package com.example.core.model

enum class DailyRule(val displayText: String) {
    ONCE_PER_DAY("Buy once a day"),
    BUY_AGAIN_TODAY("Buy many times")
}

/**
 * How often an offer may be bought per recipient in a single Nairobi day
 * (Plan.md §5.12). This is purchase awareness, never a data-usage recommender.
 *
 * - [MULTIPLE_PER_DAY]           no per-day cap; always keeps "Buy again".
 * - [ONCE_PER_RECIPIENT_PER_DAY] one purchase per recipient per day.
 * - [MAX_PER_RECIPIENT_PER_DAY]  up to [OfferItem.maxPurchasesPerDay] per
 *                                recipient per day.
 *
 * The canonical per-recipient, Nairobi-day ledger is built in Phase 6; Phase 3
 * only presents the state ([com.example.feature.home.dailyStateFor]).
 */
enum class PurchasePolicy {
    MULTIPLE_PER_DAY,
    ONCE_PER_RECIPIENT_PER_DAY,
    MAX_PER_RECIPIENT_PER_DAY
}

data class OfferItem(
    val id: String,
    val name: String,
    val allowance: String,
    val priceKsh: Int,
    val validity: String,
    // Which validity band the offer belongs to for the Offers filter:
    // "Hourly" | "Daily" | "Weekly" | "Monthly". Stored explicitly (not parsed
    // from the validity string) so filtering is exact. Empty falls back to
    // parsing the validity string.
    val validityBand: String = "",
    val category: OfferCategory,
    val dailyRule: DailyRule,
    val purchasePolicy: PurchasePolicy = when (dailyRule) {
        DailyRule.ONCE_PER_DAY -> PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY
        DailyRule.BUY_AGAIN_TODAY -> PurchasePolicy.MULTIPLE_PER_DAY
    },
    val maxPurchasesPerDay: Int? = null,
    val commercialLabel: String? = null, // e.g. "Best value", "Popular", "Limited offer"
    /**
     * Time-of-day purchase window, as minutes past midnight in Africa/Nairobi
     * (Safaricom restricts some offers to a slot, e.g. 17:00–23:00). Both null
     * means "buyable any time"; see [OfferAvailability] for the semantics and the
     * labels shown on every offer list. Nullable with defaults, so a catalogue
     * cached before this field existed still deserialises.
     */
    val availableFromMinutes: Int? = null,
    val availableToMinutes: Int? = null,
    val isPopular: Boolean = false,
    val isFavourite: Boolean = false,
    val isBoughtToday: Boolean = false,
    val description: String = "",
    val offlineInstructionsExpired: Boolean = false
)
