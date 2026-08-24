package com.example.feature.home

import com.example.core.model.DailyRule
import com.example.core.model.DailyStateKind
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.PaymentMethod
import com.example.core.model.PaymentStatus
import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionKind
import com.example.core.model.PurchasePolicy
import com.example.core.model.PurchaseRecord
import com.example.data.fake.OfferFilterState
import com.example.data.fake.SortOption
import com.example.data.fake.ValidityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for the Phase 3 catalogue logic (no Compose, no
 * Android, no real clock). Timestamps are fixed Nairobi-anchored constants so
 * every day-boundary assertion is deterministic.
 */
class CatalogueLogicTest {

    private companion object {
        // 2024-01-10 12:00:00 in Africa/Nairobi (UTC+3) == 2024-01-10T09:00:00Z.
        // Midday keeps ±1h offsets on the same day and +26h on the next day,
        // independent of DST (Nairobi has none).
        const val TODAY = 1_704_877_200_000L
        const val LATER_SAME_DAY = TODAY + 60L * 60L * 1000L // +1h -> still Jan 10
        const val PLUS_26H = TODAY + 26L * 60L * 60L * 1000L // next Nairobi day
        const val TWO_DAYS_AGO = TODAY - 2L * 24L * 60L * 60L * 1000L

        const val RECIPIENT = "0700000001"
        const val OTHER_RECIPIENT = "0711111111"
    }

    private fun offer(
        id: String,
        name: String = "Offer $id",
        allowance: String = "1 GB",
        price: Int = 50,
        validity: String = "24 hours",
        category: OfferCategory = OfferCategory.DATA,
        dailyRule: DailyRule = DailyRule.BUY_AGAIN_TODAY,
        policy: PurchasePolicy = PurchasePolicy.MULTIPLE_PER_DAY,
        maxPerDay: Int? = null,
        popular: Boolean = false,
        favourite: Boolean = false,
        description: String = ""
    ): OfferItem = OfferItem(
        id = id,
        name = name,
        allowance = allowance,
        priceKsh = price,
        validity = validity,
        category = category,
        dailyRule = dailyRule,
        purchasePolicy = policy,
        maxPurchasesPerDay = maxPerDay,
        isPopular = popular,
        isFavourite = favourite,
        description = description
    )

    private fun purchase(
        offerId: String,
        recipient: String = RECIPIENT,
        status: PaymentStatus = PaymentStatus.RECEIVED,
        ts: Long = TODAY
    ): PurchaseRecord = PurchaseRecord(
        id = "pur_${offerId}_${ts}_${status.name}",
        offerId = offerId,
        offerName = "Offer $offerId",
        allowance = "1 GB",
        priceKsh = 50,
        recipientNumber = recipient,
        payerNumber = recipient,
        mpesaCode = "TESTCODE",
        timestampMillis = ts,
        status = status,
        paymentMethod = PaymentMethod.STK_PUSH
    )

    // -----------------------------------------------------------------------
    // validityRankMinutes
    // -----------------------------------------------------------------------

    @Test
    fun `validityRankMinutes orders hour to month ascending`() {
        val oneHour = validityRankMinutes("1 hour")
        val threeHours = validityRankMinutes("3 hours")
        val day = validityRankMinutes("24 hours")
        val week = validityRankMinutes("7 days")
        val month = validityRankMinutes("30 days")

        assertTrue(oneHour < threeHours)
        assertTrue(threeHours < day)
        assertTrue(day < week)
        assertTrue(week < month)
    }

    @Test
    fun `validityRankMinutes treats Weekend as a week`() {
        assertEquals(validityRankMinutes("7 days"), validityRankMinutes("Weekend"))
    }

    @Test
    fun `validityRankMinutes puts an unknown validity in the middle`() {
        val unknown = validityRankMinutes("whenever")
        assertTrue(unknown > validityRankMinutes("3 hours"))
        assertTrue(unknown < validityRankMinutes("7 days"))
    }

