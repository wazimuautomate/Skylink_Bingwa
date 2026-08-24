package com.example.core.personalization

import com.example.core.model.OfferCategory
import com.example.core.personalization.PersonalizationTestData.NOW
import com.example.core.personalization.PersonalizationTestData.at
import com.example.core.personalization.PersonalizationTestData.offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OfferRanker] behaviour: the fresh-install guarantee, stable deterministic
 * ordering, and badge selection at its exact boundaries.
 */
class OfferRankerTest {

    private fun profile(
        counts: Map<String, Int> = emptyMap(),
        lastAt: Map<String, Long> = emptyMap(),
        mostPurchased: String = "",
        category: OfferCategory? = null,
        validity: String = "",
        amount: Int = 0,
        usualHour: Int = -1
    ) = BehaviourProfile(
        totalPurchases = counts.values.sum().coerceAtLeast(1),
        mostPurchasedOfferId = mostPurchased,
        mostPurchasedAmountKsh = amount,
        favouriteCategory = category,
        preferredValidity = validity,
        usualPurchaseHour = usualHour,
        purchaseCountByOfferId = counts,
        lastPurchaseAtMillisByOfferId = lastAt
    )

    private val abc = listOf(offer("a"), offer("b"), offer("c"))

    // -----------------------------------------------------------------------
    // Fresh install must look exactly as before personalization existed
    // -----------------------------------------------------------------------

    @Test
    fun `an empty profile returns the input order with no scores and no badges`() {
        val ranked = OfferRanker.rank(abc, BehaviourProfile.EMPTY, NOW)

        assertEquals(listOf("a", "b", "c"), ranked.map { it.offer.id })
        assertTrue(ranked.all { it.score == 0.0 })
        assertTrue(ranked.all { it.badge == null })
    }

    @Test
    fun `an empty offer list stays empty`() {
        assertTrue(OfferRanker.rank(emptyList(), profile(counts = mapOf("a" to 3)), NOW).isEmpty())
    }

    @Test
    fun `badgeFor never labels anything for an empty profile`() {
        assertNull(OfferRanker.badgeFor(offer("a", favourite = true), BehaviourProfile.EMPTY, NOW))
    }

    // -----------------------------------------------------------------------
    // Ordering
    // -----------------------------------------------------------------------

