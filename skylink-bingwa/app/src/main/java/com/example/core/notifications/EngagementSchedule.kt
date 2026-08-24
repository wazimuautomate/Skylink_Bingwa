package com.example.core.notifications

import com.example.core.model.OfferCategory
import java.util.Calendar
import java.util.TimeZone

/**
 * The daily engagement notification schedule — PURE logic, no Android and no clock
 * read internally, so every decision below is deterministic and unit-testable.
 *
 * Four slots a day, each a WINDOW rather than a fixed time. The exact minute inside
 * the window is derived from the day and the slot, so it looks random to the
 * customer but is stable for a given day (the worker can be rescheduled, or run
 * twice, without moving or duplicating the notification).
 *
 * Two slots address a customer with NO connection (they cannot browse, so data is
 * what they need); two address a connected customer (data is clearly not the gap, so
 * minutes/SMS are offered instead). At fire time the worker compares the live
 * connection against [requiresConnection] and stays SILENT when it does not match —
 * CLAUDE.md §9, "prefer silence over a weak message". That silence is deliberate:
 * at most a handful of these can ever fire in a day, and usually fewer.
 *
 * Copy stays inside the §8 allowed language. None of it claims the customer is
 * running out, needs more data, or is being profiled — it only says what is on sale.
 */
enum class EngagementSlot(
    /** Window start, minutes past Nairobi midnight (inclusive). */
    val startMinuteOfDay: Int,
    /** Window end, minutes past Nairobi midnight (exclusive). */
    val endMinuteOfDay: Int,
    /** true = only fires while connected; false = only fires while offline. */
    val requiresConnection: Boolean,
) {
    /** Offline in the morning: no connection at all, so data is the gap. 06:30–08:00. */
    MORNING_DATA(6 * 60 + 30, 8 * 60, requiresConnection = false),

    /** Connected in the morning: data is clearly not the gap. 07:00–09:00. */
    MORNING_TALK(7 * 60, 9 * 60, requiresConnection = true),

    /** Offline in the evening. 17:00–19:00. */
    EVENING_DATA(17 * 60, 19 * 60, requiresConnection = false),

    /** Connected in the evening. 17:00–20:00. */
    EVENING_TALK(17 * 60, 20 * 60, requiresConnection = true);

    /** Which categories this slot advertises. Offline slots sell data; connected slots sell talk/text. */
    val categories: List<OfferCategory>
        get() = if (requiresConnection) {
            listOf(OfferCategory.MINUTES, OfferCategory.SMS)
        } else {
            listOf(OfferCategory.DATA)
        }
}

/** One ready-to-post notification: a title, a body and the tab a tap should open. */
data class EngagementMessage(
    val title: String,
    val body: String,
    val deepLinkRoute: String,
)

object EngagementSchedule {

    private val NAIROBI: TimeZone = TimeZone.getTimeZone("Africa/Nairobi")

    /** How far ahead [nextOccurrence] will look before giving up (days). */
    private const val SEARCH_DAYS = 2

