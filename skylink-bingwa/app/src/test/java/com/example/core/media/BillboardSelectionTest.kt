package com.example.core.media

import com.example.core.model.OfferCategory
import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM coverage for the PURE billboard selection rules: active window,
 * priority order, the local category-affinity nudge and deterministic ties.
 */
class BillboardSelectionTest {

    private fun promo(
        id: String,
        weight: Int = 0,
        start: Long = 0L,
        end: Long = Long.MAX_VALUE,
        category: OfferCategory? = null
    ) = Promotion(
        id = id,
        kind = PromotionKind.ANNOUNCEMENT,
        tag = "TAG",
        headline = "Headline $id",
        subhead = "Subhead $id",
        ctaLabel = "See offers",
        accent = PromotionAccent.BLUE,
        linkedCategory = category,
        priorityWeight = weight,
        startMillis = start,
        endMillis = end
    )

    private val now = 1_000_000L

    // --- active window -------------------------------------------------------

    @Test
    fun `only slides whose window contains now are eligible`() {
        val result = BillboardSelection.selectBillboards(
            promotions = listOf(
                promo("past", end = now - 1),
                promo("future", start = now + 1),
                promo("live", start = now - 10, end = now + 10),
                promo("always")
            ),
            nowMillis = now
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.id == "live" })
        assertTrue(result.any { it.id == "always" })
    }

    @Test
    fun `window boundaries are inclusive`() {
        val exactStart = promo("start", start = now, end = now + 5)
        val exactEnd = promo("end", start = now - 5, end = now)
        val result = BillboardSelection.selectBillboards(listOf(exactStart, exactEnd), now)
        assertEquals(2, result.size)
    }

    @Test
    fun `an empty or fully expired pool returns nothing`() {
        assertTrue(BillboardSelection.selectBillboards(emptyList(), now).isEmpty())
        assertTrue(
            BillboardSelection.selectBillboards(listOf(promo("old", end = now - 1)), now).isEmpty()
        )
    }

    // --- ordering ------------------------------------------------------------

    @Test
    fun `higher priority weight leads`() {
        val result = BillboardSelection.selectBillboards(
            promotions = listOf(promo("low", weight = 1), promo("high", weight = 9), promo("mid", weight = 5)),
            nowMillis = now
        )
        assertEquals(listOf("high", "mid", "low"), result.map { it.id })
    }

    @Test
    fun `ties break on id so the order is deterministic`() {
        val forward = BillboardSelection.selectBillboards(
            listOf(promo("b"), promo("a"), promo("c")), now
        )
        val reversed = BillboardSelection.selectBillboards(
            listOf(promo("c"), promo("b"), promo("a")), now
        )
        assertEquals(listOf("a", "b", "c"), forward.map { it.id })
        assertEquals(forward.map { it.id }, reversed.map { it.id })
    }

    @Test
    fun `max caps the result`() {
        val pool = (1..10).map { promo("p$it", weight = it) }
        assertEquals(3, BillboardSelection.selectBillboards(pool, now, max = 3).size)
        assertTrue(BillboardSelection.selectBillboards(pool, now, max = 0).isEmpty())
    }

    @Test
    fun `a pinned max-weight slide is not overflowed by the affinity bonus`() {
        val pinned = promo("pinned", weight = Int.MAX_VALUE)
        val liked = promo("liked", weight = 10, category = OfferCategory.SMS)
        val result = BillboardSelection.selectBillboards(
            promotions = listOf(liked, pinned),
            nowMillis = now,
            affinity = mapOf("SMS" to 100)
        )
        assertEquals("pinned", result.first().id)
    }

    // --- personalization seam ------------------------------------------------

    @Test
    fun `category affinity nudges a matching slide ahead of a close rival`() {
        val sms = promo("sms", weight = 0, category = OfferCategory.SMS)
        val data = promo("data", weight = 500, category = OfferCategory.DATA)
        val neutral = BillboardSelection.selectBillboards(listOf(sms, data), now)
        assertEquals(listOf("data", "sms"), neutral.map { it.id })

        val personalised = BillboardSelection.selectBillboards(
            promotions = listOf(sms, data),
            nowMillis = now,
            affinity = mapOf("SMS" to 100)
        )
        assertEquals(listOf("sms", "data"), personalised.map { it.id })
    }

    @Test
    fun `affinity is a nudge and cannot beat a much stronger priority`() {
        val sms = promo("sms", weight = 0, category = OfferCategory.SMS)
        val data = promo("data", weight = 5_000, category = OfferCategory.DATA)
        val result = BillboardSelection.selectBillboards(
            promotions = listOf(sms, data),
            nowMillis = now,
            affinity = mapOf("SMS" to 100)
        )
        assertEquals(listOf("data", "sms"), result.map { it.id })
    }

    @Test
    fun `affinity keys are case-insensitive and unknown keys are ignored`() {
        assertEquals(BillboardSelection.MAX_AFFINITY_BONUS, BillboardSelection.affinityBonus("SMS", mapOf("sms" to 100)))
        assertEquals(0L, BillboardSelection.affinityBonus("SMS", mapOf("data" to 100)))
        assertEquals(0L, BillboardSelection.affinityBonus(null, mapOf("sms" to 100)))
        assertEquals(0L, BillboardSelection.affinityBonus("SMS", emptyMap()))
    }

    @Test
    fun `affinity values are clamped to the 0 to 100 band`() {
        assertEquals(BillboardSelection.MAX_AFFINITY_BONUS, BillboardSelection.affinityBonus("SMS", mapOf("SMS" to 4_000)))
        assertEquals(0L, BillboardSelection.affinityBonus("SMS", mapOf("SMS" to -50)))
        assertEquals(
            BillboardSelection.MAX_AFFINITY_BONUS / 2,
            BillboardSelection.affinityBonus("SMS", mapOf("SMS" to 50))
        )
    }

    // --- generic seam --------------------------------------------------------

    @Test
    fun `the generic overload works on any type`() {
        data class Slide(val key: String, val from: Long, val to: Long, val rank: Int)

        val result = BillboardSelection.select(
            items = listOf(
                Slide("a", 0L, Long.MAX_VALUE, 1),
                Slide("b", 0L, now - 1, 9),
                Slide("c", 0L, Long.MAX_VALUE, 5)
            ),
            nowMillis = now,
            id = { it.key },
            startMillis = { it.from },
            endMillis = { it.to },
            weight = { it.rank }
        )
        assertEquals(listOf("c", "a"), result.map { it.key })
    }
}
