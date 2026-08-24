package com.example.core.notifications.engine

/**
 * Everything the engine has to remember between posts so it never spams.
 *
 * Persisted as JSON through Moshi reflection, so:
 *  - every map is keyed by a plain String (enum `.name`, or a "yyyy-MM-dd"
 *    Africa/Nairobi day key). Moshi reflection cannot use an enum as a map key.
 *  - every field has a default, so a snapshot written by an older build still
 *    deserialises.
 *
 * It stores NO personal content: only category names, template ids, counters and
 * opaque content hashes (CLAUDE.md §10).
 */
data class NotificationState(
    /** category name -> epoch millis of the last successful post. */
    val lastPostedAtByCategory: Map<String, Long> = emptyMap(),
    /** category name -> the template id used last time, so wording rotates. */
    val lastTemplateIdByCategory: Map<String, String> = emptyMap(),
    /** "yyyy-MM-dd" (Africa/Nairobi) -> non-transactional notifications posted that day. */
    val postedCountByDay: Map<String, Int> = emptyMap(),
    /** Opaque hashes of recent notification content, newest last. FIFO, capped. */
    val recentContentHashes: List<String> = emptyList()
)

/**
 * Why a notification was or was not allowed. [reason] is a short, stable,
 * machine-readable token: "ok" | "cooldown" | "quiet_hours" | "daily_cap" |
 * "duplicate".
 */
data class PolicyDecision(val allowed: Boolean, val reason: String)

/**
 * The spam guard (CLAUDE.md §9: rate-limit, apply quiet hours, deduplicate,
 * prefer silence over a weak message).
 *
 * Pure Kotlin apart from [TimeOfDayResolver]'s java.util date maths, so every
 * rule is unit-testable on the JVM.
 *
 * Rules, in the order they are applied:
 *  1. A repeat of recently-posted content is always blocked ("duplicate").
 *  2. Quiet hours 22:00–06:59 Africa/Nairobi suppress everything that is not
 *     [NotificationCategory.transactional] ("quiet_hours").
 *  3. At most [MAX_NON_TRANSACTIONAL_PER_DAY] non-transactional notifications per
 *     Nairobi day ("daily_cap").
 *  4. A per-category cooldown ("cooldown").
 *
 * Transactional categories (payment received, a real Safaricom message, a gift)
 * bypass quiet hours, the daily cap and the cooldown, because each one is the
 * direct consequence of something that just happened. They are still blocked by
 * the duplicate check, which is what stops a re-delivered SMS notifying twice.
 */
object NotificationPolicy {

    const val REASON_OK = "ok"
    const val REASON_COOLDOWN = "cooldown"
    const val REASON_QUIET_HOURS = "quiet_hours"
    const val REASON_DAILY_CAP = "daily_cap"
    const val REASON_DUPLICATE = "duplicate"

    /** Non-transactional notifications allowed per Africa/Nairobi day. */
    const val MAX_NON_TRANSACTIONAL_PER_DAY = 6

    /** How many recent content hashes are retained for de-duplication. */
    const val RECENT_HASH_CAP = 40

    /** How many Nairobi day counters are retained. */
    const val DAY_COUNTER_CAP = 7

    /** Quiet hours start (inclusive), Africa/Nairobi. */
    const val QUIET_HOURS_START = 22

    /** Quiet hours end (inclusive), Africa/Nairobi. */
    const val QUIET_HOURS_END = 6

    private const val HOUR_MILLIS = 3_600_000L

    /** The minimum gap between two notifications of the same category. */
    fun cooldownMillis(category: NotificationCategory): Long = when (category) {
        NotificationCategory.OFFLINE -> 6L * HOUR_MILLIS
        NotificationCategory.ONLINE -> 6L * HOUR_MILLIS

        NotificationCategory.MORNING -> 24L * HOUR_MILLIS
        NotificationCategory.AFTERNOON -> 24L * HOUR_MILLIS
        NotificationCategory.EVENING -> 24L * HOUR_MILLIS
        NotificationCategory.LATE_NIGHT -> 24L * HOUR_MILLIS

        NotificationCategory.LOW_DATA -> 4L * HOUR_MILLIS
        NotificationCategory.LOW_SMS -> 4L * HOUR_MILLIS
        NotificationCategory.LOW_MINUTES -> 4L * HOUR_MILLIS
        NotificationCategory.VERY_LOW_DATA -> 3L * HOUR_MILLIS
        NotificationCategory.NO_DATA -> 3L * HOUR_MILLIS

        NotificationCategory.PROMOTION -> 24L * HOUR_MILLIS
        NotificationCategory.GENERAL -> 12L * HOUR_MILLIS
        NotificationCategory.HABIT_REMINDER -> 24L * HOUR_MILLIS
        NotificationCategory.INACTIVITY -> 48L * HOUR_MILLIS

        // Transactional: no time-based cooldown, de-duplicated by content hash.
        NotificationCategory.PURCHASE_SUCCESS -> 0L
        NotificationCategory.BUNDLE_RECEIVED -> 0L
        NotificationCategory.GIFT_RECEIVED -> 0L
    }