    // -----------------------------------------------------------------------
    // sortOffers
    // -----------------------------------------------------------------------

    private val sortSample = listOf(
        offer("a", price = 100, validity = "1 hour", popular = false),
        offer("b", price = 50, validity = "7 days", popular = true),
        offer("c", price = 200, validity = "24 hours", popular = false)
    )

    @Test
    fun `sortOffers POPULAR keeps popular first then price descending`() {
        val result = sortOffers(sortSample, SortOption.POPULAR).map { it.id }
        assertEquals(listOf("b", "c", "a"), result)
    }

    @Test
    fun `sortOffers LOWEST_PRICE ascending`() {
        val result = sortOffers(sortSample, SortOption.LOWEST_PRICE).map { it.id }
        assertEquals(listOf("b", "a", "c"), result)
    }

    @Test
    fun `sortOffers HIGHEST_VALUE descending`() {
        val result = sortOffers(sortSample, SortOption.HIGHEST_VALUE).map { it.id }
        assertEquals(listOf("c", "a", "b"), result)
    }

    @Test
    fun `sortOffers SHORTEST_VALIDITY ascending by validity`() {
        val result = sortOffers(sortSample, SortOption.SHORTEST_VALIDITY).map { it.id }
        assertEquals(listOf("a", "c", "b"), result)
    }

    @Test
    fun `sortOffers LONGEST_VALIDITY descending by validity`() {
        val result = sortOffers(sortSample, SortOption.LONGEST_VALIDITY).map { it.id }
        assertEquals(listOf("b", "c", "a"), result)
    }

    // -----------------------------------------------------------------------
    // filterAndSortOffers
    // -----------------------------------------------------------------------

    private val filterCatalogue = listOf(
        offer("d1", allowance = "1 GB", price = 19, validity = "1 hour", category = OfferCategory.DATA),
        offer("d2", allowance = "2 GB", price = 110, validity = "24 hours", category = OfferCategory.DATA, favourite = true),
        offer("s1", name = "Text Pack", allowance = "20 SMS", price = 5, validity = "24 hours", category = OfferCategory.SMS),
        offer("m1", allowance = "50 minutes", price = 51, validity = "Till midnight", category = OfferCategory.MINUTES),
        offer("w1", allowance = "8 GB", price = 1000, validity = "30 days", category = OfferCategory.DATA)
    )

    @Test
    fun `filterAndSortOffers category filter keeps only that category`() {
        val result = filterAndSortOffers(filterCatalogue, OfferFilterState(selectedCategory = OfferCategory.DATA))
        assertTrue(result.all { it.category == OfferCategory.DATA })
        assertEquals(setOf("d1", "d2", "w1"), result.map { it.id }.toSet())
    }

    @Test
    fun `filterAndSortOffers FAVOURITES category returns only favourites`() {
        val result = filterAndSortOffers(filterCatalogue, OfferFilterState(selectedCategory = OfferCategory.FAVOURITES))
        assertEquals(listOf("d2"), result.map { it.id })
    }

    @Test
    fun `filterAndSortOffers search matches category label`() {
        val result = filterAndSortOffers(filterCatalogue, OfferFilterState(searchQuery = "sms"))
        assertEquals(listOf("s1"), result.map { it.id })
    }

    @Test
    fun `filterAndSortOffers price ceiling excludes pricier offers`() {
        val result = filterAndSortOffers(filterCatalogue, OfferFilterState(maxPriceKsh = 50))
        val ids = result.map { it.id }
        assertTrue(ids.contains("d1"))
        assertTrue(ids.contains("s1"))
        assertFalse(ids.contains("d2")) // 110
        assertFalse(ids.contains("m1")) // 51
        assertFalse(ids.contains("w1")) // 1000
    }

