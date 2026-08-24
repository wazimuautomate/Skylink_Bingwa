package com.example.core.personalization

import com.example.core.model.OfferCategory
import com.example.core.model.PaymentStatus
import com.example.core.payment.KenyanPhone
import com.example.core.personalization.PersonalizationTestData.NOW
import com.example.core.personalization.PersonalizationTestData.at
import com.example.core.personalization.PersonalizationTestData.offer
import com.example.core.personalization.PersonalizationTestData.purchase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for [PersonalizationEngine]. No Android, no clock, no
 * network — the engine is handed a fixed `nowMillis` and a fixed history.
 */
class PersonalizationEngineTest {

    private val catalogue = listOf(
        offer("d20", price = 20, category = OfferCategory.DATA, band = PersonalizationEngine.BAND_HOURLY),
        offer("d50", price = 50, category = OfferCategory.DATA, band = PersonalizationEngine.BAND_DAILY),
        offer("s10", price = 10, category = OfferCategory.SMS, band = PersonalizationEngine.BAND_DAILY)
    )

    private fun build(
        purchases: List<com.example.core.model.PurchaseRecord>,
        favouriteIds: Set<String> = emptySet(),
        recentRecipients: List<String> = emptyList(),
        nowMillis: Long = NOW,
        offers: List<com.example.core.model.OfferItem> = catalogue
    ) = PersonalizationEngine.buildProfile(purchases, favouriteIds, recentRecipients, nowMillis, offers)

    // -----------------------------------------------------------------------
    // Shape of a realistic profile
    // -----------------------------------------------------------------------

    @Test
    fun `a realistic history yields most-purchased offer amount category and validity`() {
        val history = listOf(
            purchase("d20", at(-1, 20), price = 20),
            purchase("d20", at(-2, 20), price = 20),
            purchase("d20", at(-3, 20), price = 20),
            purchase("d20", at(-4, 20), price = 20),
            purchase("d50", at(-5, 20), price = 50),
            purchase("s10", at(-6, 20), price = 10)
        )

        val profile = build(history)

        assertEquals(6, profile.totalPurchases)
        assertEquals("d20", profile.mostPurchasedOfferId)
        assertEquals(20, profile.mostPurchasedAmountKsh)
        assertEquals(OfferCategory.DATA, profile.favouriteCategory)
        assertEquals(PersonalizationEngine.BAND_HOURLY, profile.preferredValidity)
        assertEquals("d20", profile.favouriteHourlyOfferId)
        assertEquals("d50", profile.favouriteDailyOfferId)
        assertEquals(4, profile.purchaseCountByOfferId["d20"] ?: 0)
        assertEquals(at(-1, 20), profile.lastPurchaseAtMillis)
        assertEquals("d20", profile.lastPurchaseOfferId)
        assertEquals(at(-1, 20), profile.lastPurchaseAtMillisByOfferId["d20"] ?: 0L)
        assertFalse(profile.isEmpty())
    }

    @Test
    fun `no history at all leaves an empty profile`() {
        val profile = build(emptyList())

        assertTrue(profile.isEmpty())
        assertEquals(0, profile.totalPurchases)
        assertEquals("", profile.mostPurchasedOfferId)
        assertNull(profile.favouriteCategory)
        assertEquals(BuyerTimeProfile.UNKNOWN, profile.timeProfile)
        assertEquals(-1, profile.usualPurchaseHour)
        assertEquals(BuyerFrequency.NEW, profile.frequency)
    }

    @Test
    fun `abandoned and failed attempts teach nothing`() {
        val history = listOf(
            purchase("a", at(-1, 12)),
            purchase("b", at(-1, 13), status = PaymentStatus.CANCELLED),
            purchase("b", at(-1, 14), status = PaymentStatus.FAILED),
            purchase("b", at(-1, 15), status = PaymentStatus.EXPIRED),
            purchase("b", at(-1, 16), status = PaymentStatus.COULD_NOT_VERIFY),
            purchase("b", at(-1, 17), status = PaymentStatus.NOT_CONFIRMED)
        )

        val profile = build(history)

        assertEquals(1, profile.totalPurchases)
        assertEquals("a", profile.mostPurchasedOfferId)
        assertNull(profile.purchaseCountByOfferId["b"])
    }

    @Test
    fun `an offline buyer waiting to verify is still learned from`() {
        val history = (1..3).map {
            purchase("offlineOffer", at(-it, 9), status = PaymentStatus.WAITING_VERIFY)
        }

        val profile = build(history)

        assertEquals(3, profile.totalPurchases)
        assertEquals("offlineOffer", profile.mostPurchasedOfferId)
    }

    // -----------------------------------------------------------------------
    // Hour histogram and time bands
    // -----------------------------------------------------------------------

