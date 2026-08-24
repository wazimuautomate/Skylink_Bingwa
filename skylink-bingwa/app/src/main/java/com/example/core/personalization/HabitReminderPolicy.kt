package com.example.core.personalization

/**
 * What, if anything, the local reminder engine may nudge about.
 *
 * These are decisions, not messages. The notification engine owns the copy, the
 * permission state, quiet hours and the campaign caps; this file only says
 * whether a nudge is justified by the customer's own local buying habit.
 */
enum class HabitNudge {
    /** Say nothing. Silence is always preferred over a weak message (CLAUDE.md §9). */
    NONE,

    /** They normally buy around this time of day and have not bought today yet. */
    USUAL_TIME_PASSED,

    /** They are a regular buyer but nothing has been bought for several days. */
    INACTIVE_SEVERAL_DAYS
}

/**
 * Decides whether this installation's own buying habit justifies a local
 * reminder. Pure, deterministic, offline — it never reads a clock, never touches
 * the network and never sees a message body.
 *
 * Restraint is the point (CLAUDE.md §9):
 *
 * - A brand-new customer is **never** nudged; the profile must be non-empty and
 *   carry at least [MIN_PURCHASES_FOR_CONFIDENCE] purchases, and must not be
 *   [BuyerFrequency.NEW].
 * - A customer who already bought today is never nudged.
 * - At most one nudge per [MIN_HOURS_BETWEEN_NUDGES] hours, enforced here rather
 *   than trusted to the caller.
 * - Inactivity outranks the time-of-day nudge, so the two never compete.
 *
 * The result is only ever an input to the notification engine, which still
 * applies permission, quiet hours, suppression and deduplication before anything
 * reaches the customer.
 */
object HabitReminderPolicy {

    /** Learned purchases required before any habit is considered confident enough to act on. */
    const val MIN_PURCHASES_FOR_CONFIDENCE = 5

    /** Minimum gap between two habit nudges. */
    const val MIN_HOURS_BETWEEN_NUDGES = 24

    /** Whole Nairobi days without a purchase before a regular buyer counts as inactive. */
    const val INACTIVE_DAYS_THRESHOLD = 5

    /** Grace after the usual hour before "their time has passed" is true. */
    const val USUAL_TIME_GRACE_MINUTES = 15

    /**
     * @param lastNudgeAtMillis when a habit nudge was last shown, or 0 when never.
     */
    fun evaluate(
        profile: BehaviourProfile,
        nowMillis: Long,
        lastNudgeAtMillis: Long
    ): HabitNudge {
        if (profile.isEmpty()) return HabitNudge.NONE
        if (profile.totalPurchases < MIN_PURCHASES_FOR_CONFIDENCE) return HabitNudge.NONE
        if (profile.frequency == BuyerFrequency.NEW) return HabitNudge.NONE
        if (profile.lastPurchaseAtMillis <= 0L) return HabitNudge.NONE

        // Minimum gap since the previous nudge, enforced here.
        if (lastNudgeAtMillis > 0L) {
            val sinceLastNudge = nowMillis - lastNudgeAtMillis
            if (sinceLastNudge < MIN_HOURS_BETWEEN_NUDGES * NairobiTime.HOUR_MILLIS) {
                return HabitNudge.NONE
            }
        }

        val daysSincePurchase = NairobiTime.daysBetween(profile.lastPurchaseAtMillis, nowMillis)

        // Already bought today (or a clock skew put the last purchase ahead of now).
        if (daysSincePurchase < 1L) return HabitNudge.NONE

        if (daysSincePurchase >= INACTIVE_DAYS_THRESHOLD) return HabitNudge.INACTIVE_SEVERAL_DAYS

        val usualHour = profile.usualPurchaseHour
        if (usualHour !in 0..23) return HabitNudge.NONE

        val nowMinuteOfDay = NairobiTime.minuteOfDay(nowMillis)
        val usualMinuteOfDay = usualHour * 60 + USUAL_TIME_GRACE_MINUTES
        return if (nowMinuteOfDay >= usualMinuteOfDay) {
            HabitNudge.USUAL_TIME_PASSED
        } else {
            HabitNudge.NONE
        }
    }
}
