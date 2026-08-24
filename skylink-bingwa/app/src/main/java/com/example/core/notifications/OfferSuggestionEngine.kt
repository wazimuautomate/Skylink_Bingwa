package com.example.core.notifications

import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.PaymentStatus
import com.example.core.model.PurchaseRecord
import java.util.Calendar
import java.util.TimeZone

/**
 * PURE, deterministic bundle-suggestion logic (no Android, no clocks read
 * internally — the caller passes `nowMillis`). Returns a short, restrained list
 * of offers the app *may* invite the customer to top up with.
 *
 * IMPORTANT — this is purchase awareness, NOT a data-usage recommender
 * (CLAUDE.md §8). It returns offers only; it never decides copy. Any human
 * strings the integration renders MUST use the allowed language ("More offers
 * you can buy", "Top up with these deals", "Bought today", "Available again
 * tomorrow") and MUST NEVER claim "You are running out of data", "Recommended
 * for your usage", "Based on your browsing" or "You need more data".
 */
object OfferSuggestionEngine {

    /** Keep suggestions calm and non-noisy. */
    const val MAX_SUGGESTIONS = 3

    private val NAIROBI: TimeZone = TimeZone.getTimeZone("Africa/Nairobi")

    /**
     * Choose up to [MAX_SUGGESTIONS] offers to suggest.
     *
     * @param offers      the current catalogue.
     * @param purchases   the customer's local purchase records.
     * @param nowMillis   current epoch millis (defines the Nairobi "today").
     * @param depleted    the category a delivery/low-balance signal implicates,
     *                    or null; offers in this category are preferred.
     * @param connection  current connectivity. Accepted for future refinement;
     *                    offers stay buyable offline (Till/Paybill), so it never
     *                    hides suggestions and does not affect ordering yet.
     *
     * Selection rules:
     *  - Exclude meta categories (ALL/FAVOURITES) and anything bought today
     *    (a RECEIVED purchase on the same Nairobi day).
     *  - Prefer the [depleted] category, then popular offers, then cheaper
     *    top-ups; ties broken by name for stable, testable output.
     */
    fun suggest(
        offers: List<OfferItem>,
        purchases: List<PurchaseRecord>,
        nowMillis: Long,
        depleted: OfferCategory?,
        connection: ConnectionState
    ): List<OfferItem> {
        if (offers.isEmpty()) return emptyList()

        val boughtTodayOfferIds = purchases
            .filter { it.status == PaymentStatus.RECEIVED && isSameNairobiDay(it.timestampMillis, nowMillis) }
            .map { it.offerId }
            .toSet()

        val hourOfDay = nairobiHour(nowMillis)

        val candidates = offers.asSequence()
            .filter { it.category != OfferCategory.ALL && it.category != OfferCategory.FAVOURITES }
            .filter { it.id !in boughtTodayOfferIds }
            .distinctBy { it.id }
            .toList()

        if (candidates.isEmpty()) return emptyList()

        val ordered = candidates.sortedWith(
            compareByDescending<OfferItem> { depleted != null && it.category == depleted }
                .thenByDescending { it.isPopular }
                .thenByDescending { preferredForTime(it, hourOfDay) }
                .thenBy { it.priceKsh }
                .thenBy { it.name }
        )

        return ordered.take(MAX_SUGGESTIONS)
    }

    /**
     * A gentle, honest time-of-day nudge used only as a tie-breaker: late at
     * night (22:00–05:59 Nairobi) shorter/cheaper daily bundles tend to fit
     * better, so give hourly/daily-priced offers a small edge. Never hides or
     * fabricates anything.
     */
    private fun preferredForTime(offer: OfferItem, hourOfDay: Int): Boolean {
        val lateNight = hourOfDay >= 22 || hourOfDay < 6
        if (!lateNight) return false
        val band = offer.validityBand.lowercase()
        return band == "hourly" || band == "daily"
    }

    private fun nairobiHour(millis: Long): Int {
        val cal = Calendar.getInstance(NAIROBI)
        cal.timeInMillis = millis
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    /** True when [a] and [b] fall on the same Africa/Nairobi calendar day. */
    private fun isSameNairobiDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance(NAIROBI).apply { timeInMillis = a }
        val cb = Calendar.getInstance(NAIROBI).apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
