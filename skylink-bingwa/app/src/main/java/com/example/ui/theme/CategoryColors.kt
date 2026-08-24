package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.example.core.model.OfferCategory

/**
 * Resolved, theme-aware colours for a category chip / icon container.
 *
 * - [accent] tints the category icon.
 * - [container] fills the icon container or chip background.
 * - [onContainer] colours text/icons drawn on [container].
 */
data class CategoryColors(
    val accent: Color,
    val container: Color,
    val onContainer: Color
)

/**
 * Theme-aware category colours (design.md §7.3). Light uses the documented
 * accent/container/content; dark uses a faint accent-tinted container with a
 * light-tint on-container so nothing carries a baked light surface into dark.
 */
@Composable
@ReadOnlyComposable
fun categoryColors(category: OfferCategory): CategoryColors {
    val dark = isSystemInDarkTheme()
    return when (category) {
        OfferCategory.DATA, OfferCategory.ALL ->
            build(DataCategoryBlue, DataCategoryContainer, LightPrimaryText, DataCategoryOnDark, dark)
        OfferCategory.MINUTES ->
            build(MinutesCategoryGreen, MinutesCategoryContainer, LightPrimaryText, MinutesCategoryOnDark, dark)
        OfferCategory.SMS ->
            build(SmsCategoryPurple, SmsCategoryContainer, SmsCategoryDarkContent, SmsCategoryOnDark, dark)
        OfferCategory.SPECIAL, OfferCategory.FAVOURITES ->
            build(SpecialCategoryOrange, SpecialCategoryContainer, SpecialCategoryDarkContent, SpecialCategoryOnDark, dark)
    }
}

private fun build(
    accent: Color,
    lightContainer: Color,
    lightOnContainer: Color,
    darkOnContainer: Color,
    dark: Boolean
): CategoryColors = if (dark) {
    CategoryColors(
        accent = accent,
        container = accent.copy(alpha = 0.20f),
        onContainer = darkOnContainer
    )
} else {
    CategoryColors(
        accent = accent,
        container = lightContainer,
        onContainer = lightOnContainer
    )
}
