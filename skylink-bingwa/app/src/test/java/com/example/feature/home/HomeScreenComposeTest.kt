package com.example.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.example.core.model.DailyRule
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionKind
import com.example.ui.theme.SkylinkBingwaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose behaviour for the simplified [HomeScreen]: greeting + category tiles +
 * billboard + Your favourites; NO search bar and NO Popular/Bought-today/Buy-again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun offer(id: String, favourite: Boolean): OfferItem = OfferItem(
        id = id,
        name = "1GB",
        allowance = "1GB",
        priceKsh = 50,
        validity = "24 Hrs",
        category = OfferCategory.DATA,
        dailyRule = DailyRule.ONCE_PER_DAY,
        isFavourite = favourite
    )

    private val promotion = Promotion(
        id = "p1",
        kind = PromotionKind.OFFER,
        tag = "HOT DEAL",
        headline = "Headline",
        subhead = "Subhead",
        ctaLabel = "Buy now",
        accent = PromotionAccent.GREEN
    )

    private fun state(): HomeUiState = HomeUiState(
        loading = false,
        greetingName = "Bonke",
        promotions = listOf(promotion),
        sections = HomeSections(
            popular = emptyList(),
            boughtToday = emptyList(),
            moreOffers = emptyList(),
            buyAgain = emptyList(),
            favourites = listOf(offer("fav_1", true)),
            suggestions = listOf(offer("sug_1", false))
        ),
        nowMillis = 1_704_877_200_000L
    )

    private fun setHome() {
        composeRule.setContent {
            SkylinkBingwaTheme {
                HomeScreen(
                    state = state(),
                    unreadNotifCount = 0,
                    reducedMotion = true,
                    onCategoryClick = {},
                    onOfferSelect = {},
                    onOfferBuy = {},
                    onFavouriteToggle = {},
                    onUndoFavourite = {},
                    onPromotionAction = {},
                    onNotifClick = {},
                    onOfflineClick = {}
                )
            }
        }
    }

    @Test
    fun `greeting shows the customer name`() {
        setHome()
        composeRule.onNodeWithTag("home_greeting_text").assertIsDisplayed()
        composeRule.onNodeWithTag("home_greeting_text").assertTextContains("Bonke", substring = true)
    }

    @Test
    fun `category shortcut exists and there is no search bar`() {
        setHome()
        composeRule.onNodeWithTag("category_tile_data").assertExists()
        composeRule.onNodeWithTag("home_search_entry").assertDoesNotExist()
    }

    @Test
    fun `favourites section header renders`() {
        setHome()
        composeRule.onNodeWithTag("home_scroll")
            .performScrollToNode(hasTestTag("section_header_Your favourites"))
        composeRule.onNodeWithTag("section_header_Your favourites").assertExists()
    }
}