    @Test
    fun `filterAndSortOffers validity filter narrows to the matching band`() {
        val result = filterAndSortOffers(filterCatalogue, OfferFilterState(selectedValidity = ValidityFilter.MONTHLY))
        assertEquals(listOf("w1"), result.map { it.id })
    }

    // -----------------------------------------------------------------------
    // dailyStateFor
    // -----------------------------------------------------------------------

    @Test
    fun `dailyStateFor MULTIPLE_PER_DAY is always available regardless of history`() {
        val o = offer("m", policy = PurchasePolicy.MULTIPLE_PER_DAY)
        val history = listOf(purchase("m"), purchase("m"), purchase("m"))
        val state = dailyStateFor(o, history, RECIPIENT, TODAY)
        assertEquals(DailyStateKind.AVAILABLE, state.kind)
        assertTrue(state.purchasable)
    }

    @Test
    fun `dailyStateFor once-per-day received today is available tomorrow and not purchasable`() {
        val o = offer("o", dailyRule = DailyRule.ONCE_PER_DAY, policy = PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY)
        val state = dailyStateFor(o, listOf(purchase("o")), RECIPIENT, TODAY)
        assertEquals(DailyStateKind.AVAILABLE_TOMORROW, state.kind)
        assertFalse(state.purchasable)
    }

    @Test
    fun `dailyStateFor once-per-day waiting-verify today shows waiting`() {
        val o = offer("o", dailyRule = DailyRule.ONCE_PER_DAY, policy = PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY)
        val state = dailyStateFor(o, listOf(purchase("o", status = PaymentStatus.WAITING_VERIFY)), RECIPIENT, TODAY)
        assertEquals(DailyStateKind.WAITING_VERIFY, state.kind)
        assertFalse(state.purchasable)
    }

    @Test
    fun `dailyStateFor once-per-day with no history is available`() {
        val o = offer("o", dailyRule = DailyRule.ONCE_PER_DAY, policy = PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY)
        val state = dailyStateFor(o, emptyList(), RECIPIENT, TODAY)
        assertEquals(DailyStateKind.AVAILABLE, state.kind)
        assertTrue(state.purchasable)
    }

    @Test
    fun `dailyStateFor once-per-day is not blocked by a different recipient`() {
        val o = offer("o", dailyRule = DailyRule.ONCE_PER_DAY, policy = PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY)
        val state = dailyStateFor(o, listOf(purchase("o", recipient = OTHER_RECIPIENT)), RECIPIENT, TODAY)
        assertEquals(DailyStateKind.AVAILABLE, state.kind)
    }

    @Test
    fun `dailyStateFor once-per-day is not blocked by a purchase on a different day`() {
        val o = offer("o", dailyRule = DailyRule.ONCE_PER_DAY, policy = PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY)
        val state = dailyStateFor(o, listOf(purchase("o", ts = TWO_DAYS_AGO)), RECIPIENT, TODAY)
        assertEquals(DailyStateKind.AVAILABLE, state.kind)
    }

    @Test
    fun `dailyStateFor max-per-day with one received leaves two purchases`() {
        val o = offer("x", policy = PurchasePolicy.MAX_PER_RECIPIENT_PER_DAY, maxPerDay = 3)
        val state = dailyStateFor(o, listOf(purchase("x")), RECIPIENT, TODAY)
        assertEquals(DailyStateKind.PURCHASES_LEFT, state.kind)
        assertEquals(2, state.purchasesLeft)
        assertTrue(state.label.contains("2 purchases left"))
        assertTrue(state.purchasable)
    }

    @Test
    fun `dailyStateFor max-per-day exhausted is available tomorrow`() {
        val o = offer("x", policy = PurchasePolicy.MAX_PER_RECIPIENT_PER_DAY, maxPerDay = 3)
        val history = listOf(purchase("x"), purchase("x", ts = LATER_SAME_DAY), purchase("x", ts = LATER_SAME_DAY + 1000))
        val state = dailyStateFor(o, history, RECIPIENT, TODAY)
        assertEquals(DailyStateKind.AVAILABLE_TOMORROW, state.kind)
        assertFalse(state.purchasable)
    }

