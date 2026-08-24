package com.example.data.sync

import kotlinx.coroutines.sync.Mutex

/**
 * What the sync engine can actually refresh. This is the ONLY seam between the engine
 * and the repository: the repository implements these six methods, the engine decides
 * which of them to call and when.
 *
 * Contract every implementation must honour (already true of the existing
 * `syncRemoteConfig` / `syncCatalogue` / `syncBillboards`):
 *
 *  - **No data loss.** A failed, empty, null or incomplete server response must KEEP
 *    the existing local content. Old content beats no content; never clear a cache
 *    because the network failed.
 *  - **Configuration only.** These methods refresh SHARED application content. They
 *    must never touch purchase history, favourites, recent recipients or personal
 *    suggestions — the device is the source of truth for user behaviour.
 *  - **Throwing is allowed.** The orchestrator wraps each call individually; a thrown
 *    exception marks that one resource failed and leaves the others untouched.
 */
interface SyncTargets {
    suspend fun syncRemoteConfig()
    suspend fun syncCatalogue()
    suspend fun syncBillboards()
    suspend fun syncNotificationTemplates()
    suspend fun syncRemoteNotifications()
}

/**
 * Runs one incremental sync pass: fetch the tiny manifest, ask [SyncPlanner] what
 * actually changed, refresh only those resources, and record what succeeded.
 *
 * Design guarantees:
 *  - **Never throws.** Every failure path returns a [SyncOutcome]. Callers (app start,
 *    connectivity, WorkManager, pull-to-refresh) can call it without a try/catch.
 *  - **Never overlaps.** A [Mutex] taken with `tryLock` means a second concurrent
 *    trigger returns immediately with an "everything skipped" outcome instead of
 *    queueing behind the first one and double-downloading.
 *  - **Never blocks the UI.** `suspend`, and every step is I/O on the caller's
 *    dispatcher (the callers use a background scope / WorkManager).
 *  - **Never wipes.** A resource's stored fingerprint advances ONLY when its target
 *    call returned successfully, so a failure is simply retried next time and its
 *    cached content stays exactly as it was.
 *
 * @param manifestSource null means "no manifest endpoint configured" — the planner
 *        then falls back to syncing everything, subject to the trigger throttle.
 */
class SyncOrchestrator(
    private val targets: SyncTargets,
    private val metadataStore: SyncMetadataStore,
    private val manifestSource: RemoteSyncManifestSource?
) {

    private val mutex = Mutex()

    /** The reason of the most recent completed plan. Debug/diagnostics only. */
    @Volatile
    var lastPlanReason: String = ""
        private set

    /**
     * Sync whatever [trigger] and the manifest say needs syncing.
     *
     * @param nowMillis injected clock so tests can drive the throttle windows.
     * @return what was synced, skipped and failed. Never throws.
     */
    suspend fun sync(
        trigger: SyncTrigger,
        nowMillis: Long = System.currentTimeMillis()
    ): SyncOutcome {
        // A sync is already in flight: return at once rather than blocking this caller
        // (which may be the UI's refresh action) or duplicating the downloads.
        if (!mutex.tryLock()) {
            return SyncOutcome(
                synced = emptyList(),
                skipped = SyncResource.values().toList(),
                failed = emptyList(),
                manifestAvailable = false
            )
        }
        return try {
            runCatching { runSync(trigger, nowMillis) }
                .getOrElse {
                    // Belt and braces: even a bug in the engine must not crash a caller
                    // or leave the app without content.
                    SyncOutcome(
                        synced = emptyList(),
                        skipped = emptyList(),
                        failed = SyncResource.values().toList(),
                        manifestAvailable = false
                    )
                }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runSync(trigger: SyncTrigger, nowMillis: Long): SyncOutcome {
        val metadata = metadataStore.load()

        // The manifest is advisory: null (offline / HTTP error / server without the
        // endpoint) degrades to "sync everything", it never aborts the run.
        val manifest = runCatching { manifestSource?.fetch() }.getOrNull()

        val plan = SyncPlanner.plan(
            remote = manifest,
            local = metadata.local,
            trigger = trigger,
            nowMillis = nowMillis,
            lastAttemptAtByResource = metadata.lastAttemptAtByResource,
            lastPublishVersion = metadata.lastPublishVersion
        )
        lastPlanReason = plan.reason

        val synced = ArrayList<SyncResource>()
        val failed = ArrayList<SyncResource>()
        val nextLocal = LinkedHashMap(metadata.local)
        val nextAttempts = LinkedHashMap(metadata.lastAttemptAtByResource)

        for (resource in plan.resources) {
            // Stamp the ATTEMPT before running it, so a resource that keeps failing is
            // throttled like any other instead of being retried on every trigger.
            nextAttempts[resource.name] = nowMillis
            val result = runCatching { refresh(resource) }
            if (result.isSuccess) {
                synced.add(resource)
                // Only a real manifest entry can advance the stored fingerprint. In
                // manifest-less mode we genuinely do not know the server revision, so we
                // leave it alone and keep re-syncing (throttled) — correct, if chattier.
                manifest?.resources?.get(resource.name)?.let { nextLocal[resource.name] = it }
            } else {
                failed.add(resource)
            }
        }

        val plannedNames = plan.resources.toSet()
        val skipped = SyncResource.values().filter { it !in plannedNames }

        metadataStore.save(
            metadata.copy(
                local = nextLocal,
                lastAttemptAtByResource = nextAttempts,
                // Record the publish revision we reacted to even when the plan was empty
                // (a publish that changed nothing this app reads), otherwise every later
                // run would keep treating it as a fresh force-publish.
                lastPublishVersion = manifest?.publishVersion ?: metadata.lastPublishVersion,
                lastManifestFetchAt = if (manifest != null) nowMillis else metadata.lastManifestFetchAt,
                lastSuccessfulSyncAt = if (synced.isNotEmpty()) nowMillis else metadata.lastSuccessfulSyncAt
            )
        )

        return SyncOutcome(
            synced = synced,
            skipped = skipped,
            failed = failed,
            manifestAvailable = manifest != null
        )
    }

    private suspend fun refresh(resource: SyncResource) {
        when (resource) {
            SyncResource.CONFIG -> targets.syncRemoteConfig()
            SyncResource.OFFERS -> targets.syncCatalogue()
            SyncResource.BILLBOARDS -> targets.syncBillboards()
            SyncResource.NOTIFICATION_TEMPLATES -> targets.syncNotificationTemplates()
            SyncResource.REMOTE_NOTIFICATIONS -> targets.syncRemoteNotifications()
        }
    }
}
