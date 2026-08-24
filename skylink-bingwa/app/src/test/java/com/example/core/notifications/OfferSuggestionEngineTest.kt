package com.example.core.notifications

import com.example.core.model.DailyRule
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.PaymentMethod
import com.example.core.model.PaymentStatus
import com.example.core.model.PurchaseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferSuggestionEngineTest {

    // A fixed midday Nairobi instant so time-of-day tie-breaks stay deterministic.
    private val now = 1_753_444_800_000L // 2025-07-25 ~15:00 EAT

    private fun offer(
        id: String,
        category: OfferCategory,
        price: Int = 50,
        popular: Boolean = false,
        band: String = "Daily"
    ) = OfferItem(
        id = id,
        name = "Offer $id",
        allowance = "1GB",
        priceKsh = price,
        validity = "24 hours",
        validityBand = band,
        category = category,
        dailyRule = DailyRule.BUY_AGAIN_TODAY,
        isPopular = popular
    )

    private fun purchase(offerId: String, millis: Long) = PurchaseRecord(
        id = "p_$offerId",
        offerId = offerId,
        offerName = "Offer $offerId",
        allowance = "1GB",
        priceKsh = 50,
        recipientNumber = "0722000000",
        payerNumber = "0722000000",
        mpesaCode = "ABC123",
        timestampMillis = millis,
        status = PaymentStatus.RECEIVED,
        paymentMethod = PaymentMethod.STK_PUSH
    )

    @Test
    fun prefersDepletedCategory() {
        val offers = listOf(
            offer("sms1", OfferCategory.SMS),
            offer("data1", OfferCategory.DATA),
            offer("min1", OfferCategory.MINUTES)
        )
        val result = OfferSuggestionEngine.suggest(
            offers = offers,
            purchases = emptyList(),
            nowMillis = now,
            depleted = OfferCategory.DATA,
            connection = ConnectionState.WIFI
        )
        assertTrue(result.isNotEmpty())
        assertEquals(OfferCategory.DATA, result.first().category)
    }

    @Test
    fun excludesOffersBoughtToday() {
        val offers = listOf(
            offer("data1", OfferCategory.DATA),
            offer("data2", OfferCategory.DATA)
        )
        val purchases = listOf(purchase("data1", now))
        val result = OfferSuggestionEngine.suggest(
            offers = offers,
            purchases = purchases,
            nowMillis = now,
            depleted = OfferCategory.DATA,
            connection = ConnectionState.CELLULAR
        )
        assertTrue(result.none { it.id == "data1" })
        assertTrue(result.any { it.id == "data2" })
    }

    @Test
    fun yesterdaysPurchaseDoesNotExclude() {
        val offers = listOf(offer("data1", OfferCategory.DATA))
        val yesterday = now - 24L * 60 * 60 * 1000
        val result = OfferSuggestionEngine.suggest(
            offers = offers,
            purchases = listOf(purchase("data1", yesterday)),
            nowMillis = now,
            depleted = null,
            connection = ConnectionState.WIFI
        )
        assertTrue(result.any { it.id == "data1" })
    }

    @Test
    fun respectsMaxSuggestions() {
        val offers = (1..10).map { offer("o$it", OfferCategory.DATA, price = it) }
        val result = OfferSuggestionEngine.suggest(
            offers = offers,
            purchases = emptyList(),
            nowMillis = now,
            depleted = OfferCategory.DATA,
            connection = ConnectionState.BOTH
        )
        assertTrue(result.size <= OfferSuggestionEngine.MAX_SUGGESTIONS)
        assertEquals(OfferSuggestionEngine.MAX_SUGGESTIONS, result.size)
    }

    @Test
    fun emptyInputs_returnEmpty_noCrash() {
        val result = OfferSuggestionEngine.suggest(
            offers = emptyList(),
            purchases = emptyList(),
            nowMillis = now,
            depleted = OfferCategory.DATA,
            connection = ConnectionState.NONE
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun excludesMetaCategories() {
        val offers = listOf(
            offer("all1", OfferCategory.ALL),
            offer("fav1", OfferCategory.FAVOURITES),
            offer("data1", OfferCategory.DATA)
        )
        val result = OfferSuggestionEngine.suggest(
            offers = offers,
            purchases = emptyList(),
            nowMillis = now,
            depleted = null,
            connection = ConnectionState.WIFI
        )
        assertFalse(result.any { it.category == OfferCategory.ALL })
        assertFalse(result.any { it.category == OfferCategory.FAVOURITES })
    }
}
