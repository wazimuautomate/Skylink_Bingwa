package com.example.core.media

import com.example.core.model.Promotion

/**
 * PURE billboard selection: which slides are eligible right now and in what
 * order. Deliberately free of Android and Compose imports so it runs as a plain
 * host-JVM unit test.
 *
 * Three rules, in priority order:
 *
 * 1. **Active window.** A slide is eligible only while
 *    `startMillis <= now <= endMillis`. An uncapped window (0 .. Long.MAX_VALUE)
 *    is always active. Scheduling is therefore enforced on the device, using the
 *    server-supplied window, and keeps working with no internet.
 * 2. **Priority.** Higher weight leads. This is the admin's published ordering
 *    (see `BillboardDto.toPromotion`, which negates the server's ascending
 *    `priority` into a descending weight).
 * 3. **Category affinity (the personalization seam).** An optional per-category
 *    hint nudges — never dictates — the order, so a customer who mostly buys SMS
 *    bundles sees SMS promotions sooner. The hint is produced LOCALLY (the
 *    server only supplies the category metadata); this function never phones
 *    home and never sees a customer's history. Another agent owns the engine
 *    that computes the hint; this is only the seam it plugs into.
 *
 * Ties break on the slide id so the result is deterministic — the same inputs
 * always produce the same order, which is what makes it testable and what stops
 * the billboard from reshuffling on every recomposition.
 */
object BillboardSelection {

    /** Largest bonus a perfect affinity match may add to a slide's weight. */
    const val MAX_AFFINITY_BONUS = 1_000L

    /**
     * Generic selection over anything that can expose the four scheduling
     * fields. Property accessors are passed in, so this file stays free of any
     * model, Android or Compose dependency.
     *
     * @param affinity local per-category hint, keyed by the same value
     *   [categoryKey] returns. Values are clamped to 0..100 (a percentage of
     *   [MAX_AFFINITY_BONUS]); unknown or missing keys contribute nothing.
     */
    fun <T> select(
        items: List<T>,
        nowMillis: Long,
        max: Int = 5,
        affinity: Map<String, Int> = emptyMap(),
        id: (T) -> String,
        startMillis: (T) -> Long,
        endMillis: (T) -> Long,
        weight: (T) -> Int,
        categoryKey: (T) -> String? = { null }
    ): List<T> {
        if (max <= 0) return emptyList()
        val active = items.filter { item ->
            val start = startMillis(item)
            val end = endMillis(item)
            start <= nowMillis && nowMillis <= end
        }
        if (active.isEmpty()) return emptyList()

        // Long arithmetic on purpose: a pinned slide may carry Int.MAX_VALUE
        // (see UpdatePromotion), and adding a bonus in Int would overflow to a
        // negative score and bury it at the bottom.
        fun score(item: T): Long =
            weight(item).toLong() + affinityBonus(categoryKey(item), affinity)

        return active
            .sortedWith(
                compareByDescending<T> { score(it) }.thenBy { id(it) }
            )
            .take(max)
    }

    /**
     * Convenience for the app's own model. Same rules; [Promotion.linkedCategory]
     * supplies the affinity key (e.g. "SMS", "DATA").
     */
    fun selectBillboards(
        promotions: List<Promotion>,
        nowMillis: Long,
        max: Int = 5,
        affinity: Map<String, Int> = emptyMap()
    ): List<Promotion> = select(
        items = promotions,
        nowMillis = nowMillis,
        max = max,
        affinity = affinity,
        id = { it.id },
        startMillis = { it.startMillis },
        endMillis = { it.endMillis },
        weight = { it.priorityWeight },
        categoryKey = { it.linkedCategory?.name }
    )

    /**
     * 0 when there is no key or no hint; otherwise a 0..[MAX_AFFINITY_BONUS]
     * nudge derived from a 0..100 affinity percentage. Lookup is case-insensitive
     * so an "sms" hint matches a "SMS" category.
     */
    internal fun affinityBonus(key: String?, affinity: Map<String, Int>): Long {
        if (key == null || affinity.isEmpty()) return 0L
        val raw = affinity[key]
            ?: affinity[key.uppercase()]
            ?: affinity.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            ?: return 0L
        val clamped = raw.coerceIn(0, 100)
        return MAX_AFFINITY_BONUS * clamped / 100L
    }
}
