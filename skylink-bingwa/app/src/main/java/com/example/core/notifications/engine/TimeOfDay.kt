package com.example.core.notifications.engine

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Time-of-day bands used to personalise notification copy and to key the
 * per-Nairobi-day counters the policy enforces.
 *
 * Pure Kotlin + java.util only (minSdk 24 has no java.time and this module has
 * no core-library desugaring), so every band boundary is unit-testable without
 * Android.
 */
enum class TimeOfDay { MORNING, AFTERNOON, EVENING, LATE_NIGHT }

object TimeOfDayResolver {

    /** All customer-facing day logic uses the owner's business day (CLAUDE.md §8). */
    const val ZONE_ID = "Africa/Nairobi"

    /** MORNING 05:00–11:59, AFTERNOON 12:00–16:59, EVENING 17:00–21:59, LATE_NIGHT 22:00–04:59. */
    fun resolve(nowMillis: Long): TimeOfDay = when (hourOfDay(nowMillis)) {
        in 5..11 -> TimeOfDay.MORNING
        in 12..16 -> TimeOfDay.AFTERNOON
        in 17..21 -> TimeOfDay.EVENING
        else -> TimeOfDay.LATE_NIGHT
    }

    /** The Nairobi hour (0–23) at [nowMillis]. */
    fun hourOfDay(nowMillis: Long): Int = calendar(nowMillis).get(Calendar.HOUR_OF_DAY)

    /** "yyyy-MM-dd" in Africa/Nairobi — the key for per-day counters. */
    fun dayKey(nowMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.timeZone = TimeZone.getTimeZone(ZONE_ID)
        return format.format(Date(nowMillis))
    }

    /**
     * A warm, human greeting. When [name] is blank the greeting still reads
     * naturally — it simply drops the name rather than leaving an empty gap.
     */
    fun greeting(t: TimeOfDay, name: String): String {
        val who = name.trim()
        return when (t) {
            TimeOfDay.MORNING -> if (who.isEmpty()) "Good morning" else "Good morning $who"
            TimeOfDay.AFTERNOON -> if (who.isEmpty()) "Good afternoon" else "Good afternoon $who"
            TimeOfDay.EVENING -> if (who.isEmpty()) "Good evening" else "Good evening $who"
            TimeOfDay.LATE_NIGHT -> if (who.isEmpty()) "Still awake?" else "Still awake $who?"
        }
    }

    /** The greeting category matching [nowMillis], useful for scheduled nudges. */
    fun categoryFor(nowMillis: Long): NotificationCategory = when (resolve(nowMillis)) {
        TimeOfDay.MORNING -> NotificationCategory.MORNING
        TimeOfDay.AFTERNOON -> NotificationCategory.AFTERNOON
        TimeOfDay.EVENING -> NotificationCategory.EVENING
        TimeOfDay.LATE_NIGHT -> NotificationCategory.LATE_NIGHT
    }

    private fun calendar(nowMillis: Long): Calendar {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(ZONE_ID), Locale.US)
        calendar.timeInMillis = nowMillis
        return calendar
    }
}