    // -----------------------------------------------------------------------
    // deriveHomeSections
    // -----------------------------------------------------------------------

    private val popA = offer("popA", category = OfferCategory.DATA, policy = PurchasePolicy.MULTIPLE_PER_DAY, popular = true, price = 100)
    private val popB = offer("popB", category = OfferCategory.SMS, policy = PurchasePolicy.MULTIPLE_PER_DAY, popular = true, price = 200)
    private val normalC = offer("normalC", category = OfferCategory.DATA, policy = PurchasePolicy.MULTIPLE_PER_DAY, popular = false)
    private val onceD = offer("onceD", category = OfferCategory.MINUTES, dailyRule = DailyRule.ONCE_PER_DAY, policy = PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY)
    private val favE = offer("favE", category = OfferCategory.SPECIAL, policy = PurchasePolicy.MULTIPLE_PER_DAY, favourite = true)
    private val homeOffers = listOf(popA, popB, normalC, onceD, favE)

    @Test
    fun `deriveHomeSections popular only contains popular offers`() {
        val sections = deriveHomeSections(homeOffers, emptyList(), TODAY)
        assertTrue(sections.popular.all { it.isPopular })
        assertEquals(setOf("popA", "popB"), sections.popular.map { it.id }.toSet())
    }

    @Test
    fun `deriveHomeSections boughtToday contains offers received today`() {
        val sections = deriveHomeSections(homeOffers, listOf(purchase("popA")), TODAY)
        assertEquals(listOf("popA"), sections.boughtToday.map { it.id })
    }

    @Test
    fun `deriveHomeSections buyAgain only contains repeatable purchased offers`() {
        val sections = deriveHomeSections(homeOffers, listOf(purchase("popA"), purchase("onceD")), TODAY)
        val ids = sections.buyAgain.map { it.id }
        assertTrue(ids.contains("popA"))
        assertFalse(ids.contains("onceD")) // once-per-day is not repeatable
    }

    @Test
    fun `deriveHomeSections favourites contains favourite offers`() {
        val sections = deriveHomeSections(homeOffers, emptyList(), TODAY)
        assertEquals(listOf("favE"), sections.favourites.map { it.id })
    }

    @Test
    fun `deriveHomeSections moreOffers is empty until a once-per-day offer is bought today`() {
        val without = deriveHomeSections(homeOffers, listOf(purchase("popA")), TODAY)
        assertTrue(without.moreOffers.isEmpty())

        val withOnce = deriveHomeSections(homeOffers, listOf(purchase("onceD")), TODAY)
        assertTrue(withOnce.moreOffers.isNotEmpty())
        assertTrue(withOnce.moreOffers.none { it.purchasePolicy == PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY })
    }

    // -----------------------------------------------------------------------
    // suggestSimilar
    // -----------------------------------------------------------------------

    @Test
    fun `suggestSimilar returns empty without any favourites or purchases`() {
        val offers = listOf(offer("a"), offer("b"))
        assertTrue(suggestSimilar(offers, emptyList()).isEmpty())
    }

    @Test
    fun `suggestSimilar returns same-category offers excluding the favourite`() {
        val dataFav = offer("dataFav", category = OfferCategory.DATA, favourite = true)
        val dataOther1 = offer("dataOther1", category = OfferCategory.DATA)
        val dataOther2 = offer("dataOther2", category = OfferCategory.DATA)
        val smsX = offer("smsX", category = OfferCategory.SMS)
        val offers = listOf(dataFav, dataOther1, dataOther2, smsX)

        val result = suggestSimilar(offers, emptyList())
        val ids = result.map { it.id }.toSet()
        assertEquals(setOf("dataOther1", "dataOther2"), ids)
        assertFalse(ids.contains("dataFav"))
        assertFalse(ids.contains("smsX"))
    }

