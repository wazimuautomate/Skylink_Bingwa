package com.example.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Time-of-day selling windows. Every case is expressed in Nairobi minutes so it
 * is stable wherever the test runs; the one clock-based test builds its instant
 * explicitly in Africa/Nairobi.
 */
class OfferAvailabilityTest {

    private fun offer(from: Int? = null, to: Int? = null) = OfferItem(
        id = "off_1",
        name = "1GB",
        allowance = "1GB",
        priceKsh = 50,
        validity = "1 Hr",
        category = OfferCategory.DATA,
        dailyRule = DailyRule.ONCE_PER_DAY,
        availableFromMinutes = from,
        availableToMinutes = to
    )

    // --- parsing -------------------------------------------------------------

    @Test
    fun `parses the server time formats`() {
        assertEquals(17 * 60, parseTimeOfDayMinutes("17:00"))
        assertEquals(17 * 60, parseTimeOfDayMinutes("17:00:00"))
        assertEquals(17 * 60 + 30, parseTimeOfDayMinutes("17:30"))
        assertEquals(17 * 60, parseTimeOfDayMinutes("5pm"))
        assertEquals(17 * 60 + 30, parseTimeOfDayMinutes("5:30 PM"))
        assertEquals(0, parseTimeOfDayMinutes("12am"))
        assertEquals(MINUTES_PER_DAY, parseTimeOfDayMinutes("24:00"))
    }

    @Test
    fun `unusable times parse to null so a bad row never blocks a sale`() {
        assertNull(parseTimeOfDayMinutes(null))
        assertNull(parseTimeOfDayMinutes(""))
        assertNull(parseTimeOfDayMinutes("   "))
        assertNull(parseTimeOfDayMinutes("later"))
        assertNull(parseTimeOfDayMinutes("99:00"))
        assertNull(parseTimeOfDayMinutes("17:88"))
    }

    @Test
    fun `formats times the way a customer reads them`() {
        assertEquals("5:00 PM", formatTimeOfDayMinutes(17 * 60))
        assertEquals("11:00 PM", formatTimeOfDayMinutes(23 * 60))
        assertEquals("12:00 AM", formatTimeOfDayMinutes(0))
        assertEquals("12:30 PM", formatTimeOfDayMinutes(12 * 60 + 30))
        assertEquals("12:00 AM", formatTimeOfDayMinutes(MINUTES_PER_DAY))
    }

    // --- window evaluation ---------------------------------------------------

    @Test
    fun `an offer with no window is always available`() {
        val state = availabilityFor(null, null, nowMinute = 3 * 60)
        assertEquals(AvailabilityKind.ALWAYS, state.kind)
        assertTrue(state.purchasable)
        assertFalse(state.restricted)
        assertEquals("Available any time", state.listLabel)
    }

    @Test
    fun `a full-day window is treated as no restriction`() {
        assertEquals(AvailabilityKind.ALWAYS, availabilityFor(0, MINUTES_PER_DAY, 60).kind)
        assertEquals(AvailabilityKind.ALWAYS, availabilityFor(0, 0, 60).kind)
        // Equal ends carry no information either way.
        assertEquals(AvailabilityKind.ALWAYS, availabilityFor(17 * 60, 17 * 60, 60).kind)
    }

    @Test
    fun `inside a 5pm to 11pm window the offer is open`() {
        val state = availabilityFor(17 * 60, 23 * 60, nowMinute = 18 * 60)
        assertEquals(AvailabilityKind.OPEN, state.kind)
        assertTrue(state.purchasable)
        assertTrue(state.restricted)
        assertEquals("5:00 PM – 11:00 PM", state.windowLabel)
        assertTrue(state.listLabel.contains("Available now"))
    }

    @Test
    fun `before the window opens the offer is closed and says when it opens`() {
        val state = availabilityFor(17 * 60, 23 * 60, nowMinute = 9 * 60)
        assertEquals(AvailabilityKind.CLOSED, state.kind)
        assertFalse(state.purchasable)
        assertEquals("Available from 5:00 PM to 11:00 PM", state.listLabel)
        assertEquals("Opens 5:00 PM", state.chipLabel)
        assertEquals(8 * 60, state.minutesUntilOpen)
    }