    private fun profileForHour(hour: Int) =
        build((1..3).map { purchase("d20", at(-it, hour), price = 20) })

    @Test
    fun `usual purchase hour comes from the Nairobi hour histogram`() {
        val profile = build(
            listOf(
                purchase("d20", at(-1, 20), price = 20),
                purchase("d20", at(-2, 20), price = 20),
                purchase("d20", at(-3, 20), price = 20),
                purchase("d50", at(-4, 8), price = 50)
            )
        )

        assertEquals(20, profile.usualPurchaseHour)
        assertEquals(BuyerTimeProfile.EVENING, profile.timeProfile)
    }

    @Test
    fun `time bands hold at every Nairobi boundary hour`() {
        assertEquals(BuyerTimeProfile.NIGHT, profileForHour(4).timeProfile)
        assertEquals(BuyerTimeProfile.MORNING, profileForHour(5).timeProfile)
        assertEquals(BuyerTimeProfile.MORNING, profileForHour(11).timeProfile)
        assertEquals(BuyerTimeProfile.AFTERNOON, profileForHour(12).timeProfile)
        assertEquals(BuyerTimeProfile.AFTERNOON, profileForHour(16).timeProfile)
        assertEquals(BuyerTimeProfile.EVENING, profileForHour(17).timeProfile)
        assertEquals(BuyerTimeProfile.EVENING, profileForHour(21).timeProfile)
        assertEquals(BuyerTimeProfile.NIGHT, profileForHour(22).timeProfile)
        assertEquals(BuyerTimeProfile.NIGHT, profileForHour(0).timeProfile)
    }

    @Test
    fun `each boundary hour is reported back exactly`() {
        listOf(0, 4, 5, 11, 12, 16, 17, 21, 22, 23).forEach { hour ->
            assertEquals(hour, profileForHour(hour).usualPurchaseHour)
        }
    }

    // -----------------------------------------------------------------------
    // Frequency thresholds
    // -----------------------------------------------------------------------

    @Test
    fun `fewer than three purchases is a NEW buyer`() {
        val profile = build(listOf(purchase("d20", at(-1, 12)), purchase("d20", at(-2, 12))))
        assertEquals(BuyerFrequency.NEW, profile.frequency)
    }

    @Test
    fun `five or more purchases a week on average is a HEAVY buyer`() {
        // 10 purchases across the last 5 days -> ~10 per week.
        val history = (1..5).flatMap { day ->
            listOf(purchase("d20", at(-day, 9)), purchase("d20", at(-day, 19)))
        }
        assertEquals(BuyerFrequency.HEAVY, build(history).frequency)
    }

    @Test
    fun `buying on five of the last seven days is a DAILY buyer`() {
        // One old purchase stretches the span so the weekly average stays below
        // the HEAVY threshold, isolating the DAILY rule.
        val history = (1..5).map { purchase("d20", at(-it, 19)) } + purchase("d20", at(-90, 19))
        val profile = build(history)

        assertEquals(BuyerFrequency.DAILY, profile.frequency)
    }

    @Test
    fun `seventy percent of purchases on Sat or Sun is a WEEKEND buyer`() {
        // Anchor day is Wed 2024-01-10: -4 = Sat, -3 = Sun, -11 = Sat, -10 = Sun, -6 = Thu.
        val history = listOf(
            purchase("d20", at(-4, 11)),
            purchase("d20", at(-3, 11)),
            purchase("d20", at(-11, 11)),
            purchase("d20", at(-10, 11)),
            purchase("d50", at(-6, 11))
        )
        assertEquals(BuyerFrequency.WEEKEND, build(history).frequency)
    }

    @Test
    fun `a few spread out weekday purchases is an OCCASIONAL buyer`() {
        // -6 = Thu, -30 = Mon, -61 = Fri.
        val history = listOf(
            purchase("d20", at(-6, 11)),
            purchase("d20", at(-30, 11)),
            purchase("d50", at(-61, 11))
        )
        assertEquals(BuyerFrequency.OCCASIONAL, build(history).frequency)
    }

    // -----------------------------------------------------------------------
    // Recency weighting
    // -----------------------------------------------------------------------

    @Test
    fun `a recent habit outranks a bigger but old one`() {
        val old = (0..7).map { purchase("oldFavourite", at(-120 - it, 12), price = 99) }
        val recent = (1..3).map { purchase("newFavourite", at(-it, 12), price = 20) }

        val profile = build(old + recent)

        // Plain counts would pick the old offer (8 > 3); recency weighting must not.
        assertEquals(8, profile.purchaseCountByOfferId["oldFavourite"] ?: 0)
        assertEquals(3, profile.purchaseCountByOfferId["newFavourite"] ?: 0)
        assertEquals("newFavourite", profile.mostPurchasedOfferId)
        assertEquals(20, profile.mostPurchasedAmountKsh)
    }

