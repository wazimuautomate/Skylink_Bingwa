package com.example.core.personalization

/**
 * Africa/Nairobi calendar arithmetic for the personalization engine.
 *
 * minSdk 24 has no `java.time` (no desugaring in this project), so every
 * calculation here is plain integer arithmetic over a fixed UTC+03:00 offset.
 * Nairobi has observed UTC+03:00 with **no daylight saving** since 1960, so a
 * constant offset is exact for every timestamp this app can produce, and it
 * keeps the whole engine deterministic and free of the platform timezone
 * database (which matters for stable unit tests on a hosted CI runner).
 *
 * This mirrors, in a different encoding, the `Calendar`-based day boundary used
 * by `com.example.feature.home.nairobiDayIndex`: both resolve a timestamp to the
 * same Nairobi calendar day, they just number days differently.
 */
internal object NairobiTime {

    const val HOUR_MILLIS = 60L * 60L * 1000L
    const val DAY_MILLIS = 24L * HOUR_MILLIS
    const val DAY_MILLIS_D = 86_400_000.0

    /** Africa/Nairobi is UTC+03:00 all year round. */
    const val OFFSET_MILLIS = 3L * HOUR_MILLIS

    /** Floor division that is correct for negative operands (no `Math.floorDiv` dependency). */
    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a xor b) < 0L && q * b != a) q - 1L else q
    }

    private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

    /** Monotonic Nairobi day number; consecutive calendar days differ by exactly 1. */
    fun epochDay(millis: Long): Long = floorDiv(millis + OFFSET_MILLIS, DAY_MILLIS)

    /** Nairobi hour of day, 0..23. */
    fun hourOfDay(millis: Long): Int =
        (floorMod(millis + OFFSET_MILLIS, DAY_MILLIS) / HOUR_MILLIS).toInt()

    /** Nairobi minutes since local midnight, 0..1439. */
    fun minuteOfDay(millis: Long): Int =
        (floorMod(millis + OFFSET_MILLIS, DAY_MILLIS) / 60_000L).toInt()

    /** Nairobi day of week: 0 = Sunday … 6 = Saturday (epoch day 0 was a Thursday). */
    fun dayOfWeek(millis: Long): Int = (floorMod(epochDay(millis) + 4L, 7L)).toInt()

    fun isWeekend(millis: Long): Boolean {
        val dow = dayOfWeek(millis)
        return dow == 0 || dow == 6
    }

    /** Whole Nairobi days between two instants (negative when [millis] is in the future). */
    fun daysBetween(millis: Long, nowMillis: Long): Long = epochDay(nowMillis) - epochDay(millis)

    /** Shortest distance between two hours on a 24-hour clock (23 and 0 are 1 apart). */
    fun hourDistance(a: Int, b: Int): Int {
        val raw = if (a > b) a - b else b - a
        return if (raw > 12) 24 - raw else raw
    }
}
