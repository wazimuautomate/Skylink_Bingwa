package com.example.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules of the incremental planner. Pure JVM — no Android, no coroutines, no clock:
 * `nowMillis` is injected, so throttle windows are exercised deterministically.
 */
class SyncPlannerTest {

    /** An arbitrary "now" far enough from 0 that every throttle window has elapsed. */
    private val now = 1_700_000_000_000L

    private fun rv(version: Long, checksum: String) =
        ResourceVersion(version = version, updatedAt = now, checksum = checksum)

    /** A manifest whose six resources all carry the same fingerprint. */
    private fun manifest(
        publishVersion: Long = 1L,
        version: Long = 10L,
        checksum: String = "abc"
    ) = SyncManifest(
        publishVersion = publishVersion,
        generatedAt = now,
        resources = SyncResource.values().associate { it.name to rv(version, checksum) }
    )

    /** Local metadata matching [manifest]'s default fingerprint for every resource. */
    private fun localMatching(version: Long = 10L, checksum: String = "abc") =
        SyncResource.values().associate { it.name to rv(version, checksum) }

    // ------------------------------------------------------------------ skip rules --

    @Test
    fun identicalVersionAndChecksum_isSkippedEntirely() {
        val plan = SyncPlanner.plan(
            remote = manifest(),
            local = localMatching(),
            trigger = SyncTrigger.MANUAL_REFRESH,
            nowMillis = now,
            lastAttemptAtByResource = emptyMap(),
            lastPublishVersion = 1L
        )
        assertTrue(plan.resources.isEmpty())
        assertEquals(SyncPlanner.REASON_UP_TO_DATE, plan.reason)
    }

    @Test
    fun differingVersion_isPlanned() {
        val local = localMatching().toMutableMap()
        local[SyncResource.OFFERS.name] = rv(9L, "abc")   // version moved, checksum same

        val plan = SyncPlanner.plan(
            remote = manifest(),
            local = local,
            trigger = SyncTrigger.MANUAL_REFRESH,
            nowMillis = now,
            lastAttemptAtByResource = emptyMap(),
            lastPublishVersion = 1L
        )
        assertEquals(listOf(SyncResource.OFFERS), plan.resources)
        assertEquals(SyncPlanner.REASON_MANUAL_REFRESH, plan.reason)
    }

    @Test
    fun sameVersionButDifferentChecksum_isStillPlanned() {
        val local = localMatching().toMutableMap()
        local[SyncResource.CONFIG.name] = rv(10L, "STALE") // version same, content moved

        val plan = SyncPlanner.plan(
            remote = manifest(),
            local = local,
            trigger = SyncTrigger.MANUAL_REFRESH,
            nowMillis = now,
            lastAttemptAtByResource = emptyMap(),
            lastPublishVersion = 1L
        )
        assertEquals(listOf(SyncResource.CONFIG), plan.resources)
    }

    @Test
    fun updatedAtAloneNeverTriggersADownload() {
        // The server stamps every resource with the same publish timestamp. If the
        // planner compared it, one publish would re-download the whole catalogue.
        val local = SyncResource.values().associate {
            it.name to ResourceVersion(version = 10L, updatedAt = 1L, checksum = "abc")
        }
        val plan = SyncPlanner.plan(
            remote = manifest(),                 // same version + checksum, updatedAt = now
            local = local,
            trigger = SyncTrigger.MANUAL_REFRESH,
            nowMillis = now,
            lastAttemptAtByResource = emptyMap(),
            lastPublishVersion = 1L
        )
        assertTrue(plan.resources.isEmpty())
        assertEquals(SyncPlanner.REASON_UP_TO_DATE, plan.reason)
    }

    @Test
    fun aResourceNeverSyncedBefore_isPlanned() {
        val plan = SyncPlanner.plan(
            remote = manifest(),
            local = emptyMap(),
            trigger = SyncTrigger.MANUAL_REFRESH,
            nowMillis = now,
            lastAttemptAtByResource = emptyMap(),
            lastPublishVersion = 1L
        )
        assertEquals(SyncResource.values().toList(), plan.resources)
    }

    @Test
    fun onlyTheChangedResourceIsPlanned_notTheWholeCatalogue() {
        val local = localMatching().toMutableMap()
        local[SyncResource.CONFIG.name] = rv(11L, "new-paybill")

        val plan = SyncPlanner.plan(
            remote = manifest(),
            local = local,
            trigger = SyncTrigger.PERIODIC,
            nowMillis = now,
            lastAttemptAtByResource = emptyMap(),
            lastPublishVersion = 1L
        )
        assertEquals(listOf(SyncResource.CONFIG), plan.resources)
        assertFalse(plan.resources.contains(SyncResource.OFFERS))
        assertFalse(plan.resources.contains(SyncResource.BILLBOARDS))
    }