    @Test
    fun `recency weight halves over one half life`() {
        val fresh = PersonalizationEngine.recencyWeight(NOW, NOW)
        val oneHalfLife = PersonalizationEngine.recencyWeight(
            NOW - (PersonalizationEngine.RECENCY_HALF_LIFE_DAYS * 86_400_000.0).toLong(),
            NOW
        )
        assertEquals(1.0, fresh, 1e-9)
        assertEquals(0.5, oneHalfLife, 1e-6)
    }

    // -----------------------------------------------------------------------
    // Payment and recipient behaviour
    // -----------------------------------------------------------------------

    @Test
    fun `the most used M-Pesa payment number is remembered`() {
        val history = (1..5).map { purchase("d20", at(-it, 12), payer = "0722000111") } +
            purchase("d20", at(-6, 12), payer = "0733000222")

        val profile = build(history)

        assertEquals("+254722000111", KenyanPhone.toE164(profile.preferredPayerNumber))
    }

    @Test
    fun `payment numbers typed in different formats count as one number`() {
        val history = listOf(
            purchase("d20", at(-1, 12), payer = "0722000111"),
            purchase("d20", at(-1, 13), payer = "+254722000111"),
            purchase("d20", at(-1, 14), payer = "254722000111"),
            purchase("d20", at(-1, 15), payer = "0733000222"),
            purchase("d20", at(-1, 16), payer = "0733000222")
        )

        val profile = build(history)

        assertEquals("+254722000111", KenyanPhone.toE164(profile.preferredPayerNumber))
    }

    @Test
    fun `top recipients are ordered by use and de-duplicated across formats`() {
        val history = listOf(
            purchase("d20", at(-5, 12), recipient = "0711111111"),
            purchase("d20", at(-3, 12), recipient = "0700000001"),
            purchase("d20", at(-2, 12), recipient = "254700000001"),
            purchase("d20", at(-1, 12), recipient = "+254700000001")
        )

        val profile = build(history, recentRecipients = listOf("0722222222", "0700 000 001"))

        assertEquals(3, profile.topRecipients.size)
        assertEquals(3, profile.topRecipients[0].count)
        assertEquals("+254700000001", KenyanPhone.toE164(profile.topRecipients[0].number))
        assertEquals(at(-1, 12), profile.topRecipients[0].lastUsedAtMillis)
        assertEquals(1, profile.topRecipients[1].count)
        assertEquals("+254711111111", KenyanPhone.toE164(profile.topRecipients[1].number))
        assertEquals(0, profile.topRecipients[2].count)
        assertEquals("+254722222222", KenyanPhone.toE164(profile.topRecipients[2].number))
    }

    @Test
    fun `recent recipients alone never make the profile look experienced`() {
        val profile = build(emptyList(), recentRecipients = listOf("0722222222"))

        assertTrue(profile.isEmpty())
        assertEquals(1, profile.topRecipients.size)
        assertEquals(0, profile.topRecipients[0].count)
        assertEquals(listOf("0722222222"), profile.suggestedRecipients())
    }

    @Test
    fun `checkout suggestions fall back when nothing has been learned`() {
        val profile = build(emptyList())

        assertEquals("0712345678", profile.suggestedPayerNumber("0712345678"))
        assertTrue(profile.suggestedRecipients().isEmpty())
    }

    // -----------------------------------------------------------------------
    // Category inference without a catalogue
    // -----------------------------------------------------------------------

    @Test
    fun `category is inferred from the record text when the catalogue is unavailable`() {
        val history = (1..3).map {
            purchase(
                "sms_pack",
                at(-it, 12),
                offerName = "Text Pack",
                allowance = "20 SMS"
            )
        }

        val profile = PersonalizationEngine.buildProfile(history, emptySet(), emptyList(), NOW)

        assertEquals(OfferCategory.SMS, profile.favouriteCategory)
    }

    @Test
    fun `the catalogue category wins over the inferred one`() {
        val history = (1..3).map {
            purchase("s10", at(-it, 12), offerName = "8 GB", allowance = "8 GB")
        }

        val profile = build(history)

        assertEquals(OfferCategory.SMS, profile.favouriteCategory)
    }

    @Test
    fun `building the same profile twice gives the same result`() {
        val history = listOf(
            purchase("d20", at(-1, 20), price = 20),
            purchase("d50", at(-2, 8), price = 50),
            purchase("d20", at(-3, 20), price = 20)
        )

        assertEquals(build(history), build(history))
    }
}
