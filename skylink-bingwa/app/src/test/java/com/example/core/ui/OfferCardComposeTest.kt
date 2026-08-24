package com.example.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.core.model.DailyRule
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.ui.theme.SkylinkBingwaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose behaviour for the (classic) [OfferCard]: a compact card WITH a Buy
 * button and few details. Robolectric pinned to SDK 34 (4.16.1 has no SDK 36).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfferCardComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun offer(id: String = "off_x", boughtToday: Boolean = false): OfferItem = OfferItem(
        id = id,
        name = "1GB",
        allowance = "1GB",
        priceKsh = 19,
        validity = "1 Hr",
        category = OfferCategory.DATA,
        dailyRule = DailyRule.ONCE_PER_DAY,
        isBoughtToday = boughtToday
    )

    @Test
    fun `card shows the buy button and click invokes onBuyClick`() {
        var bought = false
        composeRule.setContent {
            SkylinkBingwaTheme {
                OfferCard(offer = offer(), onCardClick = {}, onBuyClick = { bought = true }, onFavouriteToggle = {})
            }
        }
        composeRule.onNodeWithTag("buy_button_off_x").assertExists()
        composeRule.onNodeWithTag("buy_button_off_x").performClick()
        assertTrue(bought)
    }

    @Test
    fun `favourite button click invokes callback`() {
        var toggled = false
        composeRule.setContent {
            SkylinkBingwaTheme {
                OfferCard(offer = offer(), onCardClick = {}, onBuyClick = {}, onFavouriteToggle = { toggled = true })
            }
        }
        composeRule.onNodeWithTag("favourite_button_off_x").performClick()
        assertTrue(toggled)
    }

    @Test
    fun `card click invokes onCardClick`() {
        var clicked = false
        composeRule.setContent {
            SkylinkBingwaTheme {
                OfferCard(offer = offer(), onCardClick = { clicked = true }, onBuyClick = {}, onFavouriteToggle = {})
            }
        }
        composeRule.onNodeWithTag("offer_card_off_x").performClick()
        assertTrue(clicked)
    }

    @Test
    fun `bought today hides the buy button and shows the bought-today label`() {
        composeRule.setContent {
            SkylinkBingwaTheme {
                OfferCard(offer = offer(boughtToday = true), onCardClick = {}, onBuyClick = {}, onFavouriteToggle = {})
            }
        }
        composeRule.onNodeWithTag("buy_button_off_x").assertDoesNotExist()
        composeRule.onNodeWithText("Bought today", useUnmergedTree = true).assertIsDisplayed()
    }
}
