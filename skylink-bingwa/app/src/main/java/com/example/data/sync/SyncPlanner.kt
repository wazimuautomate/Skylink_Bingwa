package com.example.data.sync

/**
 * The pure decision core of the sync engine: given what the server says it has, what
 * this device already holds, why the sync was triggered and when it last tried, decide
 * exactly which resources to download.
 *
 * Deliberately free of Android, coroutine, network and clock dependencies — `nowMillis`
 * is injected — so every rule below is covered by plain JUnit tests. Do NOT add an
 * `android.*` import to this file.
 *
 * ## Rules
 *
 * 1. **Skip what has not changed.** A resource is skipped only when the server's
 *    `version` AND `checksum` both equal the locally stored ones. Requiring both means
 *    a forgotten version bump, or a version reused after a rollback, still re-downloads.
 * 2. **Never stop syncing because the manifest is missing.** `remote == null` (offline,
 *    server error, or a server old enough not to serve `get_sync_manifest.php`) plans
 *    ALL resources, subject to the trigger throttle. This fallback is mandatory —
 *    without it a manifest outage would silently freeze every device's content.
 * 3. **Throttle by trigger, not by wall clock.** Cheap, user-invisible triggers fire
 *    often, so each carries a minimum interval per resource (CLAUDE.md §11: never
 *    waste battery or bandwidth). MANUAL_REFRESH is never throttled — the customer
 *    asked. PERIODIC is never throttled — WorkManager already spaces it hours apart.
 * 4. **A publish beats every throttle.** When the manifest's `publishVersion` differs
 *    from the last one this device acted on, the owner pressed Publish (e.g. the
 *    displayed Paybill number stopped working). Every CHANGED resource is planned
 *    immediately, whatever the throttle says.
 */
object SyncPlanner {

    // --- reasons (machine-readable; tests and debug logs only, never customer copy) --

    /** Nothing to do: the server's fingerprints match what this device already holds. */
    const val REASON_UP_TO_DATE = "up_to_date"

    /** Something changed, but every candidate is inside this trigger's throttle window. */
    const val REASON_THROTTLED = "throttled"

    /** The admin published: publishVersion moved. Throttles are bypassed. */
    const val REASON_FORCE_PUBLISH = "force_publish"

    /** No manifest reachable — sync everything we can, subject to throttle. */
    const val REASON_NO_MANIFEST = "no_manifest"

    /** The customer explicitly asked for a refresh. */
    const val REASON_MANUAL_REFRESH = "manual_refresh"

    /** Ordinary incremental case: one or more resource fingerprints moved. */
    const val REASON_VERSION_CHANGED = "version_changed"

    // ------------------------------------------------------------------ throttling --

    private const val MINUTE_MILLIS = 60_000L

    /**
     * The minimum time that must pass since a resource's last ATTEMPT before that
     * trigger may retry it.
     *
     * Windows are chosen against CLAUDE.md §11 (low-cost Android hardware, battery and
     * data are precious):
     *  - APP_START 5 min — restarting the app repeatedly must not hammer the backend.
     *  - CONNECTIVITY_RESTORED 2 min — a flaky cell connection flaps constantly; 2
     *    minutes absorbs the flapping while still feeling immediate to a customer who
     *    just walked back into coverage.
     *  - APP_RESUME 15 min — resuming from the recents list is very frequent and almost
     *    never coincides with a publish; the 90-second force-sync watcher covers
     *    urgency, so this window can be generous.
     *  - PERIODIC 0 — WorkManager already spaces this job hours apart.
     *  - MANUAL_REFRESH 0 — the customer asked; honouring it is the whole point.
     *  - FORCE_PUBLISH 0 — an owner publish must reach online devices within minutes.
     */
    fun minIntervalMillis(trigger: SyncTrigger): Long = when (trigger) {
        SyncTrigger.APP_START -> 5 * MINUTE_MILLIS
        SyncTrigger.CONNECTIVITY_RESTORED -> 2 * MINUTE_MILLIS
        SyncTrigger.APP_RESUME -> 15 * MINUTE_MILLIS
        SyncTrigger.PERIODIC -> 0L
        SyncTrigger.MANUAL_REFRESH -> 0L
        SyncTrigger.FORCE_PUBLISH -> 0L
    }

    // --------------------------------------------------------------------- planning --

    /**
     * Decide what to download.
     *
     * @param remote the server manifest, or null when it could not be fetched.
     * @param local the last successfully synced fingerprint per [SyncResource.name].
     * @param trigger why this run started.
     * @param nowMillis injected clock (epoch millis).
     * @param lastAttemptAtByResource last ATTEMPT (success or failure) per resource
     *        name. Recording attempts rather than successes stops a permanently failing
     *        resource from being retried on every trigger.
     * @param lastPublishVersion the manifest publishVersion this device last acted on.
     */
    fun plan(
        remote: SyncManifest?,
        local: Map<String, ResourceVersion>,
        trigger: SyncTrigger,
        nowMillis: Long,
        lastAttemptAtByResource: Map<String, Long>,
        lastPublishVersion: Long
    ): SyncPlan {
        // The owner published since we last looked → bypass every throttle window.
        val forcePublish = remote != null && remote.publishVersion != lastPublishVersion

        // Candidates: everything whose server fingerprint we cannot prove we already
        // hold. With no manifest that is, correctly, everything.
        val candidates = SyncResource.values().filter { resource ->
            if (remote == null) {
                true
            } else {
                val remoteVersion = remote.resources[resource.name]
                // A resource the server does not describe is treated as UNKNOWN, not as
                // "unchanged": an older/partial manifest must never silently freeze it.
                if (remoteVersion == null) true else changed(remoteVersion, local[resource.name])
            }
        }

        if (candidates.isEmpty()) {
            return SyncPlan(resources = emptyList(), reason = REASON_UP_TO_DATE)
        }

        val interval = if (forcePublish) 0L else minIntervalMillis(trigger)
        val due = candidates.filter { resource ->
            val lastAttempt = lastAttemptAtByResource[resource.name] ?: 0L
            val elapsed = nowMillis - lastAttempt
            // A negative elapsed means the device clock moved backwards (manual change,
            // NTP correction). Treat that as "due" so a bad clock can never permanently
            // disable syncing — the worst case is one extra, cheap request.
            elapsed < 0L || elapsed >= interval
        }

        if (due.isEmpty()) {
            return SyncPlan(resources = emptyList(), reason = REASON_THROTTLED)
        }

        val reason = when {
            forcePublish -> REASON_FORCE_PUBLISH
            remote == null -> REASON_NO_MANIFEST
            trigger == SyncTrigger.MANUAL_REFRESH -> REASON_MANUAL_REFRESH
            else -> REASON_VERSION_CHANGED
        }
        return SyncPlan(resources = due, reason = reason)
    }

    /**
     * True when [remote] describes different content from what this device holds.
     *
     * ONLY `version` and `checksum` are compared — deliberately NOT `updatedAt`, and
     * deliberately not `ResourceVersion` equality, which would include it. The server
     * stamps every resource with the same publish timestamp, so comparing `updatedAt`
     * would mark all six resources changed after any publish and re-download the whole
     * catalogue every time — exactly the waste incremental sync exists to remove.
     *
     * A resource this device has never synced ([local] == null) always counts as changed.
     */
    private fun changed(remote: ResourceVersion, local: ResourceVersion?): Boolean {
        if (local == null) return true
        return remote.version != local.version || remote.checksum != local.checksum
    }
}
