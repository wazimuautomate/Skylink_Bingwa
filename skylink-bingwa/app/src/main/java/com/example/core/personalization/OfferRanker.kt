package com.example.core.personalization

import com.example.core.model.OfferItem
import kotlin.math.pow

/**
 * The quiet, familiar labels personalization is allowed to put on an offer.
 *
 * LANGUAGE RULE — CLAUDE.md §8 (binding, do not relax):
 * these labels may describe **only what this customer bought from Skylink Bingwa**.
 * The app has no visibility of a bundle balance, a consumption figure, or
 * anything the customer does outside this app, so it must never write a label
 * that implies otherwise. Concretely, a new label must never:
 *
 * - use the word "Recommended", or claim to be derived from what the customer
 *   consumes, requires or visits (the four phrases CLAUDE.md §8 forbids
 *   outright are enumerated in `PersonalizationLanguageTest`);
 * - step outside the approved purchase-awareness vocabulary — "Bought today",
 *   "Available again tomorrow", "N purchases left today", "More offers you can
 *   buy";
 * - imply delivery or activation of a bundle (CLAUDE.md §7 — this app neither
 *   delivers nor verifies bundles).
 *
 * `PersonalizationLanguageTest` asserts all of this against every label and
 * against every source file in this package.
 */
enum class PersonalBadge(val label: String) {
    BUY_AGAIN("Buy again"),
    YOUR_USUAL("Your usual bundle"),
    BOUGHT_YESTERDAY("Bought yesterday"),
    FAVOURITE("Favourite")
}

/** An offer with its personal relevance score and at most one quiet badge. */
data class RankedOffer(
    val offer: OfferItem,
    val score: Double,
    val badge: PersonalBadge?
)

/**
 * Re-orders offers by how personally relevant they are to this installation, and
 * decides the small "Buy again" / "Your usual bundle" / "Bought yesterday"
 * labels Home shows.
 *
 * Pure and deterministic: no clock, no randomness, no I/O, nothing uploaded.
 *
 * ### Score
 * ```
 * score = 3.00 * frequency   // times this offer was bought / times the most-bought offer was bought
 *       + 2.00 * recency     // 0.5 ^ (days since this offer was last bought / 7)
 *       + 1.50 * favourite   // 1 when the customer hearted it
 *       + 1.00 * category    // 1 when it is in the category they buy most
 *       + 0.75 * timeFit     // 1 when its validity band is their usual AND it is around their usual hour, else 0.5 when only the band matches
 *       + 0.50 * amount      // 1 when its price equals the amount they spend most
 * ```
 * What the customer actually repeats (frequency + recency) therefore dominates;
 * time-of-day only nudges, so the list never reshuffles dramatically just
 * because the clock moved.
 *
 * ### Stability
 * Ties keep the caller's input order (Kotlin's sort is stable) and nothing here
 * consults a random source, so repeated calls with the same inputs return the
 * identical list. Home therefore does not visibly shuffle between
 * recompositions.
 *
 * ### Fresh install
 * When [BehaviourProfile.isEmpty] the input list is returned untouched with
 * `score = 0.0` and no badges, so Home looks exactly as it did before this
 * feature existed.
 */
object OfferRanker {

    const val WEIGHT_FREQUENCY = 3.0
    const val WEIGHT_RECENCY = 2.0
    const val WEIGHT_FAVOURITE = 1.5
    const val WEIGHT_CATEGORY = 1.0
    const val WEIGHT_TIME_FIT = 0.75
    const val WEIGHT_AMOUNT = 0.5

    /** Days after which "recently bought" counts half as much. */
    const val RECENCY_HALF_LIFE_DAYS = 7.0

    /** Hours either side of the usual purchase hour that still count as "their time". */
    const val USUAL_HOUR_WINDOW = 2

    /** Times an offer must have been bought before it can be called their usual bundle. */
    const val MIN_BUYS_FOR_USUAL = 3

    /** Keep the surface calm: only a couple of badges per rendered list. */
    const val DEFAULT_MAX_BADGES = 2