    @Test
    fun `a frequently bought offer rises to the top`() {
        val ranked = OfferRanker.rank(
            abc,
            profile(
                counts = mapOf("c" to 5),
                lastAt = mapOf("c" to at(-1, 20)),
                mostPurchased = "c"
            ),
            NOW
        )

        assertEquals("c", ranked.first().offer.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `a recent purchase outranks an equally frequent but older one`() {
        val offers = listOf(offer("old"), offer("recent"))
        val ranked = OfferRanker.rank(
            offers,
            profile(
                counts = mapOf("old" to 3, "recent" to 3),
                lastAt = mapOf("old" to at(-60, 12), "recent" to at(-1, 12)),
                mostPurchased = "recent"
            ),
            NOW
        )

        assertEquals("recent", ranked.first().offer.id)
    }

    @Test
    fun `the favourite category and usual amount lift an offer that was never bought`() {
        val offers = listOf(
            offer("plainSms", price = 5, category = OfferCategory.SMS),
            offer("matchingData", price = 20, category = OfferCategory.DATA)
        )
        val ranked = OfferRanker.rank(
            offers,
            profile(
                counts = mapOf("somethingElse" to 4),
                mostPurchased = "somethingElse",
                category = OfferCategory.DATA,
                amount = 20
            ),
            NOW
        )

        assertEquals("matchingData", ranked.first().offer.id)
    }

    @Test
    fun `ties keep the caller's input order`() {
        // Nothing in this profile touches any of these offers, so every score is
        // identical and the original order must survive untouched.
        val ranked = OfferRanker.rank(abc, profile(counts = mapOf("unrelated" to 4)), NOW)

        assertEquals(listOf("a", "b", "c"), ranked.map { it.offer.id })
        assertEquals(1, ranked.map { it.score }.distinct().size)
    }

    @Test
    fun `ranking is deterministic across repeated calls`() {
        val p = profile(
            counts = mapOf("a" to 2, "c" to 5),
            lastAt = mapOf("a" to at(-4, 12), "c" to at(-1, 20)),
            mostPurchased = "c",
            category = OfferCategory.DATA,
            validity = PersonalizationEngine.BAND_DAILY,
            amount = 50,
            usualHour = 20
        )

        val first = OfferRanker.rank(abc, p, NOW)
        val second = OfferRanker.rank(abc, p, NOW)
        val third = OfferRanker.rank(abc, p, NOW)

        assertEquals(first.map { it.offer.id }, second.map { it.offer.id })
        assertEquals(first.map { it.offer.id }, third.map { it.offer.id })
        assertEquals(first.map { it.score }, second.map { it.score })
        assertEquals(first.map { it.badge }, second.map { it.badge })
    }

    // -----------------------------------------------------------------------
    // Badges
    // -----------------------------------------------------------------------

    @Test
    fun `three or more purchases of the most bought offer make it their usual bundle`() {
        val p = profile(
            counts = mapOf("a" to OfferRanker.MIN_BUYS_FOR_USUAL),
            lastAt = mapOf("a" to at(-3, 12)),
            mostPurchased = "a"
        )

        assertEquals(PersonalBadge.YOUR_USUAL, OfferRanker.badgeFor(offer("a"), p, NOW))
        assertEquals("Your usual bundle", PersonalBadge.YOUR_USUAL.label)
    }

    @Test
    fun `one purchase below the usual threshold is only buy again`() {
        val p = profile(
            counts = mapOf("a" to OfferRanker.MIN_BUYS_FOR_USUAL - 1),
            lastAt = mapOf("a" to at(-3, 12)),
            mostPurchased = "a"
        )

        assertEquals(PersonalBadge.BUY_AGAIN, OfferRanker.badgeFor(offer("a"), p, NOW))
    }

    @Test
    fun `an offer bought on the previous Nairobi day is bought yesterday`() {
        val p = profile(
            counts = mapOf("b" to 1),
            lastAt = mapOf("b" to at(-1, 23, 30)),
            mostPurchased = "a"
        )

        assertEquals(PersonalBadge.BOUGHT_YESTERDAY, OfferRanker.badgeFor(offer("b"), p, NOW))
    }

    @Test
    fun `bought earlier today or two days ago is not bought yesterday`() {
        val today = profile(counts = mapOf("b" to 1), lastAt = mapOf("b" to at(0, 2)))
        val twoDaysAgo = profile(counts = mapOf("b" to 1), lastAt = mapOf("b" to at(-2, 12)))

        assertEquals(PersonalBadge.BUY_AGAIN, OfferRanker.badgeFor(offer("b"), today, NOW))
        assertEquals(PersonalBadge.BUY_AGAIN, OfferRanker.badgeFor(offer("b"), twoDaysAgo, NOW))
    }

    @Test
    fun `a hearted offer that was never bought is only marked favourite`() {
        val p = profile(counts = mapOf("other" to 4), mostPurchased = "other")

        assertEquals(PersonalBadge.FAVOURITE, OfferRanker.badgeFor(offer("z", favourite = true), p, NOW))
        assertNull(OfferRanker.badgeFor(offer("z"), p, NOW))
    }

    @Test
    fun `only a couple of badges are handed out per list`() {
        val many = (1..6).map { offer("o$it") }
        val counts = (1..6).associate { "o$it" to it }
        val p = profile(counts = counts, mostPurchased = "o6")

        val ranked = OfferRanker.rank(many, p, NOW)

        assertEquals(OfferRanker.DEFAULT_MAX_BADGES, ranked.count { it.badge != null })
        // The badges land on the most relevant cards, not on arbitrary ones.
        assertTrue(ranked.take(OfferRanker.DEFAULT_MAX_BADGES).all { it.badge != null })
    }

    @Test
    fun `maxBadges zero produces ordering with no labels at all`() {
        val p = profile(counts = mapOf("a" to 4), mostPurchased = "a")

        val ranked = OfferRanker.rank(abc, p, NOW, maxBadges = 0)

        assertEquals("a", ranked.first().offer.id)
        assertTrue(ranked.all { it.badge == null })
    }

    @Test
    fun `every offer carries at most one badge`() {
        val p = profile(
            counts = mapOf("a" to 5),
            lastAt = mapOf("a" to at(-1, 20)),
            mostPurchased = "a"
        )

        // "a" is simultaneously the most bought, bought yesterday and hearted;
        // exactly one label may win, and it is the strongest one.
        assertEquals(
            PersonalBadge.YOUR_USUAL,
            OfferRanker.badgeFor(offer("a", favourite = true), p, NOW)
        )
    }
}
