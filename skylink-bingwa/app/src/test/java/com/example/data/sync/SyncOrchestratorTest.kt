package com.example.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the orchestrator around the planner: only changed resources are
 * refreshed, one failing resource never poisons the others, a failure never advances a
 * version and never wipes anything, and overlapping triggers cannot run concurrently.
 *
 * Everything is faked — no Android, no network, no DataStore.
 */
class SyncOrchestratorTest {

    private val now = 1_700_000_000_000L

    // ------------------------------------------------------------------- test doubles

    /** Records which targets were called and can be told to fail specific ones. */
    private class RecordingTargets(
        private val failing: Set<SyncResource> = emptySet()
    ) : SyncTargets {
        val called = ArrayList<SyncResource>()

        private fun record(resource: SyncResource) {
            called.add(resource)
            if (resource in failing) error("simulated failure for $resource")
        }

        override suspend fun syncRemoteConfig() = record(SyncResource.CONFIG)
        override suspend fun syncCatalogue() = record(SyncResource.OFFERS)
        override suspend fun syncBillboards() = record(SyncResource.BILLBOARDS)
        override suspend fun syncNotificationTemplates() = record(SyncResource.NOTIFICATION_TEMPLATES)
        override suspend fun syncRemoteNotifications() = record(SyncResource.REMOTE_NOTIFICATIONS)
    }

    /** Blocks inside every target call until [gate] completes. */
    private class GatedTargets(private val gate: CompletableDeferred<Unit>) : SyncTargets {
        var callCount = 0

        private suspend fun block() {
            callCount++
            gate.await()
        }

        override suspend fun syncRemoteConfig() = block()
        override suspend fun syncCatalogue() = block()
        override suspend fun syncBillboards() = block()
        override suspend fun syncNotificationTemplates() = block()
        override suspend fun syncRemoteNotifications() = block()
    }

    private class FakeManifestSource(private val manifest: SyncManifest?) : RemoteSyncManifestSource {
        var fetchCount = 0
        override suspend fun fetch(): SyncManifest? {
            fetchCount++
            return manifest
        }
    }

    private class ThrowingManifestSource : RemoteSyncManifestSource {
        override suspend fun fetch(): SyncManifest? = throw IllegalStateException("network down")
    }

    // ------------------------------------------------------------------------ helpers

    private fun rv(version: Long, checksum: String) =
        ResourceVersion(version = version, updatedAt = now, checksum = checksum)

    private fun manifestWith(
        publishVersion: Long = 1L,
        overrides: Map<SyncResource, ResourceVersion> = emptyMap()
    ): SyncManifest {
        val base = SyncResource.values().associate { it.name to rv(10L, "abc") }.toMutableMap()
        overrides.forEach { (resource, version) -> base[resource.name] = version }
        return SyncManifest(publishVersion = publishVersion, generatedAt = now, resources = base)
    }

    private fun metadataMatching(lastPublishVersion: Long = 1L) = SyncMetadata(
        local = SyncResource.values().associate { it.name to rv(10L, "abc") },
        lastPublishVersion = lastPublishVersion
    )

    // ------------------------------------------------------------------- happy paths

    @Test
    fun nothingChanged_downloadsNothing() = runTest {
        val targets = RecordingTargets()
        val store = InMemorySyncMetadataStore(metadataMatching())
        val orchestrator = SyncOrchestrator(targets, store, FakeManifestSource(manifestWith()))

        val outcome = orchestrator.sync(SyncTrigger.MANUAL_REFRESH, now)

        assertTrue(targets.called.isEmpty())
        assertTrue(outcome.synced.isEmpty())
        assertEquals(SyncResource.values().toList(), outcome.skipped)
        assertTrue(outcome.manifestAvailable)
    }

    @Test
    fun onlyTheChangedResourceIsRefreshed() = runTest {
        val targets = RecordingTargets()
        val store = InMemorySyncMetadataStore(metadataMatching())
        val manifest = manifestWith(overrides = mapOf(SyncResource.OFFERS to rv(11L, "new")))
        val orchestrator = SyncOrchestrator(targets, store, FakeManifestSource(manifest))

        val outcome = orchestrator.sync(SyncTrigger.MANUAL_REFRESH, now)

        assertEquals(listOf(SyncResource.OFFERS), targets.called)
        assertEquals(listOf(SyncResource.OFFERS), outcome.synced)
    }

