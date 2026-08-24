package com.example.feature.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.example.core.media.BillboardImageLoader
import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionClickAction
import com.example.core.model.PromotionKind
import com.example.ui.theme.FieldButtonShape
import com.example.ui.theme.InfoActionBlue
import com.example.ui.theme.SkylinkBingwaTheme
import com.example.ui.theme.PrimaryActionGreen
import com.example.ui.theme.PromotionOrange
import com.example.ui.theme.PromotionStatusShape
import com.example.ui.theme.TagShape

/**
 * The Home "advert billboard" — a manually swipeable carousel of promotion
 * slides that behaves like a small television for the seller's biggest offers,
 * announcements and app updates. It replaces the old gradient banner.
 *
 * Design rules honoured here:
 * - No gradients / Brush: every slide is a single solid brand colour
 *   ([slidePalette]) or a photograph under a FLAT translucent scrim. design.md
 *   §7.6 forbids gradients outright, including as an image scrim.
 * - No auto-rotation: swiping is manual only. design.md forbids auto-rotating
 *   carousels, so there is no timer / LaunchedEffect that changes pages.
 * - Reduced motion: the CTA "breathing" pulse and the media entrance fade are
 *   suppressed when [reducedMotion] is true (the end state is shown
 *   immediately); the per-selection dot transition is a short, allowed state
 *   change.
 * - Contrast: orange slides never place white text on orange — they use navy
 *   content and a navy CTA. The media palette is intentionally constant across
 *   light and dark; a billboard is its own vivid surface.
 * - One dominant CTA per slide. Tapping the slide body (only when the promotion
 *   declares a click action) performs the SAME action as the CTA — it is not a
 *   second, competing call to action.
 *
 * Remote artwork (still image or animated GIF) is drawn behind the text using
 * the shared [BillboardImageLoader], whose on-disk cache means a slide fetched
 * once keeps rendering with no internet. While the artwork loads — and forever,
 * if it fails or the device has never been online — the slide shows its solid
 * brand colour with the same text and CTA: never a blank space, never an error
 * dialog, never a crash.
 *
 * If [promotions] is empty this emits nothing.
 *
 * @param imageLoader override for the shared billboard loader (tests only).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PromotionBillboard(
    promotions: List<Promotion>,
    reducedMotion: Boolean,
    onPromotionAction: (Promotion) -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null
) {
    // Empty pool: render no layout at all.
    if (promotions.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { promotions.size })

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("promotion_billboard"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Manual-swipe carousel. No auto-advance and no looping (design.md).
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 0.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            PromotionSlide(
                promotion = promotions[page],
                reducedMotion = reducedMotion,
                onPromotionAction = onPromotionAction,
                imageLoader = imageLoader
            )
        }

        // Page indicators — only meaningful when there is more than one slide.
        if (promotions.size > 1) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(promotions.size) { index ->
                    PageIndicatorDot(selected = index == pagerState.currentPage)
                    if (index != promotions.lastIndex) {
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
            }
        }
    }
}

/** What the slide's remote artwork is currently doing. */
private enum class MediaPhase { LOADING, SHOWN, FAILED }

/**
 * A single billboard slide: solid brand background (optionally covered by remote
 * artwork under a flat scrim), start-aligned tag + headline + subhead, and a
 * breathing CTA.
 */
