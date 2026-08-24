package com.example.data.payment

import com.example.core.model.DailyRule
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineEligibilityTest {

    private val now = 1_000L

    private fun offer(
        id: String,
        priceKsh: Int,
        rule: DailyRule = DailyRule.BUY_AGAIN_TODAY,
        offlineExpired: Boolean = false
    ) = OfferItem(
        id = id,
        name = "Offer $id",
        allowance = "1 GB",
        priceKsh = priceKsh,
        validity = "1 day",
        category = OfferCategory.DATA,
        dailyRule = rule,
        offlineInstructionsExpired = offlineExpired
    )

    private fun validConfig() = OfflineConfigResult.Valid(
        OfflinePaymentConfig(
            tillNumber = "4953696",
            paybillNumber = "40450595",
            issuedAtMillis = 0L,
            expiresAtMillis = Long.MAX_VALUE,
            signatureValid = true
        )
    )

    @Test
    fun eligible_self_usesTillRoute() {
        val o = offer("a", 20)
        val result = OfflineEligibilityChecker.check(o, isForSelf = true, catalogue = listOf(o), config = validConfig(), nowMillis = now)
        assertEquals(OfflineEligibility.Eligible(PaymentRoute.TILL_SELF), result)
        assertTrue(result.isEligible)
    }

    @Test
    fun eligible_another_usesPaybillRoute() {
        val o = offer("a", 20)
        val result = OfflineEligibilityChecker.check(o, isForSelf = false, catalogue = listOf(o), config = validConfig(), nowMillis = now)
        assertEquals(OfflineEligibility.Eligible(PaymentRoute.PAYBILL_OTHER), result)
    }

    @Test
    fun expiredConfig_blocks() {
        val o = offer("a", 20)
        val result = OfflineEligibilityChecker.check(o, true, listOf(o), OfflineConfigResult.Expired, now)
        assertEquals(OfflineEligibility.Expired, result)
    }

    @Test
    fun invalidSignature_isConfigUnavailable() {
        val o = offer("a", 20)
        val result = OfflineEligibilityChecker.check(o, true, listOf(o), OfflineConfigResult.InvalidSignature, now)
        assertEquals(OfflineEligibility.ConfigUnavailable, result)
    }

    @Test
    fun missingConfig_isConfigUnavailable() {
        val o = offer("a", 20)
        val result = OfflineEligibilityChecker.check(o, true, listOf(o), OfflineConfigResult.Missing, now)
        assertEquals(OfflineEligibility.ConfigUnavailable, result)
    }

    @Test
    fun perOfferOfflineExpiry_blocks() {
        val o = offer("a", 20, offlineExpired = true)
        val result = OfflineEligibilityChecker.check(o, true, listOf(o), validConfig(), now)
        assertEquals(OfflineEligibility.Expired, result)
    }

    @Test
    fun hardOncePerDayOffer_blockedOffline() {
        val o = offer("a", 20, rule = DailyRule.ONCE_PER_DAY)
        val result = OfflineEligibilityChecker.check(o, true, listOf(o), validConfig(), now)
        assertEquals(OfflineEligibility.HardLimitBlocked, result)
    }

    @Test
    fun sharedPriceAmongOfflinePayableOffers_isAmbiguous() {
        val a = offer("a", 20)
        val b = offer("b", 20) // both repeatable + same price
        val result = OfflineEligibilityChecker.check(a, true, listOf(a, b), validConfig(), now)
        assertEquals(OfflineEligibility.AmbiguousAmount, result)
    }

    @Test
    fun sharedPriceWithHardLimitedOffer_isNotAmbiguous() {
        val a = offer("a", 20)
        val b = offer("b", 20, rule = DailyRule.ONCE_PER_DAY) // hard-limited → not offline-payable
        val result = OfflineEligibilityChecker.check(a, true, listOf(a, b), validConfig(), now)
        assertEquals(OfflineEligibility.Eligible(PaymentRoute.TILL_SELF), result)
    }

    @Test
    fun uniquePrice_isEligible() {
        val a = offer("a", 20)
        val b = offer("b", 50)
        val result = OfflineEligibilityChecker.check(a, true, listOf(a, b), validConfig(), now)
        assertTrue(result.isEligible)
    }
}
