package com.example.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "the owner just published, everyone must see it now" path.
 *
 * The admin console has a Preview & Publish flow. When the owner publishes — most
 * critically when a displayed Paybill or Till number stopped working and was replaced —
 * every device that is online must pick the change up within minutes: no Play Store
 * update, no reinstall, no manual refresh, no cache clearing.
 *
 * This watcher achieves that by polling ONLY the manifest — a few hundred bytes
 * describing versions and checksums, no offers, no billboards — and starting a
 * [SyncTrigger.FORCE_PUBLISH] sync when `publishVersion` moves. In the overwhelmingly
 * common case (nothing published) the cost is one tiny request and zero writes.
 *
 * ## Lifecycle — the caller owns the scope
 *
 * The watcher MUST only run while the app is in the foreground. It does not observe
 * the lifecycle itself; the caller does, in one of two ways:
 *
 *  - `start(scope)` / `stop()` from an owner that already tracks foreground state
 *    (e.g. a `DefaultLifecycleObserver` on `ProcessLifecycleOwner`), or
 *  - `watch()` hosted directly in a Compose `LaunchedEffect`, which cancels the
 *    coroutine automatically when the composition leaves.
 *
 * Either way, cancelling the scope stops the polling immediately: [watch] suspends in
 * [delay], which is cancellable. Nothing keeps running in the background, so this can
 * never drain battery while the app is not on screen.
 *
 * ## Why 90 seconds (CLAUDE.md §11)
 *
 * §11 forbids wasting battery and forbids blocking startup, and the app targets
 * Samsung A05/A06-class hardware. 90 seconds is the balance point:
 *
 *  - It only ever runs while the screen is on and the app is in front, so the radio is
 *    already awake for the user's own activity — the poll costs no extra wakelock and
 *    no extra radio power-up. This is precisely why the interval can be short: it is
 *    NOT a background job.
 *  - A request of a few hundred bytes every 90 s is roughly 0.5 MB per continuous
 *    24 hours of foreground use — far below normal app traffic, and real foreground
 *    sessions are minutes, not hours.
 *  - A publish therefore reaches an online, in-app customer in under two minutes,
 *    which is what "the Till number changed" demands. A longer window (5–10 min) would
 *    leave customers paying to a dead Till; a shorter one would be measurable battery
 *    cost for no user-visible benefit.
 *
 * Devices that are backgrounded or offline are covered by the other triggers
 * (APP_START, APP_RESUME, CONNECTIVITY_RESTORED and the periodic WorkManager job), so
 * nothing depends on this watcher for correctness — it only shortens the worst case.
 */
class ForceSyncWatcher(
    private val orchestrator: SyncOrchestrator,
    private val manifestSource: RemoteSyncManifestSource?,
    private val metadataStore: SyncMetadataStore,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS
) {

    @Volatile
    private var job: Job? = null

    /**
     * Start polling on [scope]. Idempotent: calling it while already running does
     * nothing, so a re-entered foreground callback cannot start a second poller.
     * The caller owns [scope] and MUST cancel it (or call [stop]) when the app
     * backgrounds.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch { watch() }
    }

    /** Stop polling. Safe to call when not running. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Poll forever until cancelled. Suitable for hosting directly in a Compose
     * `LaunchedEffect(Unit) { watcher.watch() }`.
     *
     * The first [delay] happens BEFORE the first poll: whoever brought the app to the
     * foreground has already run an APP_START/APP_RESUME sync, so polling immediately
     * would be a guaranteed duplicate request.
     */
    suspend fun watch() {
        while (true) {
            // Outside runCatching on purpose: delay is the cancellation point, and its
            // CancellationException must propagate so the loop actually stops.
            delay(pollIntervalMillis)
            runCatching { pollOnce() }
        }
    }

    /**
     * One cheap check. Returns true when a publish was detected and a force sync ran.
     * Never throws.
     *
     * The manifest is fetched here AND again inside [SyncOrchestrator.sync]; that second
     * fetch is what actually decides which resources changed. Two requests for a
     * few-hundred-byte document, only on the rare run where a publish happened, is a
     * fair price for keeping the orchestrator the single owner of the sync decision.
     */
    suspend fun pollOnce(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val source = manifestSource ?: return false
        val manifest = runCatching { source.fetch() }.getOrNull() ?: return false
        val known = runCatching { metadataStore.load().lastPublishVersion }.getOrElse { return false }
        if (manifest.publishVersion == known) return false
        runCatching { orchestrator.sync(SyncTrigger.FORCE_PUBLISH, nowMillis) }
        return true
    }

    companion object {
        /** See the class KDoc for the battery/latency justification of 90 seconds. */
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 90_000L
    }
}