    @Test
    fun `suggestSimilar never includes an already purchased offer`() {
        val dataFav = offer("dataFav", category = OfferCategory.DATA, favourite = true)
        val dataOther1 = offer("dataOther1", category = OfferCategory.DATA)
        val dataOther2 = offer("dataOther2", category = OfferCategory.DATA)
        val offers = listOf(dataFav, dataOther1, dataOther2)

        val result = suggestSimilar(offers, listOf(purchase("dataOther1")))
        assertEquals(listOf("dataOther2"), result.map { it.id })
    }

    // -----------------------------------------------------------------------
    // selectPromotions
    // -----------------------------------------------------------------------

    private fun promo(
        id: String,
        kind: PromotionKind,
        weight: Int,
        linkedOfferId: String? = null,
        endMillis: Long = Long.MAX_VALUE
    ): Promotion = Promotion(
        id = id,
        kind = kind,
        tag = "TAG",
        headline = "Headline $id",
        subhead = "Subhead",
        ctaLabel = "Go",
        accent = PromotionAccent.GREEN,
        linkedOfferId = linkedOfferId,
        priorityWeight = weight,
        endMillis = endMillis
    )

    private val promoOffers = listOf(offer("o1"))

    private val promoPool = listOf(
        promo("keepOffer", PromotionKind.OFFER, weight = 50, linkedOfferId = "o1"),
        promo("inactive", PromotionKind.OFFER, weight = 100, linkedOfferId = "o1", endMillis = TODAY - 1000),
        promo("missingOffer", PromotionKind.OFFER, weight = 80, linkedOfferId = "ghost"),
        promo("announce", PromotionKind.ANNOUNCEMENT, weight = 30),
        promo("update", PromotionKind.UPDATE, weight = 90)
    )

    @Test
    fun `selectPromotions drops only inactive slides and orders by weight`() {
        // Synced (admin) OFFER billboards routinely link to an offer id this install's
        // cached catalogue lacks; they must STILL show (only the active window gates
        // visibility). So "missingOffer" (linkedOfferId not in the catalogue) is kept,
        // and only the time-expired "inactive" slide is dropped.
        val result = selectPromotions(promoPool, promoOffers, TODAY, seed = 0L)
        val ids = result.map { it.id }
        assertEquals(listOf("update", "missingOffer", "keepOffer", "announce"), ids)
        assertFalse(ids.contains("inactive"))
    }

    @Test
    fun `selectPromotions keeps an offer slide with no linked offer (synced billboard)`() {
        // The common admin case: an OFFER-kind billboard published with a blank/absent
        // linked offer. It must render, not be silently swallowed.
        val pool = listOf(promo("noLink", PromotionKind.OFFER, weight = 10, linkedOfferId = null))
        val result = selectPromotions(pool, promoOffers, TODAY, seed = 0L)
        assertEquals(listOf("noLink"), result.map { it.id })
    }

    @Test
    fun `selectPromotions respects max`() {
        val result = selectPromotions(promoPool, promoOffers, TODAY, seed = 0L, max = 2)
        assertEquals(listOf("update", "missingOffer"), result.map { it.id })
    }

    // -----------------------------------------------------------------------
    // Nairobi day boundaries
    // -----------------------------------------------------------------------

    @Test
    fun `isSameNairobiDay true within the same day`() {
        assertTrue(isSameNairobiDay(TODAY, LATER_SAME_DAY))
        assertEquals(nairobiDayIndex(TODAY), nairobiDayIndex(LATER_SAME_DAY))
    }

    @Test
    fun `isSameNairobiDay false across a day boundary`() {
        assertFalse(isSameNairobiDay(TODAY, PLUS_26H))
        assertTrue(nairobiDayIndex(TODAY) != nairobiDayIndex(PLUS_26H))
    }
}