    @Test
    fun `after the window closes the offer is closed until tomorrow`() {
        val state = availabilityFor(17 * 60, 23 * 60, nowMinute = 23 * 60 + 30)
        assertEquals(AvailabilityKind.CLOSED, state.kind)
        // 23:30 → 17:00 the next day is 17 hours 30 minutes away.
        assertEquals(17 * 60 + 30, state.minutesUntilOpen)
    }

    @Test
    fun `the window boundaries are inclusive at the start and exclusive at the end`() {
        assertEquals(AvailabilityKind.OPEN, availabilityFor(17 * 60, 23 * 60, 17 * 60).kind)
        assertEquals(AvailabilityKind.CLOSED, availabilityFor(17 * 60, 23 * 60, 23 * 60).kind)
    }

    @Test
    fun `a window that crosses midnight is open on both sides of it`() {
        val late = availabilityFor(22 * 60, 2 * 60, nowMinute = 23 * 60)
        assertEquals(AvailabilityKind.OPEN, late.kind)

        val earlyMorning = availabilityFor(22 * 60, 2 * 60, nowMinute = 1 * 60)
        assertEquals(AvailabilityKind.OPEN, earlyMorning.kind)

        val afternoon = availabilityFor(22 * 60, 2 * 60, nowMinute = 15 * 60)
        assertEquals(AvailabilityKind.CLOSED, afternoon.kind)
        assertEquals(7 * 60, afternoon.minutesUntilOpen)
    }

    @Test
    fun `one open end runs to the day boundary`() {
        // "from 20:00" with no closing time sells until midnight.
        assertEquals(AvailabilityKind.OPEN, availabilityFor(20 * 60, null, 22 * 60).kind)
        assertEquals(AvailabilityKind.CLOSED, availabilityFor(20 * 60, null, 10 * 60).kind)
        // "until 09:00" with no opening time sells from midnight.
        assertEquals(AvailabilityKind.OPEN, availabilityFor(null, 9 * 60, 7 * 60).kind)
        assertEquals(AvailabilityKind.CLOSED, availabilityFor(null, 9 * 60, 11 * 60).kind)
    }

    @Test
    fun `a nonsense window from the server never makes an offer unbuyable`() {
        assertEquals(AvailabilityKind.ALWAYS, availabilityFor(-5, 9999, 12 * 60).kind)
    }

    // --- the offer-level entry point uses the Nairobi clock -------------------

    @Test
    fun `availability is evaluated on the Nairobi clock`() {
        // 18:30 in Nairobi, whatever timezone this test runs in.
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Africa/Nairobi"))
        cal.set(2026, Calendar.AUGUST, 8, 18, 30, 0)
        cal.set(Calendar.MILLISECOND, 0)

        assertEquals(18 * 60 + 30, nairobiMinuteOfDay(cal.timeInMillis))
        val open = offerAvailabilityAt(offer(from = 17 * 60, to = 23 * 60), cal.timeInMillis)
        assertEquals(AvailabilityKind.OPEN, open.kind)

        val closed = offerAvailabilityAt(offer(from = 5 * 60, to = 9 * 60), cal.timeInMillis)
        assertEquals(AvailabilityKind.CLOSED, closed.kind)
    }

    @Test
    fun `an offer with no window set reads as always available`() {
        assertEquals(AvailabilityKind.ALWAYS, offerAvailabilityAt(offer(), 0L).kind)
    }

    @Test
    fun `the countdown reads naturally`() {
        assertEquals("now", formatMinutesUntil(0))
        assertEquals("45 min", formatMinutesUntil(45))
        assertEquals("1 hr", formatMinutesUntil(60))
        assertEquals("2 hrs 15 min", formatMinutesUntil(135))
    }
}