    /**
     * Absolute Nairobi day number. Only equality and ordering matter, not the value.
     * Shared with the catalogue logic's notion of "today" (both use Africa/Nairobi, so
     * a slot and a purchase agree on which day they belong to).
     */
    fun nairobiDayIndex(millis: Long): Long {
        val cal = Calendar.getInstance(NAIROBI)
        cal.timeInMillis = millis
        return cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Epoch millis of Nairobi midnight starting the day that contains [millis].
     * Seconds and milliseconds are cleared so slot times land on an exact minute.
     */
    private fun startOfNairobiDay(millis: Long): Long {
        val cal = Calendar.getInstance(NAIROBI)
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * The minute inside [slot]'s window this slot fires on the given day.
     *
     * Deterministic: the same day and slot always produce the same minute, so a
     * reschedule (reboot, app update, a worker that ran early) cannot shift the
     * notification or post a second one. Different days produce different minutes,
     * which is what makes it feel random rather than an alarm clock.
     */
    fun fireMinuteOfDay(slot: EngagementSlot, dayIndex: Long): Int {
        val span = slot.endMinuteOfDay - slot.startMinuteOfDay
        if (span <= 0) return slot.startMinuteOfDay
        // A cheap, well-mixed hash of (day, slot). `and 0x7FFFFFFF` keeps it positive
        // so the modulo cannot return a negative offset.
        val mixed = ((dayIndex * 31L + slot.ordinal) * 2654435761L) and 0x7FFFFFFFL
        return slot.startMinuteOfDay + (mixed % span).toInt()
    }

    /** Epoch millis at which [slot] fires on the Nairobi day containing [dayMillis]. */
    fun fireTimeOn(slot: EngagementSlot, dayMillis: Long): Long =
        startOfNairobiDay(dayMillis) + fireMinuteOfDay(slot, nairobiDayIndex(dayMillis)) * 60_000L

    /**
     * The next slot due strictly after [nowMillis], and when it fires.
     *
     * Scans today and the following days in order, so a call made at 21:00 correctly
     * returns tomorrow's first morning slot. Returns null only if no slot could be
     * found within [SEARCH_DAYS], which cannot happen with the windows above but keeps
     * the caller total rather than throwing.
     */
    fun nextOccurrence(nowMillis: Long): Pair<EngagementSlot, Long>? {
        var best: Pair<EngagementSlot, Long>? = null
        for (dayOffset in 0..SEARCH_DAYS) {
            val dayMillis = nowMillis + dayOffset * 24L * 60L * 60L * 1000L
            for (slot in EngagementSlot.entries) {
                val at = fireTimeOn(slot, dayMillis)
                if (at <= nowMillis) continue
                if (best == null || at < best!!.second) best = slot to at
            }
            // Once this day yielded something, no later day can beat it.
            if (best != null) return best
        }
        return best
    }

    /**
     * A stable key identifying "this slot, on this Nairobi day". Used both as the
     * notification's de-duplication id and as the persisted marker of what has already
     * been posted, so a slot can never fire twice in one day even across process death.
     */
    fun slotKey(slot: EngagementSlot, nowMillis: Long): String =
        "engage_${slot.name}_${nairobiDayIndex(nowMillis)}"

    /**
     * Pick the message for [slot] on the day containing [nowMillis].
     *
     * When [offers] is provided, uses the live/cached catalogue to dynamically
     * substitute current real prices and allowances into the message copy.
     * Otherwise rotates through verified default variations.
     */
    fun messageFor(
        slot: EngagementSlot,
        nowMillis: Long,
        offers: List<com.example.core.model.OfferItem> = emptyList()
    ): EngagementMessage {
        val dynamic = buildDynamicMessage(slot, nowMillis, offers)
        if (dynamic != null) return dynamic

        val variations = VARIATIONS.getValue(slot)
        val index = (nairobiDayIndex(nowMillis) % variations.size).toInt()
        return variations[index]
    }

    private fun buildDynamicMessage(
        slot: EngagementSlot,
        nowMillis: Long,
        offers: List<com.example.core.model.OfferItem>
    ): EngagementMessage? {
        if (offers.isEmpty()) return null
        val dayIdx = nairobiDayIndex(nowMillis)

        return when (slot) {
            EngagementSlot.MORNING_DATA -> {
                val dataOffers = offers.filter { it.category == com.example.core.model.OfferCategory.DATA }
                    .sortedBy { it.priceKsh }
                if (dataOffers.isEmpty()) return null
                val cheapest = dataOffers.first()
                val popular = dataOffers.firstOrNull { it.isPopular } ?: dataOffers.getOrNull(1) ?: cheapest
                val alt = dataOffers.getOrNull(1) ?: cheapest
                val variant = (dayIdx % 3).toInt()
                when (variant) {
                    0 -> EngagementMessage(
                        title = "No bundle this morning?",
                        body = "Data starts at KSh ${cheapest.priceKsh} here. One tap and you are back online.",
                        deepLinkRoute = "offers"
                    )
                    1 -> EngagementMessage(
                        title = "Morning. Still offline?",
                        body = "${cheapest.allowance} for KSh ${cheapest.priceKsh}, ${alt.allowance} for KSh ${alt.priceKsh}. Buy with Till, no internet needed.",
                        deepLinkRoute = "offers"
                    )
                    else -> EngagementMessage(
                        title = "The day is up, your data is not",
                        body = "Bundles from KSh ${cheapest.priceKsh} (${popular.allowance} for KSh ${popular.priceKsh}). Pay by M-Pesa even while offline.",
                        deepLinkRoute = "offers"
                    )
                }
            }
            EngagementSlot.MORNING_TALK -> {
                val minOffers = offers.filter { it.category == com.example.core.model.OfferCategory.MINUTES }
                    .sortedBy { it.priceKsh }
                val smsOffers = offers.filter { it.category == com.example.core.model.OfferCategory.SMS }
                    .sortedBy { it.priceKsh }
                val min = minOffers.firstOrNull()
                val sms = smsOffers.firstOrNull()
                if (min == null && sms == null) return null
                val minPrice = min?.priceKsh ?: 22
                val smsPrice = sms?.priceKsh ?: 5
                val minName = min?.allowance ?: "20 Min"
                val smsName = sms?.allowance ?: "200 SMS"
                val variant = (dayIdx % 3).toInt()
                when (variant) {
                    0 -> EngagementMessage(
                        title = "Data sorted. Minutes?",
                        body = "Talk from KSh $minPrice and SMS from KSh $smsPrice, whenever you want them.",
                        deepLinkRoute = "offers"
                    )
                    1 -> EngagementMessage(
                        title = "More than data in here",
                        body = "Minutes from KSh $minPrice and SMS bundles from KSh $smsPrice are one tap away.",
                        deepLinkRoute = "offers"
                    )
                    else -> EngagementMessage(
                        title = "Someone is waiting for that call",
                        body = "$minName for KSh $minPrice, $smsName for KSh $smsPrice. Your move.",
                        deepLinkRoute = "offers"
                    )
                }
            }
            EngagementSlot.EVENING_DATA -> {
                val dataOffers = offers.filter { it.category == com.example.core.model.OfferCategory.DATA }
                    .sortedBy { it.priceKsh }
                if (dataOffers.isEmpty()) return null
                val cheapest = dataOffers.first()
                val mid = dataOffers.firstOrNull { it.priceKsh in 50..110 } ?: dataOffers.last()
                val variant = (dayIdx % 3).toInt()
                when (variant) {
                    0 -> EngagementMessage(
                        title = "Evening without data?",
                        body = "${mid.allowance} for KSh ${mid.priceKsh}. Buy it offline with the Till.",
                        deepLinkRoute = "offers"
                    )
                    1 -> EngagementMessage(
                        title = "Long evening ahead",
                        body = "Bundles from KSh ${cheapest.priceKsh}. Grab one and get back online.",
                        deepLinkRoute = "offers"
                    )
                    else -> EngagementMessage(
                        title = "Still offline this evening?",
                        body = "${mid.allowance} for KSh ${mid.priceKsh}. No internet needed to pay.",
                        deepLinkRoute = "offers"
                    )
                }
            }
            EngagementSlot.EVENING_TALK -> {
                val minOffers = offers.filter { it.category == com.example.core.model.OfferCategory.MINUTES }
                    .sortedBy { it.priceKsh }
                val smsOffers = offers.filter { it.category == com.example.core.model.OfferCategory.SMS }
                    .sortedBy { it.priceKsh }
                val min = minOffers.firstOrNull()
                val sms = smsOffers.firstOrNull()
                if (min == null && sms == null) return null
                val minPrice = min?.priceKsh ?: 22
                val smsPrice = sms?.priceKsh ?: 5
                val minName = min?.allowance ?: "20 Min"
                val smsName = sms?.allowance ?: "200 SMS"
                val variant = (dayIdx % 3).toInt()
                when (variant) {
                    0 -> EngagementMessage(
                        title = "Before the day ends",
                        body = "Minutes from KSh $minPrice, SMS from KSh $smsPrice. Whenever you need them.",
                        deepLinkRoute = "offers"
                    )
                    1 -> EngagementMessage(
                        title = "Talk is not free. It is KSh $minPrice",
                        body = "$minName for KSh $minPrice, or $smsName for KSh $smsPrice. Both are in the app.",
                        deepLinkRoute = "offers"
                    )
                    else -> EngagementMessage(
                        title = "Call home this evening",
                        body = "Minutes from KSh $minPrice and SMS bundles from KSh $smsPrice. Two taps, done.",
                        deepLinkRoute = "offers"
                    )
                }
            }
        }
    }

    private val VARIATIONS: Map<EngagementSlot, List<EngagementMessage>> = mapOf(
        EngagementSlot.MORNING_DATA to listOf(
            EngagementMessage(
                title = "No bundle this morning?",
                body = "Data starts at KSh 19 here. One tap and you are back online.",
                deepLinkRoute = "offers",
            ),
            EngagementMessage(
                title = "Morning. Still offline?",
                body = "1GB for KSh 19, 250MB for KSh 20. Buy with Till, no internet needed.",
                deepLinkRoute = "offers",
            ),
            EngagementMessage(
                title = "The day is up, your data is not",
                body = "Bundles from KSh 19. Pay by M-Pesa even while you are offline.",
                deepLinkRoute = "offers",
            ),
        ),
        EngagementSlot.MORNING_TALK to listOf(
            EngagementMessage(
                title = "Data sorted. Minutes?",
                body = "Talk from KSh 22 and SMS from KSh 5, whenever you want them.",
                deepLinkRoute = "offers",
            ),
            EngagementMessage(
                title = "More than data in here",
                body = "Minutes and SMS bundles are one tap away. From KSh 5.",
                deepLinkRoute = "offers",
            ),
            EngagementMessage(
                title = "Someone is waiting for that call",
                body = "20 minutes for KSh 22, 200 SMS for KSh 10. Your move.",
                deepLinkRoute = "offers",
            ),
        ),
        EngagementSlot.EVENING_DATA to listOf(
            EngagementMessage(
                title = "Evening without data?",
                body = "1.5GB for KSh 50. Buy it offline with the Till.",
                deepLinkRoute = "offers",
            ),
            EngagementMessage(
                title = "Long evening ahead",
                body = "Bundles from KSh 19. Grab one and get back online.",
                deepLinkRoute = "offers",
            ),
            EngagementMessage(
                title = "Still offline this evening?",
                body = "2GB for KSh 110, valid 24 hours. No internet needed to pay.",
                deepLinkRoute = "offers",
            ),
        ),
        EngagementSlot.EVENING_TALK to listOf(
            EngagementMessage(
                title = "Before the day ends",
                body = "Minutes from KSh 22, SMS from KSh 5. Whenever you need them.",
                deepLinkRoute = "offers",
            ),
            EngagementMessage(
                title = "Talk is not free. It is KSh 22",
                body = "20 minutes, or 200 SMS for KSh 10. Both are in the app.",
                deepLinkRoute = "offers",
            ),
            EngagementMessage(
                title = "Call home this evening",
                body = "Minutes and SMS bundles from KSh 5. Two taps, done.",
                deepLinkRoute = "offers",
            ),
        ),
    )
}