    @Test
    fun aSuccessfulTargetAdvancesItsStoredVersion() = runTest {
        val targets = RecordingTargets()
        val store = InMemorySyncMetadataStore(metadataMatching())
        val manifest = manifestWith(overrides = mapOf(SyncResource.CONFIG to rv(11L, "new-paybill")))
        val orchestrator = SyncOrchestrator(targets, store, FakeManifestSource(manifest))

        orchestrator.sync(SyncTrigger.MANUAL_REFRESH, now)

        assertEquals(rv(11L, "new-paybill"), store.load().local[SyncResource.CONFIG.name])
        assertEquals(now, store.load().lastSuccessfulSyncAt)
    }

    @Test
    fun aChangedPublishVersionForcesTheChangedResource() = runTest {
        val targets = RecordingTargets()
        val manifest = manifestWith(
            publishVersion = 42L,
            overrides = mapOf(SyncResource.CONFIG to rv(11L, "replacement-till"))
        )
        // We last acted on publish 41, hold the old config, and tried every resource a
        // second ago — APP_RESUME would normally wait a further 15 minutes.
        val store = InMemorySyncMetadataStore(
            metadataMatching(lastPublishVersion = 41L).copy(
                lastAttemptAtByResource = SyncResource.values().associate { it.name to now - 1_000L }
            )
        )
        val orchestrator = SyncOrchestrator(targets, store, FakeManifestSource(manifest))

        val outcome = orchestrator.sync(SyncTrigger.APP_RESUME, now)

        assertEquals(listOf(SyncResource.CONFIG), outcome.synced)
        assertEquals(SyncPlanner.REASON_FORCE_PUBLISH, orchestrator.lastPlanReason)
        assertEquals(42L, store.load().lastPublishVersion)
    }

    // ------------------------------------------------------------- failure isolation

    @Test
    fun oneFailingTargetDoesNotPreventTheOthers() = runTest {
        val targets = RecordingTargets(failing = setOf(SyncResource.OFFERS))
        val store = InMemorySyncMetadataStore()
        val orchestrator = SyncOrchestrator(targets, store, FakeManifestSource(manifestWith()))

        val outcome = orchestrator.sync(SyncTrigger.MANUAL_REFRESH, now)

        // Every resource was attempted, even the one after the failure.
        assertEquals(SyncResource.values().toList(), targets.called)
        assertEquals(listOf(SyncResource.OFFERS), outcome.failed)
        assertEquals(SyncResource.values().size - 1, outcome.synced.size)
        assertFalse(outcome.synced.contains(SyncResource.OFFERS))
    }

    @Test
    fun aFailingTargetDoesNotAdvanceItsVersion_soItIsRetried() = runTest {
        val targets = RecordingTargets(failing = setOf(SyncResource.OFFERS))
        val store = InMemorySyncMetadataStore()
        val manifest = manifestWith()
        val orchestrator = SyncOrchestrator(targets, store, FakeManifestSource(manifest))

        orchestrator.sync(SyncTrigger.MANUAL_REFRESH, now)

        val saved = store.load()
        // The failed resource has NO stored version; the successful ones do.
        assertEquals(null, saved.local[SyncResource.OFFERS.name])
        assertNotNull(saved.local[SyncResource.CONFIG.name])

        // A second run therefore re-plans exactly the failed resource.
        val second = RecordingTargets()
        val retry = SyncOrchestrator(second, store, FakeManifestSource(manifest))
        retry.sync(SyncTrigger.MANUAL_REFRESH, now)
        assertEquals(listOf(SyncResource.OFFERS), second.called)
    }