    @Test
    fun resourceMissingFromManifest_isTreatedAsUnknownAndPlanned() {
        // An older/partial manifest must never silently freeze a resource.
        val remote = SyncManifest(
            publishVersion = 1L,
            generatedAt = now,
            resources = mapOf(SyncResource.OFFERS.name to rv(10L, "abc"))
        )
        val plan = SyncPlanner.plan(
            remote = remote,
            local = localMatching(),
            trigger = SyncTrigger.PERIODIC,
            nowMillis = now,
            lastAttemptAtByResource = emptyMap(),
            lastPublishVersion = 1L
        )
        assertFalse(plan.resources.contains(SyncResource.OFFERS))
        assertTrue(plan.resources.contains(SyncResource.CONFIG))
        assertTrue(plan.resources.contains(SyncResource.BILLBOARDS))
    }

    @Test
    fun unknownResourceKeyFromAFutureServer_isIgnoredNotCrashed() {
        val resources = localMatching().toMutableMap()
        resources["A_RESOURCE_THIS_BUILD_HAS_NEVER_HEARD_OF"] = rv(99L, "zzz")
        val remote = SyncManifest(publishVersion = 1L, generatedAt = now, resources = resources)

        val plan = SyncPlanner.plan(
            remote = remote,
            local = localMatching(),
            trigger = SyncTrigger.PERIODIC,
            nowMillis = now,
            lastAttemptAtByResource = emptyMap(),
            lastPublishVersion = 1L
        )
        assertTrue(plan.resources.isEmpty())
    }

    // -------------------------------------------------------------- manifest-less ---

    @Test
    fun nullManifest_plansEverything() {
        val plan = SyncPlanner.plan(
            remote = null,
            local = localMatching(),
            trigger = SyncTrigger.PERIODIC,
            nowMillis = now,
            lastAttemptAtByResource = emptyMap(),
            lastPublishVersion = 1L
        )
        assertEquals(SyncResource.values().toList(), plan.resources)
        assertEquals(SyncPlanner.REASON_NO_MANIFEST, plan.reason)
    }

    @Test
    fun nullManifest_isStillSubjectToThrottle() {
        val justTried = SyncResource.values().associate { it.name to now - 1_000L }
        val plan = SyncPlanner.plan(
            remote = null,
            local = emptyMap(),
            trigger = SyncTrigger.APP_START,   // 5-minute window, tried 1 second ago
            nowMillis = now,
            lastAttemptAtByResource = justTried,
            lastPublishVersion = 0L
        )
        assertTrue(plan.resources.isEmpty())
        assertEquals(SyncPlanner.REASON_THROTTLED, plan.reason)
    }

    // ------------------------------------------------------------------- throttling --

    @Test
    fun everyTriggerHasTheDocumentedWindow() {
        assertEquals(5 * 60_000L, SyncPlanner.minIntervalMillis(SyncTrigger.APP_START))
        assertEquals(2 * 60_000L, SyncPlanner.minIntervalMillis(SyncTrigger.CONNECTIVITY_RESTORED))
        assertEquals(15 * 60_000L, SyncPlanner.minIntervalMillis(SyncTrigger.APP_RESUME))
        assertEquals(0L, SyncPlanner.minIntervalMillis(SyncTrigger.PERIODIC))
        assertEquals(0L, SyncPlanner.minIntervalMillis(SyncTrigger.MANUAL_REFRESH))
        assertEquals(0L, SyncPlanner.minIntervalMillis(SyncTrigger.FORCE_PUBLISH))
    }

    @Test
    fun appStart_insideItsWindow_isThrottled_outsideItIsNot() {
        val changed = SyncManifest(
            publishVersion = 1L,
            generatedAt = now,
            resources = mapOf(SyncResource.OFFERS.name to rv(11L, "new"))
        )
        val local = mapOf(SyncResource.OFFERS.name to rv(10L, "old"))
        val attempts = mapOf(SyncResource.OFFERS.name to now - (4 * 60_000L)) // 4 min ago

        val throttled = SyncPlanner.plan(
            remote = changed, local = local, trigger = SyncTrigger.APP_START,
            nowMillis = now, lastAttemptAtByResource = attempts, lastPublishVersion = 1L
        )
        assertFalse(throttled.resources.contains(SyncResource.OFFERS))

        val due = SyncPlanner.plan(
            remote = changed, local = local, trigger = SyncTrigger.APP_START,
            nowMillis = now + (2 * 60_000L),   // now 6 minutes since the attempt
            lastAttemptAtByResource = attempts, lastPublishVersion = 1L
        )
        assertTrue(due.resources.contains(SyncResource.OFFERS))
    }

