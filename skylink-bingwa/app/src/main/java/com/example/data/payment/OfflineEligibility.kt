package com.example.data.payment

import com.example.core.model.OfferItem
import com.example.core.model.PurchasePolicy
import com.example.core.model.offerAvailabilityAt

/**
 * Whether a given offer may be purchased offline right now, and why not when it
 * cannot. Purely a function of the offer, the chosen route, the catalogue and the
 * cached config — no Android or time-of-day surprises (Plan.md §5.8, §5.12).
 */
sealed interface OfflineEligibility {
    data class Eligible(val route: PaymentRoute) : OfflineEligibility

    /** The cached Till/Paybill config or this offer's offline price has expired. */
    data object Expired : OfflineEligibility

    /** No usable/verified offline config — internet is needed to refresh it. */
    data object ConfigUnavailable : OfflineEligibility

    /**
     * A hard once-per-recipient-per-day offer is disabled for offline payment by
     * default, because an offline duplicate cannot be prevented safely (Plan.md §5.12).
     */
    data object HardLimitBlocked : OfflineEligibility

    /**
     * Another offline-payable offer shares this exact amount on the same route, so
     * fulfilment could not tell which offer was paid for (Plan.md §5.8).
     */
    data object AmbiguousAmount : OfflineEligibility

    /**
     * The offer is outside the time-of-day window Safaricom sells it in. Paying
     * offline now would take the customer's money for a bundle that cannot be
     * fulfilled, so the manual steps are withheld until the window opens.
     */
    data class OutsideSellingWindow(val windowLabel: String) : OfflineEligibility

    val isEligible: Boolean get() = this is Eligible
}

object OfflineEligibilityChecker {

    /**
     * @param offer      the offer being purchased.
     * @param isForSelf  true → Till route (own number); false → Paybill route.
     * @param catalogue  all catalogue offers, used for the amount-uniqueness check.
     * @param config     the loaded offline configuration.
     * @param nowMillis  current time for expiry evaluation.
     */
    fun check(
        offer: OfferItem,
        isForSelf: Boolean,
        catalogue: List<OfferItem>,
        config: OfflineConfigResult,
        nowMillis: Long
    ): OfflineEligibility {
        // 1. Trust the config first.
        when (config) {
            is OfflineConfigResult.Expired -> return OfflineEligibility.Expired
            is OfflineConfigResult.Missing,
            is OfflineConfigResult.InvalidSignature -> return OfflineEligibility.ConfigUnavailable
            is OfflineConfigResult.Valid -> Unit // continue
        }

        // 2. This offer's own cached offline price may have expired independently.
        if (offer.offlineInstructionsExpired) return OfflineEligibility.Expired

        // 3. Hard once-per-recipient-per-day offers are offline-disabled by default.
        if (isHardLimited(offer)) return OfflineEligibility.HardLimitBlocked

        // 4. Outside its selling window nothing can be fulfilled, online or off.
        val availability = offerAvailabilityAt(offer, nowMillis)
        if (!availability.purchasable) {
            return OfflineEligibility.OutsideSellingWindow(availability.windowLabel)
        }

        // 5. Amount must uniquely identify the offer among other offline-payable
        //    offers on the same route. Since the payable amount is identical on
        //    either route, a shared price with any other offline-payable offer is
        //    ambiguous.
        val collides = catalogue.any { other ->
            other.id != offer.id &&
                other.priceKsh == offer.priceKsh &&
                isOfflinePayable(other)
        }
        if (collides) return OfflineEligibility.AmbiguousAmount

        val route = if (isForSelf) PaymentRoute.TILL_SELF else PaymentRoute.PAYBILL_OTHER
        return OfflineEligibility.Eligible(route)
    }

    /** An offer that itself can be paid offline (repeatable and not expired). */
    private fun isOfflinePayable(offer: OfferItem): Boolean =
        !isHardLimited(offer) && !offer.offlineInstructionsExpired

    private fun isHardLimited(offer: OfferItem): Boolean =
        offer.purchasePolicy == PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY ||
            offer.purchasePolicy == PurchasePolicy.MAX_PER_RECIPIENT_PER_DAY
}
