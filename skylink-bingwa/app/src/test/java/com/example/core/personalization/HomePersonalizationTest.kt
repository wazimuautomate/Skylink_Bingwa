package com.example.core.personalization

import com.example.core.model.OfferCategory
import com.example.core.personalization.PersonalizationTestData.NOW
import com.example.core.personalization.PersonalizationTestData.at
import com.example.core.personalization.PersonalizationTestData.offer
import com.example.feature.home.HomeSections
import com.example.feature.home.personalizeHomeSections
import com.example.feature.home.personalizeOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home-side regression guard: with nothing learned, Home's sections and
 * labels must be exactly what they were before personalization existed. Only a
 * real local purchase history is allowed to change anything.
 */
class HomePersonalizationTest {

    private val favourites = listOf(
        offer("favA", price = 20, favourite = true),
        offer("favB", price = 50, favourite = true),
        offer("favC", price = 99, favourite = true)
    )
    private val suggestions = listOf(
        offer("sug1", price = 10, category = OfferCategory.SMS),
        offer("sug2", price = 30, category = OfferCategory.DATA)
    )
    private val popular = listOf(offer("pop1"), offer("pop2"))

    private fun sections() = HomeSections(
        popular = popular,
        boughtToday = emptyList(),
        moreOffers = emptyList(),
        buyAgain = emptyList(),
        favourites = favourites,
        suggestions = suggestions
    )

    @Test
    fun `an empty profile leaves Home exactly as it was`() {
        val original = sections()

        val result = personalizeHomeSections(original, BehaviourProfile.EMPTY, NOW)

        assertSame(original, result.sections)
        assertTrue(result.badges.isEmpty())
        assertEquals(listOf("favA", "favB", "favC"), result.sections.favourites.map { it.id })
        assertEquals(listOf("sug1", "sug2"), result.sections.suggestions.map { it.id })
        assertEquals(listOf("pop1", "pop2"), result.sections.popular.map { it.id })
    }

    @Test
    fun `personalizeOrder returns the same list instance for an empty profile`() {
        assertSame(favourites, personalizeOrder(favourites, BehaviourProfile.EMPTY, NOW))
    }

    @Test
    fun `what the customer actually buys is lifted to the top of favourites`() {
        val profile = BehaviourProfile(
            totalPurchases = 6,
            mostPurchasedOfferId = "favC",
            purchaseCountByOfferId = mapOf("favC" to 6),
            lastPurchaseAtMillisByOfferId = mapOf("favC" to at(-1, 20)),
            lastPurchaseAtMillis = at(-1, 20),
            lastPurchaseOfferId = "favC"
        )

        val result = personalizeHomeSections(sections(), profile, NOW)

        assertEquals("favC", result.sections.favourites.first().id)
        assertEquals(PersonalBadge.YOUR_USUAL, result.badges["favC"])
    }

    @Test
    fun `Home stays calm - only a couple of badges are produced`() {
        val counts = (favourites + suggestions).associate { it.id to 4 }
        val profile = BehaviourProfile(
            totalPurchases = counts.values.sum(),
            mostPurchasedOfferId = "favA",
            purchaseCountByOfferId = counts,
            lastPurchaseAtMillis = at(-1, 20),
            lastPurchaseOfferId = "favA"
        )

        val result = personalizeHomeSections(sections(), profile, NOW)

        // Two on the favourites list, one on the suggestions row: never a badge
        // on every card.
        assertTrue(result.badges.size <= 3)
        assertTrue(result.badges.keys.all { id -> result.sections.favourites.any { it.id == id } ||
            result.sections.suggestions.any { it.id == id } })
    }

    @Test
    fun `bought today is never re-ordered`() {
        val boughtToday = listOf(offer("t1"), offer("t2"), offer("t3"))
        val original = sections().copy(boughtToday = boughtToday)
        val profile = BehaviourProfile(
            totalPurchases = 5,
            mostPurchasedOfferId = "t3",
            purchaseCountByOfferId = mapOf("t3" to 5)
        )

        val result = personalizeHomeSections(original, profile, NOW)

        assertEquals(listOf("t1", "t2", "t3"), result.sections.boughtToday.map { it.id })
    }

    @Test
    fun `personalizing Home twice gives an identical result`() {
        val profile = BehaviourProfile(
            totalPurchases = 4,
            mostPurchasedOfferId = "favB",
            favouriteCategory = OfferCategory.DATA,
            preferredValidity = PersonalizationEngine.BAND_DAILY,
            usualPurchaseHour = 12,
            purchaseCountByOfferId = mapOf("favB" to 4),
            lastPurchaseAtMillisByOfferId = mapOf("favB" to at(-2, 12))
        )

        val first = personalizeHomeSections(sections(), profile, NOW)
        val second = personalizeHomeSections(sections(), profile, NOW)

        assertEquals(first.sections.favourites.map { it.id }, second.sections.favourites.map { it.id })
        assertEquals(first.badges, second.badges)
    }
}
