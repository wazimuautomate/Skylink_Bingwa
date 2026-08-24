package com.example.core.model

// Offer categories carry only semantic identity (label + canonical Material
// Symbol name). Visual colours are resolved from the active theme via
// com.example.ui.theme.categoryColors(...) so light and dark both stay on
// design.md §7.3 — colours are never baked into the model (that previously
// forced light-theme category chips into dark mode).
enum class OfferCategory(
    val label: String,
    val iconName: String
) {
    ALL("All offers", "grid_view"),
    DATA("Data", "signal_cellular_alt"),
    SMS("SMS", "chat_bubble_outline"),
    MINUTES("Minutes", "call"),
    SPECIAL("Special", "auto_awesome"),
    FAVOURITES("Favourites", "favorite")
}
