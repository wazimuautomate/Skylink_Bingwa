package com.example.data.sync

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * How the worker reaches the process-wide [SyncOrchestrator] without depending on the
 * application class (which other parts of the codebase own).
 *
 * `SkylinkBingwaApplication` implements this and returns its single orchestrator instance,
 * so a background sync refreshes exactly the same repository, StateFlows and on-device
 * store the UI observes — never a second, divergent copy.
 *
 * The property is nullable so a host that has not built an orchestrator (or a
 * Robolectric/unit-test application) simply yields null and the worker succeeds quietly.
 */
interface SyncOrchestratorProvider {
    val syncOrchestrator: SyncOrchestrator?
}

/**
 * Background incremental sync of shared application content: seller config, offers,
 * billboards, notification templates, published notifications and SMS rules.
 *
 * It delegates the whole decision to [SyncOrchestrator], which fetches the tiny
 * manifest first and downloads ONLY the resources whose version/checksum actually
 * changed — so the common "nothing published since last time" run costs one small
 * request and zero writes.
 *
 * Safety, unchanged from the original worker:
 *  - No provider in the process → [Result.success] (retrying cannot help).
 *  - Any unexpected throwable → [Result.retry] so WorkManager reschedules with the
 *    configured exponential backoff.
 *  - The orchestrator itself never throws and never clears cached content on failure,
 *    so this worker can never leave the app empty.
 */
class CatalogueSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val orchestrator = (applicationContext as? SyncOrchestratorProvider)?.syncOrchestrator
        // No app-scoped orchestrator (should not happen in the real process): nothing to
        // sync, and retrying will not help — succeed quietly rather than loop.
            ?: return Result.success()

        return try {
            orchestrator.sync(SyncTrigger.PERIODIC)
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {

        /** Unique name for the one-off sync, so repeated taps collapse into one run. */
        const val UNIQUE_IMMEDIATE_SYNC_WORK_NAME = "mybingwa_immediate_sync"

        /**
         * Enqueue a single, as-soon-as-possible sync from anywhere that has a [Context]
         * — a manual refresh, a connectivity change, or an FCM "config published" data
         * message. Cheap and fire-and-forget; it never blocks the caller and never
         * throws (WorkManager can be uninitialised under tests or a rare OEM fault).
         *
         * `ExistingWorkPolicy.KEEP` means a burst of triggers produces ONE run.
         *
         * ## Expedited only where it is safe
         *
         * On API 31+ an expedited request runs as an expedited JobScheduler job — no
         * notification, no foreground service, exactly what a silent sync wants. On
         * API 24–30 WorkManager instead promotes expedited work to a foreground service
         * and calls `getForegroundInfo()`, whose default implementation throws; honouring
         * it would mean showing the customer a notification for a background sync, which
         * CLAUDE.md §9 rules out ("prefer silence over a weak message"). So older devices
         * get a normal one-off request, which — with no initial delay and only a network
         * constraint — starts essentially immediately anyway.
         */
        fun enqueueImmediateSync(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_IMMEDIATE_SYNC_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    buildImmediateRequest()
                )
            }
        }

        private fun buildImmediateRequest(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val builder = OneTimeWorkRequestBuilder<CatalogueSyncWorker>()
                .setConstraints(constraints)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Quota exhausted → run as ordinary work rather than failing the request.
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            return builder.build()
        }
    }
}