    fun rank(
        offers: List<OfferItem>,
        profile: BehaviourProfile,
        nowMillis: Long,
        maxBadges: Int = DEFAULT_MAX_BADGES
    ): List<RankedOffer> {
        if (profile.isEmpty() || offers.isEmpty()) {
            return offers.map { RankedOffer(it, 0.0, null) }
        }

        val maxCount = profile.purchaseCountByOfferId.values.maxOrNull() ?: 0
        val nowHour = NairobiTime.hourOfDay(nowMillis)
        val inUsualWindow = profile.usualPurchaseHour in 0..23 &&
            NairobiTime.hourDistance(nowHour, profile.usualPurchaseHour) <= USUAL_HOUR_WINDOW

        val ordered = offers
            .map { offer -> offer to scoreOf(offer, profile, nowMillis, maxCount, inUsualWindow) }
            .sortedWith(compareByDescending<Pair<OfferItem, Double>> { it.second })

        var awarded = 0
        return ordered.map { (offer, score) ->
            val badge = if (awarded < maxBadges) badgeFor(offer, profile, nowMillis) else null
            if (badge != null) awarded++
            RankedOffer(offer, score, badge)
        }
    }

    private fun scoreOf(
        offer: OfferItem,
        profile: BehaviourProfile,
        nowMillis: Long,
        maxCount: Int,
        inUsualWindow: Boolean
    ): Double {
        val count = profile.purchaseCountByOfferId[offer.id] ?: 0
        val frequency = if (maxCount > 0) count.toDouble() / maxCount.toDouble() else 0.0

        val lastAt = profile.lastPurchaseAtMillisByOfferId[offer.id] ?: 0L
        val recency = if (lastAt <= 0L) {
            0.0
        } else {
            val ageDays = ((nowMillis - lastAt).toDouble() / NairobiTime.DAY_MILLIS_D)
                .coerceAtLeast(0.0)
            0.5.pow(ageDays / RECENCY_HALF_LIFE_DAYS)
        }

        val favourite = if (offer.isFavourite) 1.0 else 0.0
        val category = if (profile.favouriteCategory != null && offer.category == profile.favouriteCategory) 1.0 else 0.0

        val bandMatches = profile.preferredValidity.isNotEmpty() &&
            PersonalizationEngine.bandOf(offer) == profile.preferredValidity
        val timeFit = when {
            bandMatches && inUsualWindow -> 1.0
            bandMatches -> 0.5
            else -> 0.0
        }

        val amount = if (profile.mostPurchasedAmountKsh > 0 && offer.priceKsh == profile.mostPurchasedAmountKsh) 1.0 else 0.0

        return WEIGHT_FREQUENCY * frequency +
            WEIGHT_RECENCY * recency +
            WEIGHT_FAVOURITE * favourite +
            WEIGHT_CATEGORY * category +
            WEIGHT_TIME_FIT * timeFit +
            WEIGHT_AMOUNT * amount
    }

    /**
     * At most one badge per offer, in this priority:
     * their usual bundle → bought on the previous Nairobi day → bought before →
     * hearted. Returns null when the offer means nothing personal yet.
     */
    fun badgeFor(offer: OfferItem, profile: BehaviourProfile, nowMillis: Long): PersonalBadge? {
        if (profile.isEmpty()) return null
        val count = profile.purchaseCountByOfferId[offer.id] ?: 0
        val lastAt = profile.lastPurchaseAtMillisByOfferId[offer.id] ?: 0L
        val boughtYesterday = lastAt > 0L && NairobiTime.daysBetween(lastAt, nowMillis) == 1L
        return when {
            count >= MIN_BUYS_FOR_USUAL && offer.id == profile.mostPurchasedOfferId -> PersonalBadge.YOUR_USUAL
            boughtYesterday -> PersonalBadge.BOUGHT_YESTERDAY
            count > 0 -> PersonalBadge.BUY_AGAIN
            offer.isFavourite -> PersonalBadge.FAVOURITE
            else -> null
        }
    }
}
