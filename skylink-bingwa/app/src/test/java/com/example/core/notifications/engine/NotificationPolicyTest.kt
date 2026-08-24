package com.example.core.notifications.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spam guard. Cooldowns, quiet hours, the daily cap and content de-duplication
 * are the difference between a helpful assistant and an app the customer mutes
 * (CLAUDE.md §9).
 */
class NotificationPolicyTest {

    /**
     * 08:00 Africa/Nairobi. Chosen so that adding ANY cooldown (up to 12h) still
     * lands outside quiet hours, and so 24h/48h land on the same morning hour of a
     * later day — only the rule under test can bite.
     */
    private val eightAm = TimeOfDayResolverTest.nairobi(hour = 8)

    // ----- cooldowns ------------------------------------------------------

    @Test
    fun `every non-transactional category blocks a too-early repeat and allows a later one`() {
        for (category in NotificationCategory.values()) {
            if (category.transactional) continue
            val cooldown = NotificationPolicy.cooldownMillis(category)
            assertTrue("${category.name} must define a cooldown", cooldown > 0L)

            val posted = NotificationPolicy.record(category, "t1", eightAm, NotificationState(), "hash-${category.name}")

            val tooEarly = NotificationPolicy.shouldPost(category, eightAm + cooldown - 1L, posted, null)
            assertFalse("${category.name} allowed a repeat before its cooldown", tooEarly.allowed)
            assertEquals(NotificationPolicy.REASON_COOLDOWN, tooEarly.reason)

            val later = NotificationPolicy.shouldPost(category, eightAm + cooldown, posted, null)
            assertTrue("${category.name} still blocked after its cooldown", later.allowed)
            assertEquals(NotificationPolicy.REASON_OK, later.reason)
        }
    }

