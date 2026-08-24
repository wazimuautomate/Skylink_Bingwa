package com.example.core.notifications.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Time-of-day bands and greetings.
 *
 * All boundaries are asserted in Africa/Nairobi, the owner's business day
 * (CLAUDE.md §8). Nairobi has no daylight saving, so a fixed date is safe.
 */
class TimeOfDayResolverTest {

    @Test
    fun `morning band covers 05 to 11 inclusive`() {
        assertEquals(TimeOfDay.MORNING, TimeOfDayResolver.resolve(nairobi(hour = 5)))
        assertEquals(TimeOfDay.MORNING, TimeOfDayResolver.resolve(nairobi(hour = 8)))
        assertEquals(TimeOfDay.MORNING, TimeOfDayResolver.resolve(nairobi(hour = 11, minute = 59)))
    }

    @Test
    fun `afternoon band covers 12 to 16 inclusive`() {
        assertEquals(TimeOfDay.AFTERNOON, TimeOfDayResolver.resolve(nairobi(hour = 12)))
        assertEquals(TimeOfDay.AFTERNOON, TimeOfDayResolver.resolve(nairobi(hour = 16, minute = 59)))
    }

    @Test
    fun `evening band covers 17 to 21 inclusive`() {
        assertEquals(TimeOfDay.EVENING, TimeOfDayResolver.resolve(nairobi(hour = 17)))
        assertEquals(TimeOfDay.EVENING, TimeOfDayResolver.resolve(nairobi(hour = 21, minute = 59)))
    }

    @Test
    fun `late night band wraps 22 to 04`() {
        assertEquals(TimeOfDay.LATE_NIGHT, TimeOfDayResolver.resolve(nairobi(hour = 22)))
        assertEquals(TimeOfDay.LATE_NIGHT, TimeOfDayResolver.resolve(nairobi(hour = 0)))
        assertEquals(TimeOfDay.LATE_NIGHT, TimeOfDayResolver.resolve(nairobi(hour = 4, minute = 59)))
    }

    @Test
    fun `greetings name the customer`() {
        assertEquals("Good morning James", TimeOfDayResolver.greeting(TimeOfDay.MORNING, "James"))
        assertEquals("Good afternoon James", TimeOfDayResolver.greeting(TimeOfDay.AFTERNOON, "James"))
        assertEquals("Good evening James", TimeOfDayResolver.greeting(TimeOfDay.EVENING, "James"))
        assertEquals("Still awake James?", TimeOfDayResolver.greeting(TimeOfDay.LATE_NIGHT, "James"))
    }

    @Test
    fun `greetings read naturally without a name`() {
        for (band in TimeOfDay.values()) {
            val greeting = TimeOfDayResolver.greeting(band, "   ")
            assertTrue("blank-name greeting must not trail: '$greeting'", greeting == greeting.trim())
            assertTrue("blank-name greeting must not double-space: '$greeting'", !greeting.contains("  "))
            assertTrue("blank-name greeting must not be empty", greeting.isNotEmpty())
        }
        assertEquals("Good morning", TimeOfDayResolver.greeting(TimeOfDay.MORNING, ""))
        assertEquals("Still awake?", TimeOfDayResolver.greeting(TimeOfDay.LATE_NIGHT, ""))
    }

    @Test
    fun `day key uses the Nairobi calendar day`() {
        // 00:30 Nairobi on 16 March is still the 16th, even though it is the
        // 15th in UTC.
        assertEquals("2026-03-16", TimeOfDayResolver.dayKey(nairobi(2026, 3, 16, 0, 30)))
        assertEquals("2026-03-15", TimeOfDayResolver.dayKey(nairobi(2026, 3, 15, 23, 30)))
    }

    @Test
    fun `category matches the band`() {
        assertEquals(NotificationCategory.MORNING, TimeOfDayResolver.categoryFor(nairobi(hour = 7)))
        assertEquals(NotificationCategory.AFTERNOON, TimeOfDayResolver.categoryFor(nairobi(hour = 13)))
        assertEquals(NotificationCategory.EVENING, TimeOfDayResolver.categoryFor(nairobi(hour = 19)))
        assertEquals(NotificationCategory.LATE_NIGHT, TimeOfDayResolver.categoryFor(nairobi(hour = 23)))
    }

    companion object {
        /** Epoch millis for a wall-clock moment in Africa/Nairobi. */
        fun nairobi(
            year: Int = 2026,
            month: Int = 3,
            day: Int = 16,
            hour: Int = 10,
            minute: Int = 0
        ): Long {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("Africa/Nairobi"), Locale.US)
            calendar.clear()
            calendar.set(year, month - 1, day, hour, minute, 0)
            return calendar.timeInMillis
        }
    }
}
