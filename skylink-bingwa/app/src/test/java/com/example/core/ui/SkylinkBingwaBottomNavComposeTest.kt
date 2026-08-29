package com.example.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.ui.theme.SkylinkBingwaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Referrals is now a primary bottom-navigation destination (owner request), so
 * it must render and route like every other tab. Settings moved to the header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkylinkBingwaBottomNavComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `referrals destination renders in the bottom navigation`() {
        composeRule.setContent {
            SkylinkBingwaTheme {
                SkylinkBingwaBottomNav(currentRoute = "home", onNavigate = {})
            }
        }
        composeRule.onNodeWithTag("nav_item_referrals").assertIsDisplayed()
    }

    @Test
    fun `tapping referrals routes to the referrals destination`() {
        var routed: String? = null
        composeRule.setContent {
            SkylinkBingwaTheme {
                SkylinkBingwaBottomNav(currentRoute = "home", onNavigate = { routed = it.route })
            }
        }
        composeRule.onNodeWithTag("nav_item_referrals").performClick()
        assertEquals("referrals", routed)
    }
}
