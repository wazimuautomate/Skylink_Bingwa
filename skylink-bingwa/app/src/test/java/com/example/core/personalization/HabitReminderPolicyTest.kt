package com.example.core.personalization

import com.example.core.personalization.PersonalizationTestData.HOUR
import com.example.core.personalization.PersonalizationTestData.at
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [HabitReminderPolicy] must be hard to trigger: only a confident local habit,
 * only after the customer's usual hour has passed without a purchase, and never
 * more than once a day.
 */
class HabitReminderPolicyTest {

    private fun confident(
        usualHour: Int = 20,
        lastPurchase: Long = at(-1, 20),
        purchases: Int = 8,
        frequency: BuyerFrequency = BuyerFrequency.DAILY
    ) = BehaviourProfile(
        totalPurchases = purchases,
        usualPurchaseHour = usualHour,
        frequency = frequency,
        lastPurchaseAtMillis = lastPurchase
    )

    private fun evaluate(
        profile: BehaviourProfile,
        now: Long,
        lastNudge: Long = 0L
    ) = HabitReminderPolicy.evaluate(profile, now, lastNudge)

    // -----------------------------------------------------------------------
    // Never nudge without confidence
    // -----------------------------------------------------------------------

    @Test
    fun `an empty profile is never nudged`() {
        assertEquals(HabitNudge.NONE, evaluate(BehaviourProfile.EMPTY, at(0, 21)))
    }

    @Test
    fun `a brand new customer is never nudged`() {
        val newCustomer = confident(
            purchases = HabitReminderPolicy.MIN_PURCHASES_FOR_CONFIDENCE - 1,
            frequency = BuyerFrequency.NEW
        )
        assertEquals(HabitNudge.NONE, evaluate(newCustomer, at(0, 21)))
    }

    @Test
    fun `enough purchases but still classified NEW is not nudged`() {
        val unsure = confident(purchases = 9, frequency = BuyerFrequency.NEW)
        assertEquals(HabitNudge.NONE, evaluate(unsure, at(0, 21)))
    }

    @Test
    fun `a profile with no recorded last purchase is not nudged`() {
        assertEquals(HabitNudge.NONE, evaluate(confident(lastPurchase = 0L), at(0, 21)))
    }

    // -----------------------------------------------------------------------
    // Usual time of day
    // -----------------------------------------------------------------------

    @Test
    fun `their usual hour passing without a purchase today fires the nudge`() {
        assertEquals(HabitNudge.USUAL_TIME_PASSED, evaluate(confident(), at(0, 20, 16)))
    }

    @Test
    fun `the grace period is respected exactly`() {
        val grace = HabitReminderPolicy.USUAL_TIME_GRACE_MINUTES
        assertEquals(HabitNudge.USUAL_TIME_PASSED, evaluate(confident(), at(0, 20, grace)))
        assertEquals(HabitNudge.NONE, evaluate(confident(), at(0, 20, grace - 1)))
    }

    @Test
    fun `before their usual hour nothing is said`() {
        assertEquals(HabitNudge.NONE, evaluate(confident(), at(0, 19)))
    }

    @Test
    fun `a customer who already bought today is left alone`() {
        val boughtToday = confident(lastPurchase = at(0, 9))
        assertEquals(HabitNudge.NONE, evaluate(boughtToday, at(0, 21)))
    }

    @Test
    fun `an unknown usual hour cannot fire the time nudge`() {
        assertEquals(HabitNudge.NONE, evaluate(confident(usualHour = -1), at(0, 23)))
    }

    // -----------------------------------------------------------------------
    // Inactivity
    // -----------------------------------------------------------------------

    @Test
    fun `several days without a purchase outranks the time of day nudge`() {
        val inactive = confident(
            lastPurchase = at(-HabitReminderPolicy.INACTIVE_DAYS_THRESHOLD - 1, 20)
        )
        assertEquals(HabitNudge.INACTIVE_SEVERAL_DAYS, evaluate(inactive, at(0, 21)))
    }

    @Test
    fun `one day short of the inactivity threshold is not inactivity`() {
        val nearlyInactive = confident(
            lastPurchase = at(-HabitReminderPolicy.INACTIVE_DAYS_THRESHOLD + 1, 20)
        )
        assertEquals(HabitNudge.USUAL_TIME_PASSED, evaluate(nearlyInactive, at(0, 21)))
    }

    // -----------------------------------------------------------------------
    // Minimum gap between nudges
    // -----------------------------------------------------------------------

    @Test
    fun `a second nudge inside twenty four hours is suppressed`() {
        val now = at(0, 20, 30)
        assertEquals(HabitNudge.NONE, evaluate(confident(), now, lastNudge = now - 2L * HOUR))
        assertEquals(
            HabitNudge.NONE,
            evaluate(confident(), now, lastNudge = now - (HabitReminderPolicy.MIN_HOURS_BETWEEN_NUDGES - 1) * HOUR)
        )
    }

    @Test
    fun `once the gap has elapsed the nudge is allowed again`() {
        val now = at(0, 20, 30)
        assertEquals(
            HabitNudge.USUAL_TIME_PASSED,
            evaluate(confident(), now, lastNudge = now - (HabitReminderPolicy.MIN_HOURS_BETWEEN_NUDGES + 1) * HOUR)
        )
    }

    @Test
    fun `a never nudged customer is not blocked by the gap rule`() {
        assertEquals(HabitNudge.USUAL_TIME_PASSED, evaluate(confident(), at(0, 20, 30), lastNudge = 0L))
    }
}
