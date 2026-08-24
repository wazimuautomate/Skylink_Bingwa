package com.example.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The daily engagement schedule is the one piece of the app that speaks to a customer
 * unprompted, so its timing rules are pinned here: inside the advertised window, once
 * per slot per day, stable under rescheduling, and quiet at night.
 */
class EngagementScheduleTest {

    private val nairobi: TimeZone = TimeZone.getTimeZone("Africa/Nairobi")

    /** Epoch millis for a Nairobi wall-clock time. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(nairobi)
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun minuteOfDay(millis: Long): Int {
        val cal = Calendar.getInstance(nairobi)
        cal.timeInMillis = millis
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    @Test
    fun everySlotFiresInsideItsOwnWindow() {
        // Sweep a month so no single day's hash can accidentally pass for all of them.
        for (day in 1..31) {
            val dayMillis = at(2026, 8, day, 12, 0)
            for (slot in EngagementSlot.entries) {
                val minute = minuteOfDay(EngagementSchedule.fireTimeOn(slot, dayMillis))
                assertTrue(
                    "$slot on day $day fired at $minute, outside " +
                        "${slot.startMinuteOfDay}..${slot.endMinuteOfDay}",
                    minute >= slot.startMinuteOfDay && minute < slot.endMinuteOfDay,
                )
            }
        }
    }

    @Test
    fun fireTimeIsStableForAGivenDay() {
        // Same day asked twice — from different moments within it — must not move. This is
        // what lets the worker be rescheduled (reboot, app update) without the
        // notification sliding around or firing twice.
        val morning = at(2026, 8, 8, 3, 0)
        val evening = at(2026, 8, 8, 22, 0)
        for (slot in EngagementSlot.entries) {
            assertEquals(
                EngagementSchedule.fireTimeOn(slot, morning),
                EngagementSchedule.fireTimeOn(slot, evening),
            )
        }
    }

    @Test
    fun fireMinuteVariesAcrossDays() {
        // If every day fired at the same minute this would read as an alarm clock.
        val minutes = (1..14).map {
            EngagementSchedule.fireMinuteOfDay(EngagementSlot.MORNING_DATA, dayIndex = 2026_000L + it)
        }
        assertTrue("expected varied fire minutes, got $minutes", minutes.toSet().size > 1)
    }

    @Test
    fun nextOccurrenceIsAlwaysInTheFuture() {
        val probes = listOf(
            at(2026, 8, 8, 0, 1),
            at(2026, 8, 8, 7, 30),
            at(2026, 8, 8, 12, 0),
            at(2026, 8, 8, 18, 45),
            at(2026, 8, 8, 23, 59),
        )
        for (now in probes) {
            val next = EngagementSchedule.nextOccurrence(now)
            assertNotNull("no slot found from $now", next)
            assertTrue("slot at ${next!!.second} is not after $now", next.second > now)
        }
    }

    @Test
    fun lateNightRollsOverToTheNextMorning() {
        // 23:00 is past every window, so the next slot must be tomorrow's first one.
        val next = EngagementSchedule.nextOccurrence(at(2026, 8, 8, 23, 0))!!
        val minute = minuteOfDay(next.second)
        assertTrue("expected a morning slot, got $minute", minute < 12 * 60)
        assertEquals(
            EngagementSchedule.nairobiDayIndex(at(2026, 8, 9, 12, 0)),
            EngagementSchedule.nairobiDayIndex(next.second),
        )
    }

    @Test
    fun nothingIsScheduledDuringQuietHours() {
        // Nothing may land before 06:30 or after 20:00 — the app must never buzz at night.
        for (day in 1..31) {
            for (slot in EngagementSlot.entries) {
                val minute = minuteOfDay(EngagementSchedule.fireTimeOn(slot, at(2026, 8, day, 12, 0)))
                assertTrue("$slot fired at $minute on day $day", minute in (6 * 60 + 30)..(20 * 60))
            }
        }
    }

    @Test
    fun offlineSlotsSellDataAndConnectedSlotsSellTalk() {
        assertEquals(listOf(com.example.core.model.OfferCategory.DATA), EngagementSlot.MORNING_DATA.categories)
        assertEquals(listOf(com.example.core.model.OfferCategory.DATA), EngagementSlot.EVENING_DATA.categories)
        for (slot in listOf(EngagementSlot.MORNING_TALK, EngagementSlot.EVENING_TALK)) {
            assertEquals(
                listOf(
                    com.example.core.model.OfferCategory.MINUTES,
                    com.example.core.model.OfferCategory.SMS,
                ),
                slot.categories,
            )
        }
    }

    @Test
    fun slotKeyIsUniquePerSlotPerDay() {
        val today = at(2026, 8, 8, 7, 0)
        val tomorrow = at(2026, 8, 9, 7, 0)
        val keys = EngagementSlot.entries.map { EngagementSchedule.slotKey(it, today) }
        assertEquals("slot keys collided within one day", keys.size, keys.toSet().size)
        // The same slot on another day is a different key, so it can fire again tomorrow.
        keys.forEach { key ->
            assertTrue(EngagementSlot.entries.none { EngagementSchedule.slotKey(it, tomorrow) == key })
        }
    }

    @Test
    fun copyRotatesAndAvoidsForbiddenClaims() {
        // CLAUDE.md §8: never claim a balance is low, never recommend from usage.
        val banned = listOf(
            "running out", "you need more", "recommended for", "based on your", "we delivered",
        )
        val seen = mutableSetOf<String>()
        for (day in 1..7) {
            val now = at(2026, 8, day, 7, 0)
            for (slot in EngagementSlot.entries) {
                val message = EngagementSchedule.messageFor(slot, now)
                seen += message.title
                val text = (message.title + " " + message.body).lowercase()
                banned.forEach { phrase ->
                    assertTrue("\"$phrase\" appeared in: $text", !text.contains(phrase))
                }
                assertTrue(message.title.isNotBlank() && message.body.isNotBlank())
            }
        }
        // A customer seeing these every day should not read the same line all week.
        assertTrue("copy did not rotate across days", seen.size > EngagementSlot.entries.size)
    }

    @Test
    fun `dynamic offers are substituted into copy when provided`() {
        val testOffers = listOf(
            com.example.core.model.OfferItem(
                id = "d1",
                name = "500MB",
                allowance = "500MB",
                priceKsh = 35,
                validity = "24 Hrs",
                validityBand = "Daily",
                category = com.example.core.model.OfferCategory.DATA,
                dailyRule = com.example.core.model.DailyRule.ONCE_PER_DAY,
                isFavourite = false,
                description = "500MB"
            ),
            com.example.core.model.OfferItem(
                id = "m1",
                name = "15 Min",
                allowance = "15 Min",
                priceKsh = 18,
                validity = "Midnight",
                validityBand = "Daily",
                category = com.example.core.model.OfferCategory.MINUTES,
                dailyRule = com.example.core.model.DailyRule.BUY_AGAIN_TODAY,
                isFavourite = false,
                description = "15 Min"
            )
        )
        val morningData = EngagementSchedule.messageFor(EngagementSlot.MORNING_DATA, at(2026, 8, 1, 7, 0), testOffers)
        assertTrue("Expected 35 in copy, got: ${morningData.body}", morningData.body.contains("35"))

        val morningTalk = EngagementSchedule.messageFor(EngagementSlot.MORNING_TALK, at(2026, 8, 1, 7, 30), testOffers)
        assertTrue("Expected 18 in copy, got: ${morningTalk.body}", morningTalk.body.contains("18"))
    }
}