    @Test
    fun `the documented cooldown values are the ones in force`() {
        val hour = 3_600_000L
        assertEquals(6 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.OFFLINE))
        assertEquals(6 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.ONLINE))
        assertEquals(24 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.MORNING))
        assertEquals(24 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.AFTERNOON))
        assertEquals(24 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.EVENING))
        assertEquals(24 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.LATE_NIGHT))
        assertEquals(4 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.LOW_DATA))
        assertEquals(4 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.LOW_SMS))
        assertEquals(4 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.LOW_MINUTES))
        assertEquals(3 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.VERY_LOW_DATA))
        assertEquals(3 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.NO_DATA))
        assertEquals(24 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.PROMOTION))
        assertEquals(12 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.GENERAL))
        assertEquals(24 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.HABIT_REMINDER))
        assertEquals(48 * hour, NotificationPolicy.cooldownMillis(NotificationCategory.INACTIVITY))
        assertEquals(0L, NotificationPolicy.cooldownMillis(NotificationCategory.PURCHASE_SUCCESS))
        assertEquals(0L, NotificationPolicy.cooldownMillis(NotificationCategory.BUNDLE_RECEIVED))
        assertEquals(0L, NotificationPolicy.cooldownMillis(NotificationCategory.GIFT_RECEIVED))
    }

    @Test
    fun `a low-balance nudge does not fire on every carrier SMS`() {
        val state = NotificationPolicy.record(
            NotificationCategory.LOW_DATA, "low_data_1", eightAm, NotificationState(), "h1"
        )
        // A second balance SMS ten minutes later must not produce a second nudge.
        val tenMinutesLater = NotificationPolicy.shouldPost(
            NotificationCategory.LOW_DATA, eightAm + 600_000L, state, "h2"
        )
        assertFalse(tenMinutesLater.allowed)
        assertEquals(NotificationPolicy.REASON_COOLDOWN, tenMinutesLater.reason)
    }

    @Test
    fun `transactional categories have no time cooldown`() {
        val state = NotificationPolicy.record(
            NotificationCategory.PURCHASE_SUCCESS, "purchase_success_1", eightAm, NotificationState(), "order-1"
        )
        val secondOrder = NotificationPolicy.shouldPost(
            NotificationCategory.PURCHASE_SUCCESS, eightAm + 1_000L, state, "order-2"
        )
        assertTrue(secondOrder.allowed)
    }

    // ----- quiet hours ----------------------------------------------------

    @Test
    fun `quiet hours suppress optional chatter`() {
        for (hour in listOf(22, 23, 0, 3, 6)) {
            val now = TimeOfDayResolverTest.nairobi(hour = hour)
            assertTrue("hour $hour should be quiet", NotificationPolicy.isQuietHour(now))
            val decision = NotificationPolicy.shouldPost(
                NotificationCategory.PROMOTION, now, NotificationState(), null
            )
            assertFalse("PROMOTION posted at $hour:00", decision.allowed)
            assertEquals(NotificationPolicy.REASON_QUIET_HOURS, decision.reason)
        }
    }

    @Test
    fun `quiet hours never block a transactional update`() {
        val midnight = TimeOfDayResolverTest.nairobi(hour = 0, minute = 30)
        for (category in NotificationCategory.values()) {
            if (!category.transactional) continue
            val decision = NotificationPolicy.shouldPost(category, midnight, NotificationState(), null)
            assertTrue("${category.name} blocked at midnight", decision.allowed)
            assertEquals(NotificationPolicy.REASON_OK, decision.reason)
        }
    }

    @Test
    fun `07 00 and 21 00 are not quiet`() {
        assertFalse(NotificationPolicy.isQuietHour(TimeOfDayResolverTest.nairobi(hour = 7)))
        assertFalse(NotificationPolicy.isQuietHour(TimeOfDayResolverTest.nairobi(hour = 21, minute = 59)))
    }

    // ----- daily cap ------------------------------------------------------

    @Test
    fun `at most six optional notifications per Nairobi day`() {
        val categories = listOf(
            NotificationCategory.OFFLINE,
            NotificationCategory.ONLINE,
            NotificationCategory.MORNING,
            NotificationCategory.AFTERNOON,
            NotificationCategory.EVENING,
            NotificationCategory.PROMOTION,
            NotificationCategory.GENERAL
        )
        var state = NotificationState()
        for (index in 0 until NotificationPolicy.MAX_NON_TRANSACTIONAL_PER_DAY) {
            val category = categories[index]
            val decision = NotificationPolicy.shouldPost(category, eightAm, state, null)
            assertTrue("post $index blocked: ${decision.reason}", decision.allowed)
            state = NotificationPolicy.record(category, "t$index", eightAm, state, "hash-$index")
        }

        val seventh = NotificationPolicy.shouldPost(categories[6], eightAm, state, null)
        assertFalse("the seventh optional notification should be capped", seventh.allowed)
        assertEquals(NotificationPolicy.REASON_DAILY_CAP, seventh.reason)

        // A transactional update still gets through.
        assertTrue(
            NotificationPolicy.shouldPost(
                NotificationCategory.PURCHASE_SUCCESS, eightAm, state, "unique"
            ).allowed
        )
    }

    @Test
    fun `the daily cap resets on the next Nairobi day`() {
        var state = NotificationState()
        val categories = NotificationCategory.values().filter { !it.transactional }
        for (index in 0 until NotificationPolicy.MAX_NON_TRANSACTIONAL_PER_DAY) {
            state = NotificationPolicy.record(categories[index], "t$index", eightAm, state, "hash-$index")
        }
        assertFalse(NotificationPolicy.shouldPost(NotificationCategory.PROMOTION, eightAm, state, null).allowed)

        val nextDay = TimeOfDayResolverTest.nairobi(day = 17, hour = 10)
        assertTrue(NotificationPolicy.shouldPost(NotificationCategory.PROMOTION, nextDay, state, null).allowed)
    }

    @Test
    fun `transactional posts do not consume the optional daily budget`() {
        var state = NotificationState()
        for (index in 0 until 20) {
            state = NotificationPolicy.record(
                NotificationCategory.PURCHASE_SUCCESS, "t$index", eightAm, state, "order-$index"
            )
        }
        assertTrue(NotificationPolicy.shouldPost(NotificationCategory.PROMOTION, eightAm, state, null).allowed)
    }

    // ----- de-duplication -------------------------------------------------

    @Test
    fun `identical content is never posted twice`() {
        val state = NotificationPolicy.record(
            NotificationCategory.BUNDLE_RECEIVED, "bundle_received_1", eightAm, NotificationState(), "same-sms"
        )
        val repeat = NotificationPolicy.shouldPost(
            NotificationCategory.BUNDLE_RECEIVED, eightAm + 5_000L, state, "same-sms"
        )
        assertFalse("a re-delivered SMS must not notify twice", repeat.allowed)
        assertEquals(NotificationPolicy.REASON_DUPLICATE, repeat.reason)
    }

    @Test
    fun `the duplicate window is bounded`() {
        var state = NotificationState()
        for (index in 0 until NotificationPolicy.RECENT_HASH_CAP + 10) {
            state = NotificationPolicy.record(
                NotificationCategory.PURCHASE_SUCCESS, "t", eightAm, state, "hash-$index"
            )
        }
        assertEquals(NotificationPolicy.RECENT_HASH_CAP, state.recentContentHashes.size)
        // The newest is retained, the oldest has aged out.
        assertTrue(state.recentContentHashes.contains("hash-${NotificationPolicy.RECENT_HASH_CAP + 9}"))
        assertFalse(state.recentContentHashes.contains("hash-0"))
    }

    @Test
    fun `a null content hash skips the duplicate check`() {
        val state = NotificationPolicy.record(
            NotificationCategory.PROMOTION, "promotion_1", eightAm, NotificationState(), "h"
        )
        val later = NotificationPolicy.shouldPost(
            NotificationCategory.PROMOTION,
            eightAm + NotificationPolicy.cooldownMillis(NotificationCategory.PROMOTION),
            state,
            null
        )
        assertTrue(later.allowed)
    }

    // ----- recorded state -------------------------------------------------

    @Test
    fun `record stores the template id so wording can rotate`() {
        val state = NotificationPolicy.record(
            NotificationCategory.MORNING, "morning_2", eightAm, NotificationState(), "h"
        )
        assertEquals("morning_2", state.lastTemplateIdByCategory[NotificationCategory.MORNING.name])
        assertEquals(eightAm, state.lastPostedAtByCategory[NotificationCategory.MORNING.name] ?: 0L)
    }

    @Test
    fun `day counters stay bounded`() {
        var state = NotificationState()
        for (day in 1..20) {
            val now = TimeOfDayResolverTest.nairobi(month = 3, day = day, hour = 10)
            state = NotificationPolicy.record(NotificationCategory.PROMOTION, "t", now, state, "hash-$day")
        }
        assertTrue(
            "day counters grew to ${state.postedCountByDay.size}",
            state.postedCountByDay.size <= NotificationPolicy.DAY_COUNTER_CAP
        )
        assertTrue(state.postedCountByDay.containsKey("2026-03-20"))
    }

    @Test
    fun `the empty state allows a first post`() {
        val decision = NotificationPolicy.shouldPost(
            NotificationCategory.MORNING, eightAm, NotificationState(), "brand-new"
        )
        assertTrue(decision.allowed)
        assertEquals(NotificationPolicy.REASON_OK, decision.reason)
    }
}