    /** True during 22:00–06:59 Africa/Nairobi. */
    fun isQuietHour(nowMillis: Long): Boolean {
        val hour = TimeOfDayResolver.hourOfDay(nowMillis)
        return hour >= QUIET_HOURS_START || hour <= QUIET_HOURS_END
    }

    /**
     * Decides whether [category] may be posted right now.
     *
     * @param contentHash an opaque hash of the composed copy, or null when the
     *        copy has not been composed yet (a cheap pre-check).
     */
    fun shouldPost(
        category: NotificationCategory,
        nowMillis: Long,
        state: NotificationState,
        contentHash: String?
    ): PolicyDecision {
        if (contentHash != null &&
            contentHash.isNotEmpty() &&
            state.recentContentHashes.contains(contentHash)
        ) {
            return PolicyDecision(false, REASON_DUPLICATE)
        }

        if (category.transactional) return PolicyDecision(true, REASON_OK)

        if (isQuietHour(nowMillis)) return PolicyDecision(false, REASON_QUIET_HOURS)

        val dayKey = TimeOfDayResolver.dayKey(nowMillis)
        val postedToday = state.postedCountByDay[dayKey] ?: 0
        if (postedToday >= MAX_NON_TRANSACTIONAL_PER_DAY) {
            return PolicyDecision(false, REASON_DAILY_CAP)
        }

        val lastAt = state.lastPostedAtByCategory[category.name]
        if (lastAt != null) {
            val cooldown = cooldownMillis(category)
            if (cooldown > 0L && nowMillis - lastAt < cooldown) {
                return PolicyDecision(false, REASON_COOLDOWN)
            }
        }

        return PolicyDecision(true, REASON_OK)
    }

    /**
     * Returns the state that results from having just posted [category]. Pure —
     * the caller persists the returned value.
     *
     * The daily counter only counts non-transactional notifications, because the
     * cap only applies to optional chatter.
     */
    fun record(
        category: NotificationCategory,
        templateId: String,
        nowMillis: Long,
        state: NotificationState,
        contentHash: String?
    ): NotificationState {
        val lastPostedAt = state.lastPostedAtByCategory.toMutableMap()
        lastPostedAt[category.name] = nowMillis

        val lastTemplateId = state.lastTemplateIdByCategory.toMutableMap()
        if (templateId.isNotEmpty()) lastTemplateId[category.name] = templateId

        val counts = state.postedCountByDay.toMutableMap()
        if (!category.transactional) {
            val dayKey = TimeOfDayResolver.dayKey(nowMillis)
            counts[dayKey] = (counts[dayKey] ?: 0) + 1
        }

        val hashes = if (contentHash != null && contentHash.isNotEmpty()) {
            (state.recentContentHashes.filter { it != contentHash } + contentHash)
                .takeLast(RECENT_HASH_CAP)
        } else {
            state.recentContentHashes.takeLast(RECENT_HASH_CAP)
        }

        return NotificationState(
            lastPostedAtByCategory = lastPostedAt.toMap(),
            lastTemplateIdByCategory = lastTemplateId.toMap(),
            postedCountByDay = prune(counts),
            recentContentHashes = hashes
        )
    }

    /** Keeps the counter map bounded: only the most recent day keys survive. */
    private fun prune(counts: Map<String, Int>): Map<String, Int> {
        if (counts.size <= DAY_COUNTER_CAP) return counts.toMap()
        val kept = LinkedHashMap<String, Int>()
        val ordered = counts.keys.sortedDescending()
        for (index in 0 until DAY_COUNTER_CAP) {
            val key = ordered[index]
            val value = counts[key]
            if (value != null) kept[key] = value
        }
        return kept
    }
}