    @Test
    fun connectivityRestored_flappingWithinTwoMinutes_isThrottled() {
        val plan = SyncPlanner.plan(
            remote = null, local = emptyMap(),
            trigger = SyncTrigger.CONNECTIVITY_RESTORED,
            nowMillis = now,
            lastAttemptAtByResource = SyncResource.values().associate { it.name to now - 30_000L },
            lastPublishVersion = 0L
        )
        assertTrue(plan.resources.isEmpty())
    }

    @Test
    fun appResume_withinFifteenMinutes_isThrottled() {
        val plan = SyncPlanner.plan(
            remote = null, local = emptyMap(),
            trigger = SyncTrigger.APP_RESUME,
            nowMillis = now,
            lastAttemptAtByResource = SyncResource.values().associate { it.name to now - (10 * 60_000L) },
            lastPublishVersion = 0L
        )
        assertTrue(plan.resources.isEmpty())
    }

    @Test
    fun manualRefresh_ignoresTheThrottle() {
        val plan = SyncPlanner.plan(
            remote = null, local = emptyMap(),
            trigger = SyncTrigger.MANUAL_REFRESH,
            nowMillis = now,
            lastAttemptAtByResource = SyncResource.values().associate { it.name to now - 1L },
            lastPublishVersion = 0L
        )
        assertEquals(SyncResource.values().toList(), plan.resources)
    }

    @Test
    fun periodic_ignoresTheThrottle() {
        val plan = SyncPlanner.plan(
            remote = null, local = emptyMap(),
            trigger = SyncTrigger.PERIODIC,
            nowMillis = now,
            lastAttemptAtByResource = SyncResource.values().associate { it.name to now - 1L },
            lastPublishVersion = 0L
        )
        assertEquals(SyncResource.values().toList(), plan.resources)
    }

    @Test
    fun backwardsClock_doesNotPermanentlyDisableSyncing() {
        // lastAttempt in the future (clock corrected backwards) → still due.
        val plan = SyncPlanner.plan(
            remote = null, local = emptyMap(),
            trigger = SyncTrigger.APP_RESUME,
            nowMillis = now,
            lastAttemptAtByResource = SyncResource.values().associate { it.name to now + 86_400_000L },
            lastPublishVersion = 0L
        )
        assertEquals(SyncResource.values().toList(), plan.resources)
    }

    // ---------------------------------------------------------------- force publish --

    @Test
    fun changedPublishVersion_forcesTheChangedResourcesDespiteTheThrottle() {
        // The owner replaced a dead Paybill number: CONFIG changed and must go out now,
        // even though APP_RESUME normally waits 15 minutes.
        val remote = SyncManifest(
            publishVersion = 42L,
            generatedAt = now,
            resources = SyncResource.values().associate { it.name to rv(10L, "abc") }
                .toMutableMap()
                .apply { put(SyncResource.CONFIG.name, rv(11L, "new-paybill")) }
        )
        val plan = SyncPlanner.plan(
            remote = remote,
            local = localMatching(),
            trigger = SyncTrigger.APP_RESUME,
            nowMillis = now,
            lastAttemptAtByResource = SyncResource.values().associate { it.name to now - 1_000L },
            lastPublishVersion = 41L
        )
        assertEquals(listOf(SyncResource.CONFIG), plan.resources)
        assertEquals(SyncPlanner.REASON_FORCE_PUBLISH, plan.reason)
    }

    @Test
    fun changedPublishVersion_withNoChangedResource_plansNothing() {
        val plan = SyncPlanner.plan(
            remote = manifest(publishVersion = 43L),
            local = localMatching(),
            trigger = SyncTrigger.FORCE_PUBLISH,
            nowMillis = now,
            lastAttemptAtByResource = emptyMap(),
            lastPublishVersion = 42L
        )
        assertTrue(plan.resources.isEmpty())
        assertEquals(SyncPlanner.REASON_UP_TO_DATE, plan.reason)
    }

    @Test
    fun plannedResourcesKeepAStableDeclarationOrder() {
        val plan = SyncPlanner.plan(
            remote = null, local = emptyMap(), trigger = SyncTrigger.PERIODIC,
            nowMillis = now, lastAttemptAtByResource = emptyMap(), lastPublishVersion = 0L
        )
        // CONFIG first: the Till/Paybill route is the one thing that must never be stale.
        assertEquals(SyncResource.CONFIG, plan.resources.first())
    }
}