    @Test
    fun everyTargetFailing_wipesNothing() = runTest {
        val before = metadataMatching()
        val store = InMemorySyncMetadataStore(before)
        val targets = RecordingTargets(failing = SyncResource.values().toSet())
        // A manifest where everything changed, so everything is attempted and fails.
        val manifest = manifestWith(
            overrides = SyncResource.values().associateWith { rv(99L, "changed") }
        )
        val orchestrator = SyncOrchestrator(targets, store, FakeManifestSource(manifest))

        val outcome = orchestrator.sync(SyncTrigger.MANUAL_REFRESH, now)

        assertEquals(SyncResource.values().toList(), outcome.failed)
        assertTrue(outcome.synced.isEmpty())
        // Every previously stored fingerprint survived untouched: old content beats none.
        assertEquals(before.local, store.load().local)
        assertEquals(0L, store.load().lastSuccessfulSyncAt)
    }

    @Test
    fun aThrowingManifestSourceFallsBackToSyncingEverything() = runTest {
        val targets = RecordingTargets()
        val store = InMemorySyncMetadataStore(metadataMatching())
        val orchestrator = SyncOrchestrator(targets, store, ThrowingManifestSource())

        val outcome = orchestrator.sync(SyncTrigger.PERIODIC, now)

        assertFalse(outcome.manifestAvailable)
        assertEquals(SyncResource.values().toList(), targets.called)
        assertEquals(SyncPlanner.REASON_NO_MANIFEST, orchestrator.lastPlanReason)
    }

    @Test
    fun noManifestSourceConfigured_stillSyncsEverything() = runTest {
        val targets = RecordingTargets()
        val orchestrator = SyncOrchestrator(targets, InMemorySyncMetadataStore(), null)

        val outcome = orchestrator.sync(SyncTrigger.PERIODIC, now)

        assertEquals(SyncResource.values().toList(), outcome.synced)
        assertFalse(outcome.manifestAvailable)
    }

    @Test
    fun manifestlessSuccess_doesNotInventALocalVersion() = runTest {
        val store = InMemorySyncMetadataStore()
        val orchestrator = SyncOrchestrator(RecordingTargets(), store, null)

        orchestrator.sync(SyncTrigger.PERIODIC, now)

        // Without a manifest we do not know the server revision, so nothing is recorded
        // as "current" — the next run legitimately syncs again.
        assertTrue(store.load().local.isEmpty())
        assertEquals(now, store.load().lastSuccessfulSyncAt)
    }

    // ----------------------------------------------------------------- concurrency --

    @Test
    fun concurrentSyncCalls_doNotOverlap() = runTest {
        val gate = CompletableDeferred<Unit>()
        val targets = GatedTargets(gate)
        val orchestrator = SyncOrchestrator(targets, InMemorySyncMetadataStore(), null)

        val first = launch { orchestrator.sync(SyncTrigger.PERIODIC, now) }
        advanceUntilIdle()   // let `first` reach the gate inside the first target call
        assertEquals(1, targets.callCount)

        // A second trigger arrives while the first run is still in flight.
        val second = orchestrator.sync(SyncTrigger.MANUAL_REFRESH, now)

        assertTrue(second.synced.isEmpty())
        assertTrue(second.failed.isEmpty())
        assertEquals(SyncResource.values().toList(), second.skipped)
        // Still exactly one in-flight target call: the second run did not duplicate work.
        assertEquals(1, targets.callCount)

        gate.complete(Unit)
        first.join()
        assertEquals(SyncResource.values().size, targets.callCount)
    }

    @Test
    fun throttledTriggerRecordsAnAttemptSoItDoesNotHammerTheBackend() = runTest {
        val targets = RecordingTargets()
        val store = InMemorySyncMetadataStore()
        val orchestrator = SyncOrchestrator(targets, store, null)

        orchestrator.sync(SyncTrigger.APP_START, now)
        assertEquals(SyncResource.values().size, targets.called.size)

        // Immediately restarting the app must not re-download anything.
        val second = RecordingTargets()
        val throttled = SyncOrchestrator(second, store, null)
        throttled.sync(SyncTrigger.APP_START, now + 1_000L)

        assertTrue(second.called.isEmpty())
        assertEquals(SyncPlanner.REASON_THROTTLED, throttled.lastPlanReason)
    }
}
