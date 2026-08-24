package com.example.core.model

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Time-of-day purchase windows (Safaricom restricts some offers to a slot, e.g.
 * "5pm to 11pm"). Everything here is pure and clock-injected so it is unit
 * testable, and it is presentation + guardrail only — the SERVER is the authority
 * that refuses an out-of-window purchase (`stk.php`).
 *
 * The window is stored on [OfferItem] as two minute-of-day values in
 * **Africa/Nairobi**, the same day boundary every other daily rule in this app
 * uses (CLAUDE.md §8), so a customer's "today" is their own local day.
 *
 * Semantics:
 *  - both ends absent (or a full 00:00–24:00 span) → the offer is always buyable;
 *  - `from < to` → a normal same-day window (17:00–23:00);
 *  - `from > to` → a window that crosses midnight (22:00–02:00);
 *  - one end absent → the other end runs to the day boundary.
 */

private val NAIROBI: TimeZone = TimeZone.getTimeZone("Africa/Nairobi")

const val MINUTES_PER_DAY: Int = 24 * 60

enum class AvailabilityKind {
    /** No restriction — buyable at any hour. */
    ALWAYS,

    /** Restricted, and the window is open right now. */
    OPEN,

    /** Restricted, and the window has not started (or has ended) for now. */
    CLOSED
}

/**
 * What to render for one offer at one instant.
 *
 * [listLabel] is the single line every offer list must show. [chipLabel] is the
 * short form that replaces the Buy button while the offer is closed.
 * [explanation] is the sentence shown when a closed offer is tapped.
 */
data class OfferAvailability(
    val kind: AvailabilityKind,
    val windowLabel: String,
    val listLabel: String,
    val chipLabel: String,
    val explanation: String,
    val minutesUntilOpen: Int = 0
) {
    /** True when the offer may be bought right now, as far as its window is concerned. */
    val purchasable: Boolean get() = kind != AvailabilityKind.CLOSED

    /** True when the offer carries a real restriction (so the window is worth naming). */
    val restricted: Boolean get() = kind != AvailabilityKind.ALWAYS
}

/** Minute of day (0..1439) in Africa/Nairobi for [millis]. */
fun nairobiMinuteOfDay(millis: Long): Int {
    val cal = Calendar.getInstance(NAIROBI)
    cal.timeInMillis = millis
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

/**
 * Parse a server time-of-day into minutes past midnight, or null when the value is
 * absent/unusable (which always means "no restriction from this end").
 *
 * Accepts "17:00", "17:00:00", "17", "5pm", "5:30 PM" — the admin panel writes
 * "HH:MM", the rest are tolerated so a hand-edited row can never crash a sync.
 * "24:00" is accepted as the end of the day.
 */
fun parseTimeOfDayMinutes(raw: String?): Int? {
    val text = raw?.trim()?.lowercase(Locale.US).orEmpty()
    if (text.isEmpty() || text == "null") return null

    val pm = text.endsWith("pm")
    val am = text.endsWith("am")
    val core = text.removeSuffix("pm").removeSuffix("am").trim()
    val parts = core.split(":", ".").map { it.trim() }
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
    if (minute !in 0..59) return null

    var h = hour
    if (pm && h in 1..11) h += 12
    if (am && h == 12) h = 0
    if (h == 24 && minute == 0) return MINUTES_PER_DAY
    if (h !in 0..23) return null
    return h * 60 + minute
}

/** "17:00" → "5:00 PM". [MINUTES_PER_DAY] renders as midnight. */
fun formatTimeOfDayMinutes(minutes: Int): String {
    val normalised = ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    val hour24 = normalised / 60
    val minute = normalised % 60
    val suffix = if (hour24 < 12) "AM" else "PM"
    val hour12 = when (hour24 % 12) {
        0 -> 12
        else -> hour24 % 12
    }
    return String.format(Locale.US, "%d:%02d %s", hour12, minute, suffix)
}

/** A short "in 2 hrs 15 min" for how long until a closed window opens. */
fun formatMinutesUntil(minutes: Int): String {
    if (minutes <= 0) return "now"
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    val hoursText = if (hours == 1) "1 hr" else "$hours hrs"
    return if (rest == 0) hoursText else "$hoursText $rest min"
}

/**
 * The availability of [offer] at [nowMillis]. Never throws: an offer with no
 * window, or with a window the server sent as nonsense, reads as [AvailabilityKind.ALWAYS]
 * so a bad row can never make a sellable offer un-buyable.
 */
fun offerAvailabilityAt(offer: OfferItem, nowMillis: Long): OfferAvailability =
    availabilityFor(offer.availableFromMinutes, offer.availableToMinutes, nairobiMinuteOfDay(nowMillis))

/** The window-only computation, taking the current Nairobi minute directly (test seam). */
fun availabilityFor(fromRaw: Int?, toRaw: Int?, nowMinute: Int): OfferAvailability {
    val from = fromRaw?.takeIf { it in 0..MINUTES_PER_DAY }
    val to = toRaw?.takeIf { it in 0..MINUTES_PER_DAY }

    // No usable restriction: absent ends, an explicit full day, or a zero-length window.
    val unrestricted = (from == null && to == null) ||
        (from == null && to == MINUTES_PER_DAY) ||
        (from == 0 && to == null) ||
        (from == 0 && to == MINUTES_PER_DAY) ||
        (from == 0 && to == 0) ||
        (from != null && to != null && from == to)
    if (unrestricted) {
        return OfferAvailability(
            kind = AvailabilityKind.ALWAYS,
            windowLabel = "Any time",
            listLabel = "Available any time",
            chipLabel = "",
            explanation = "This offer can be bought at any time of day."
        )
    }

    val start = from ?: 0
    val end = to ?: MINUTES_PER_DAY
    val startText = formatTimeOfDayMinutes(start)
    val endText = formatTimeOfDayMinutes(end)
    val windowLabel = "$startText – $endText"

    // A window that ends past midnight (22:00–02:00) is open on either side of it.
    val crossesMidnight = start > end
    val open = if (crossesMidnight) nowMinute >= start || nowMinute < end else nowMinute in start until end

    if (open) {
        return OfferAvailability(
            kind = AvailabilityKind.OPEN,
            windowLabel = windowLabel,
            listLabel = "Available now · $windowLabel",
            chipLabel = "",
            explanation = "Safaricom sells this offer between $startText and $endText only. " +
                "It is open now and closes at $endText."
        )
    }

    val untilOpen = ((start - nowMinute) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return OfferAvailability(
        kind = AvailabilityKind.CLOSED,
        windowLabel = windowLabel,
        listLabel = "Available from $startText to $endText",
        chipLabel = "Opens $startText",
        explanation = "Safaricom sells this offer between $startText and $endText only. " +
            "It opens in ${formatMinutesUntil(untilOpen)} — buying it now would not go through.",
        minutesUntilOpen = untilOpen
    )
}
