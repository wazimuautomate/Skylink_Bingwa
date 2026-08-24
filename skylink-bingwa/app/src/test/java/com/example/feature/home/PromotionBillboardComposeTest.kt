package com.example.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionKind
import com.example.ui.theme.SkylinkBingwaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose behaviour for [PromotionBillboard]. `reducedMotion = true` suppresses
 * the infinite CTA breathing animation so the tests never wait on animation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromotionBillboardComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun promo(id: String): Promotion = Promotion(
        id = id,
        kind = PromotionKind.OFFER,
        tag = "HOT DEAL",
        headline = "Headline $id",
        subhead = "Subhead $id",
        ctaLabel = "Buy now",
        accent = PromotionAccent.GREEN
    )

    @Test
    fun `empty promotions render no billboard`() {
        composeRule.setContent {
            SkylinkBingwaTheme {
                PromotionBillboard(promotions = emptyList(), reducedMotion = true, onPromotionAction = {})
            }
        }
        composeRule.onNodeWithTag("promotion_billboard").assertDoesNotExist()
    }

    @Test
    fun `non-empty billboard shows first slide and cta invokes callback`() {
        val first = promo("p1")
        var actioned: Promotion? = null
        composeRule.setContent {
            SkylinkBingwaTheme {
                PromotionBillboard(
                    promotions = listOf(first, promo("p2")),
                    reducedMotion = true,
                    onPromotionAction = { actioned = it }
                )
            }
        }
        composeRule.onNodeWithTag("promotion_billboard").assertExists()
        composeRule.onNodeWithTag("promotion_slide_p1", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("promotion_cta_p1", useUnmergedTree = true).performClick()
        assertEquals(first, actioned)
    }

    @Test
    fun `a text-only promotion draws no media layer`() {
        composeRule.setContent {
            SkylinkBingwaTheme {
                PromotionBillboard(
                    promotions = listOf(promo("p1")),
                    reducedMotion = true,
                    onPromotionAction = {}
                )
            }
        }
        composeRule.onNodeWithTag("promotion_headline_p1", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("promotion_media_p1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `an image slide whose url cannot load still renders the text fallback`() {
        // Port 9 (discard) on loopback: the fetch fails immediately, with no DNS
        // lookup and no real network — the offline / broken-artwork case. The slide
        // must degrade to the coloured text slide: no blank space, no crash.
        val withMedia = promo("p1").copy(
            mediaUrl = "https://127.0.0.1:9/missing.png",
            mediaType = "IMAGE",
            mediaVersion = "v1",
            clickAction = "OFFER",
            clickTarget = "data_1"
        )
        var actioned: Promotion? = null
        composeRule.setContent {
            SkylinkBingwaTheme {
                PromotionBillboard(
                    promotions = listOf(withMedia),
                    reducedMotion = true,
                    onPromotionAction = { actioned = it }
                )
            }
        }
        composeRule.onNodeWithTag("promotion_slide_p1", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("promotion_headline_p1", useUnmergedTree = true).assertExists()
        // The CTA still works, so the customer is never stranded on a dead slide.
        composeRule.onNodeWithTag("promotion_cta_p1", useUnmergedTree = true).performClick()
        assertEquals(withMedia, actioned)
    }

    @Test
    fun `a gif slide composes its media layer and keeps the text`() {
        val gif = promo("p1").copy(
            mediaUrl = "https://127.0.0.1:9/promo.gif",
            mediaType = "GIF",
            mediaAltText = "Weekend data promo"
        )
        composeRule.setContent {
            SkylinkBingwaTheme {
                PromotionBillboard(
                    promotions = listOf(gif),
                    reducedMotion = true,
                    onPromotionAction = {}
                )
            }
        }
        composeRule.onNodeWithTag("promotion_media_p1", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("promotion_headline_p1", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `reduced motion renders the end state without waiting on animation`() {
        // With reducedMotion the CTA scale is pinned and the media fade is skipped;
        // the tree settles immediately, which is exactly what this assertion proves.
        composeRule.setContent {
            SkylinkBingwaTheme {
                PromotionBillboard(
                    promotions = listOf(
                        promo("p1").copy(mediaUrl = "https://127.0.0.1:9/x.png", mediaType = "IMAGE"),
                        promo("p2")
                    ),
                    reducedMotion = true,
                    onPromotionAction = {}
                )
            }
        }
        composeRule.onNodeWithTag("promotion_billboard").assertExists()
        composeRule.onNodeWithTag("promotion_cta_p1", useUnmergedTree = true).assertExists()
    }
}