@Composable
private fun PromotionSlide(
    promotion: Promotion,
    reducedMotion: Boolean,
    onPromotionAction: (Promotion) -> Unit,
    imageLoader: ImageLoader?
) {
    val context = LocalContext.current
    val basePalette = slidePalette(promotion.accent)

    // Media state is keyed to the slide identity AND its cache-busting version, so
    // a republished image restarts cleanly instead of inheriting a stale phase.
    var phase by remember(promotion.id, promotion.mediaUrl, promotion.mediaVersion) {
        mutableStateOf(if (promotion.hasRemoteMedia) MediaPhase.LOADING else MediaPhase.FAILED)
    }
    val mediaShown = promotion.hasRemoteMedia && phase == MediaPhase.SHOWN

    // Entrance for the artwork: a short fade inside design.md's 220–260ms band.
    // Reduced motion jumps straight to the end state.
    val animatedAlpha by animateFloatAsState(
        targetValue = if (mediaShown) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "billboard_media_alpha"
    )
    val mediaAlpha = if (reducedMotion) {
        if (mediaShown) 1f else 0f
    } else {
        animatedAlpha
    }

    // Over artwork the text sits on a dark scrim, so the content switches to white
    // for every accent — including orange, whose navy content would disappear.
    // The CTA keeps its own guaranteed-contrast colours.
    val palette = if (mediaShown) {
        basePalette.copy(
            content = Color.White,
            chipContainer = Color.White.copy(alpha = 0.20f),
            chipContent = Color.White
        )
    } else {
        basePalette
    }

    val hasClickAction = promotion.clickActionOrNone != PromotionClickAction.NONE
    val clickModifier = if (hasClickAction) {
        Modifier.clickable(
            onClickLabel = promotion.ctaLabel.takeIf { it.isNotBlank() } ?: "Open promotion"
        ) { onPromotionAction(promotion) }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // A minimum (not fixed) height keeps the classic billboard proportions
            // but lets the slide grow instead of clipping at large font scales.
            .heightIn(min = 190.dp)
            .clip(PromotionStatusShape)
            .background(basePalette.background)
            .then(clickModifier)
            .testTag("promotion_slide_${promotion.id}")
    ) {
        // Optional bundled artwork sits behind the content. A solid dark scrim
        // (never a gradient) keeps the text legible over any image.
        if (promotion.imageRes != null) {
            Image(
                painter = painterResource(promotion.imageRes!!),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x66000000))
            )
        }

        // Remote artwork (image or animated GIF). Drawn only once decoded; a
        // failure simply leaves the solid coloured slide in place, so the customer
        // always sees a complete, readable billboard.
        if (promotion.hasRemoteMedia) {
            val loader = imageLoader ?: remember(context) { BillboardImageLoader.get(context) }
            val request = remember(context, promotion.mediaUrl, promotion.mediaVersion) {
                BillboardImageLoader.requestFor(context, promotion.mediaUrl, promotion.mediaVersion)
            }
            AsyncImage(
                model = request,
                // Real alternative text: the admin's alt text, else the headline.
                contentDescription = promotion.mediaAltText.takeIf { it.isNotBlank() }
                    ?: promotion.headline,
                imageLoader = loader,
                onState = { state ->
                    phase = when (state) {
                        is AsyncImagePainter.State.Success -> MediaPhase.SHOWN
                        is AsyncImagePainter.State.Error -> MediaPhase.FAILED
                        else -> MediaPhase.LOADING
                    }
                },
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = mediaAlpha }
                    .testTag("promotion_media_${promotion.id}")
            )
            if (mediaShown) {
                // FLAT translucent scrim from the theme — not a gradient (design.md).
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = mediaAlpha }
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                )
            }
        }

        // The text and the CTA are real siblings in a Row, so each reserves its
        // own space. The text column takes the remaining width via weight(1f) and
        // wraps/truncates BEFORE the button — it can never run underneath the CTA.
        // The Row's minimum height matches the slide so its contents stay centred.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 190.dp)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Start-aligned text block. weight(1f) makes it yield space to the CTA.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                // Tag chip.
                if (promotion.tag.isNotBlank()) {
                    Surface(
                        color = palette.chipContainer,
                        shape = TagShape
                    ) {
                        Text(
                            text = promotion.tag,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.chipContent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }

                Text(
                    text = promotion.headline,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("promotion_headline_${promotion.id}")
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = promotion.subhead,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.content.copy(alpha = 0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Clear gap between the text and the CTA so they never touch or collide.
            Spacer(modifier = Modifier.width(16.dp))

            // CTA on the right, vertically centred by the Row. It occupies its own
            // reserved column — nothing is drawn on top of the text.
            BreathingCtaButton(
                label = promotion.ctaLabel,
                palette = palette,
                reducedMotion = reducedMotion,
                onClick = { onPromotionAction(promotion) },
                modifier = Modifier.testTag("promotion_cta_${promotion.id}")
            )
        }
    }
}

/**
 * The CTA button. It gently "breathes" (scale 1f..1.03f) to draw attention,
 * unless [reducedMotion] is true, in which case the scale stays a constant 1f.
 *
 * Colours are chosen so the button always stands out against the slide: on the
 * white-text slides (green/navy/blue) a white-filled button uses the slide
 * background as its label colour; on orange a navy-filled button uses white.
 */
@Composable
private fun BreathingCtaButton(
    label: String,
    palette: SlidePalette,
    reducedMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Only run the infinite animation when motion is allowed; otherwise pin the
    // scale so nothing animates for reduced-motion users.
    val scale = if (reducedMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "cta_breathing")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cta_scale"
        ).value
    }

    Button(
        onClick = onClick,
        shape = FieldButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.ctaContainer,
            contentColor = palette.ctaContent
        ),
        // A taller button with generous vertical content padding keeps the label
        // fully visible (the descenders were being clipped before).
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier
            .height(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A single page-indicator dot; the selected dot is wider and fully coloured. */
@Composable
private fun PageIndicatorDot(selected: Boolean) {
    // Short per-selection transition (allowed state change, ~180ms).
    val width: Dp by animateDpAsState(
        targetValue = if (selected) 20.dp else 6.dp,
        animationSpec = tween(durationMillis = 180),
        label = "dot_width"
    )
    val alpha: Float by animateFloatAsState(
        targetValue = if (selected) 1f else 0.4f,
        animationSpec = tween(durationMillis = 180),
        label = "dot_alpha"
    )
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .height(6.dp)
            .width(width)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * Resolved solid colours for one slide. There is no [androidx.compose.ui.graphics.Brush]
 * anywhere — every value is a flat brand colour.
 */
private data class SlidePalette(
    val background: Color,
    val content: Color,
    val chipContainer: Color,
    val chipContent: Color,
    val ctaContainer: Color,
    val ctaContent: Color
)

// Navy brand colour used for the orange slide's content and CTA (never white
// text on orange — design.md §7.4).
private val NavyContent = Color(0xFF0A2540)

/**
 * Maps a [PromotionAccent] to its solid slide palette. These media colours are
 * intentionally identical in light and dark themes.
 */
private fun slidePalette(accent: PromotionAccent): SlidePalette = when (accent) {
    PromotionAccent.GREEN -> SlidePalette(
        background = PrimaryActionGreen,
        content = Color.White,
        chipContainer = Color.White.copy(alpha = 0.18f),
        chipContent = Color.White,
        // White CTA reads against the green field; label uses the slide colour.
        ctaContainer = Color.White,
        ctaContent = PrimaryActionGreen
    )
    PromotionAccent.NAVY -> SlidePalette(
        background = NavyContent,
        content = Color.White,
        chipContainer = Color.White.copy(alpha = 0.15f),
        chipContent = Color.White,
        ctaContainer = Color.White,
        ctaContent = NavyContent
    )
    PromotionAccent.BLUE -> SlidePalette(
        background = InfoActionBlue,
        content = Color.White,
        chipContainer = Color.White.copy(alpha = 0.18f),
        chipContent = Color.White,
        ctaContainer = Color.White,
        ctaContent = InfoActionBlue
    )
    PromotionAccent.ORANGE -> SlidePalette(
        background = PromotionOrange,
        content = NavyContent,
        chipContainer = NavyContent.copy(alpha = 0.12f),
        chipContent = NavyContent,
        // Navy-filled button with white text stays high-contrast on orange.
        ctaContainer = NavyContent,
        ctaContent = Color.White
    )
}

@Preview(showBackground = true)
@Composable
private fun PromotionBillboardPreview() {
    SkylinkBingwaTheme {
        PromotionBillboard(
            promotions = listOf(
                Promotion(
                    id = "p1",
                    kind = PromotionKind.OFFER,
                    tag = "HOT DEAL",
                    headline = "2 GB for KSh 110",
                    subhead = "Valid for 24 hours. Tap to buy for any number.",
                    ctaLabel = "Buy now",
                    accent = PromotionAccent.GREEN
                ),
                Promotion(
                    id = "p2",
                    kind = PromotionKind.ANNOUNCEMENT,
                    tag = "NEW",
                    headline = "Weekend minutes bundle",
                    subhead = "400 minutes to all networks, only on weekends.",
                    ctaLabel = "See offers",
                    accent = PromotionAccent.BLUE
                ),
                Promotion(
                    id = "p3",
                    kind = PromotionKind.UPDATE,
                    tag = "OFFER",
                    headline = "Data midnight special",
                    subhead = "Extra data every night from 12am to 6am.",
                    ctaLabel = "Learn more",
                    accent = PromotionAccent.ORANGE
                )
            ),
            reducedMotion = false,
            onPromotionAction = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
