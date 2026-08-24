package com.example.core.model

/**
 * Presentation of an offer's daily purchase state for a specific recipient
 * (Plan.md §5.12 / design.md §13.8). Purely how the state is shown — the state
 * itself is computed in [com.example.feature.home.dailyStateFor] from this
 * installation's Skylink Bingwa payments. This is never a data-usage recommendation.
 */
enum class DailyStateKind { AVAILABLE, BOUGHT_TODAY, AVAILABLE_TOMORROW, PURCHASES_LEFT, WAITING_VERIFY }

data class OfferDailyState(
    val kind: DailyStateKind,
    val label: String,
    val purchasesLeft: Int = 0
) {
    /** True when the offer can still be bought right now for this recipient. */
    val purchasable: Boolean
        get() = kind == DailyStateKind.AVAILABLE || kind == DailyStateKind.PURCHASES_LEFT
}
