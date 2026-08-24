package com.example.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.SkylinkBingwaApplication
import com.example.core.notifications.ConnectionState

import com.example.core.notifications.ConnectivityObserver
import com.example.core.notifications.EngagementSchedule
import com.example.core.notifications.EngagementSlot
import com.example.core.notifications.engine.NotificationCategory
import java.util.concurrent.TimeUnit

/**
 * Posts the daily engagement notification for ONE slot, then schedules the next one.
 *
 * A self-rescheduling chain of one-shot jobs rather than a periodic job: the fire
 * time inside each window is different every day (see [EngagementSchedule]), which a
 * fixed-interval periodic job cannot express. Each run enqueues exactly one
 * successor under the same unique name, so the chain can never fork — a duplicate
 * enqueue REPLACEs rather than adds.
 *
 * Nothing here is loud. A run can decide to stay silent and that is a normal, quiet
 * success (CLAUDE.md §9, "prefer silence over a weak message"):
 *  - the live connection does not match what the slot is for;
 *  - this slot already posted today (survives process death via prefs);
 *  - the shared NotificationEngine policy suppresses it (quiet hours, daily cap,
 *    cooldown, duplicate content), or notifications are not permitted at all.
 */
class EngagementNotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()

        // Which slot were we scheduled for? An absent/unknown name means the request was
        // built by an older version of the app; just re-schedule from now and stop.
        val slot = inputData.getString(KEY_SLOT)
            ?.let { name -> EngagementSlot.entries.firstOrNull { it.name == name } }

        if (slot != null) {
            postIfDue(slot, now)
        }

        scheduleNext(applicationContext, now)
        return Result.success()
    }

    /** Post the slot's message when — and only when — every condition still holds. */
    private suspend fun postIfDue(slot: EngagementSlot, now: Long) {
        val key = EngagementSchedule.slotKey(slot, now)
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Already posted this slot today (a retried or duplicated run) → stay silent.
        if (prefs.getBoolean(key, false)) return

        // The slot is written for a specific connection state. WorkManager may run us
        // late or early, so the state is read HERE, not when the job was scheduled.
        val connected = ConnectivityObserver(applicationContext).current() != ConnectionState.NONE
        if (connected != slot.requiresConnection) return

        // Post through the shared NotificationEngine rather than straight to AppNotifier.
        // The engine owns the cooldowns, the per-day cap, quiet hours and content
        // de-duplication for EVERY notification the app sends, so routing through it is
        // what stops these daily nudges stacking on top of whatever the personalisation
        // paths already posted. `notifyRaw` keeps the wording authored here while still
        // passing the full policy check — the engine may well decide to stay silent, and
        // that is a correct outcome.
        val app = applicationContext as? SkylinkBingwaApplication ?: return
        val engine = app.notificationEngine
        val offers = app.repository.offers.value
        val message = EngagementSchedule.messageFor(slot, now, offers)
        val posted = engine.notifyRaw(
            category = engagementCategory(slot),
            title = message.title,
            body = message.body,
            stableId = key,
            deepLinkRoute = message.deepLinkRoute,
            nowMillis = now,
        )
        // Only mark it done once it actually reached the system. A refusal (permission
        // not granted, or the policy suppressing it) leaves the slot open so it can
        // still land on a later day.
        if (posted) prefs.edit().putBoolean(key, true).apply()
    }

    companion object {
        /**
         * Dedicated per-time-of-day category, NOT [NotificationCategory.ONLINE] /
         * [NotificationCategory.OFFLINE] (those are reserved for the connectivity-change
         * nudge in [com.example.MainActivity]). Sharing a category would mean an ordinary
         * connectivity blip while the app happens to be open could consume the category's
         * cooldown and silently block that day's engagement post. [NotificationCategory.MORNING]
         * / [NotificationCategory.EVENING] carry a 24h cooldown, which is also the
         * semantically correct budget for a once-a-day nudge (the two slots per time of
         * day are mutually exclusive at fire time, so one category per time of day is
         * exactly right).
         */
        private fun engagementCategory(slot: EngagementSlot): NotificationCategory =
            if (slot == EngagementSlot.MORNING_DATA || slot == EngagementSlot.MORNING_TALK) {
                NotificationCategory.MORNING
            } else {
                NotificationCategory.EVENING
            }

        private const val PREFS = "mybingwa_engagement"
        private const val KEY_SLOT = "slot"

        /** Unique work name. One chain, replaced rather than duplicated on re-enqueue. */
        const val UNIQUE_WORK_NAME = "mybingwa_engagement_notifications"

        /**
         * Enqueue the next due slot as a one-shot job.
         *
         * [policy] matters a lot here and the two call sites deliberately use different
         * values:
         *  - The worker itself ([doWork], at the tail of a run that just finished) passes
         *    the default `REPLACE`: this job's own WorkSpec is already on its way to
         *    SUCCEEDED, so replacing the (now-nonexistent) current occupant of the unique
         *    name with the next one is correct and cannot race itself.
         *  - [com.example.SkylinkBingwaApplication.onCreate] passes `KEEP`. Android always runs
         *    `Application.onCreate()` before dispatching a WorkManager job in that process,
         *    so a cold start caused BY this very engagement job firing would otherwise call
         *    `scheduleNext` with `REPLACE` and cancel the job that is about to run —
         *    silently eating every notification (the original bug: the chain rescheduled
         *    itself out from under itself on essentially every real-world cold start,
         *    because the app is rarely still warm hours later when a slot fires). `KEEP`
         *    leaves an already-pending/running job alone and only seeds a new one when the
         *    chain is genuinely missing (first install, or repair after a reboot, force-stop
         *    or app update) — the same pattern already used for the periodic catalogue sync.
         *
         * No network constraint: the offline slots exist precisely to reach a customer
         * with no connection, and the copy is built on the device.
         */
        fun scheduleNext(
            context: Context,
            nowMillis: Long = System.currentTimeMillis(),
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        ) {
            val (slot, at) = EngagementSchedule.nextOccurrence(nowMillis) ?: return
            val delay = (at - nowMillis).coerceAtLeast(0L)

            val request = OneTimeWorkRequestBuilder<EngagementNotificationWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putString(KEY_SLOT, slot.name).build())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                request,
            )
        }
    }
}
