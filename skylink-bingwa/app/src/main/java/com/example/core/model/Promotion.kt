package com.example.core.model

import androidx.annotation.DrawableRes

/**
 * What a promotion slide is about. Drives the tag copy and the default action:
 *
 * - [OFFER]        promotes a specific buyable offer ([Promotion.linkedOfferId]).
 * - [ANNOUNCEMENT] promotes a filtered offer list or a general message
 *                  ([Promotion.linkedCategory]); the action browses offers.
 * - [UPDATE]       app news / service notice; the action is informational only.
 */
enum class PromotionKind { OFFER, ANNOUNCEMENT, UPDATE }

/**
 * A billboard slide's solid brand palette. design.md forbids gradients, so each
 * slide paints a single saturated brand colour. Palettes are chosen so the
 * content colour never becomes white-on-bright-green / sky-blue / orange
 * (design.md §7.4): the orange slide uses navy content, the rest use white.
 * These media colours are intentionally constant across light/dark — a
 * billboard is its own vivid surface, like a TV — while staying contrast-safe.
 */
enum class PromotionAccent { GREEN, NAVY, BLUE, ORANGE }

/**
 * What kind of remote artwork a billboard slide carries.
 *
 * - [NONE]  text-only slide: the classic solid-colour billboard.
 * - [IMAGE] a still image (PNG/JPG/WebP) drawn behind the slide text.
 * - [GIF]   an animated GIF, decoded by the GIF decoders registered on the
 *           shared billboard image loader.
 *
 * Stored on [Promotion] as a plain [String] (see [Promotion.mediaType]) so an
 * unknown value published by a newer server can never crash an older app — it
 * degrades to [NONE] and the slide renders as text, which is always safe.
 */
enum class PromotionMediaType { NONE, IMAGE, GIF }

/**
 * What tapping a billboard slide should do. The app (MainActivity) performs the
 * navigation; the model only carries the intent.
 *
 * - [NONE]           fall back to the legacy kind-based behaviour.
 * - [OFFER]          open the purchase sheet for [Promotion.clickTarget]
 *                    (or [Promotion.linkedOfferId]).
 * - [CATEGORY]       browse Offers filtered to the target category.
 * - [EXTERNAL_LINK]  open an https link in the browser (mapping only ever
 *                    produces this for an `https://`/`http://` target).
 * - [INTERNAL_ROUTE] navigate to a known in-app route ("offers", "help", ...).
 *
 * Stored as a [String] for the same forward-compatibility reason as
 * [PromotionMediaType]; unknown values degrade to [NONE].
 */
enum class PromotionClickAction { NONE, OFFER, CATEGORY, EXTERNAL_LINK, INTERNAL_ROUTE }

/**
 * Parse a server-supplied media-type token. Case/whitespace tolerant. Anything
 * unknown, blank or null degrades to [PromotionMediaType.NONE] — never throws
 * (deliberately not `enumValueOf`, which would crash on an unknown value).
 */
fun promotionMediaTypeOf(raw: String?): PromotionMediaType =
    when (raw?.trim()?.uppercase()) {
        "IMAGE" -> PromotionMediaType.IMAGE
        "GIF" -> PromotionMediaType.GIF
        else -> PromotionMediaType.NONE
    }

/**
 * Parse a server-supplied click-action token. Case/whitespace tolerant; unknown,
 * blank or null degrades to [PromotionClickAction.NONE] — never throws.
 */
fun promotionClickActionOf(raw: String?): PromotionClickAction =
    when (raw?.trim()?.uppercase()) {
        "OFFER" -> PromotionClickAction.OFFER
        "CATEGORY" -> PromotionClickAction.CATEGORY
        "EXTERNAL_LINK" -> PromotionClickAction.EXTERNAL_LINK
        "INTERNAL_ROUTE" -> PromotionClickAction.INTERNAL_ROUTE
        else -> PromotionClickAction.NONE
    }

/**
 * A single promotion / announcement shown on the Home billboard.
 *
 * The billboard is the app's advert surface: it rotates the seller's biggest
 * offers (weekly / monthly / high-value) plus occasional announcements and app
 * updates. [imageRes] carries an optional bundled drawable; [mediaUrl] carries
 * remote artwork (still image or animated GIF) synced from the admin console and
 * rendered from Coil's on-disk cache, so a slide that was fetched once keeps
 * rendering with no internet.
 *
 * PERSISTENCE CONTRACT: this class is stored inside the Moshi (reflection)
 * snapshot written by `data/persistence/LocalStore`. Every field added after
 * v1.0.2 MUST therefore keep a default value, and must stay a primitive,
 * String, Boolean or enum — otherwise a snapshot written by an older build
 * fails to deserialise and the customer loses their local state.
 */
data class Promotion(
    val id: String,
    val kind: PromotionKind,
    val tag: String,
    val headline: String,
    val subhead: String,
    val ctaLabel: String,
    val accent: PromotionAccent,
    val linkedOfferId: String? = null,
    val linkedCategory: OfferCategory? = null,
    @DrawableRes val imageRes: Int? = null,
    val priorityWeight: Int = 0,
    val startMillis: Long = 0L,
    val endMillis: Long = Long.MAX_VALUE,
    /** Absolute https URL of the slide artwork; blank means "text slide". */
    val mediaUrl: String = "",
    /** One of NONE / IMAGE / GIF — see [promotionMediaTypeOf]. */
    val mediaType: String = "NONE",
    /** One of NONE / OFFER / CATEGORY / EXTERNAL_LINK / INTERNAL_ROUTE. */
    val clickAction: String = "NONE",
    /** Meaning depends on [clickAction]: offer id, category name, url or route. */
    val clickTarget: String = "",
    /**
     * Server cache-busting token. When it changes the effective Coil cache key
     * changes, so a republished image at the SAME url is refetched exactly once
     * (see `core/media/BillboardImageLoader`).
     */
    val mediaVersion: String = "",
    /** Accessibility text for [mediaUrl]; blank falls back to [headline]. */
    val mediaAltText: String = ""
) {
    fun isActive(nowMillis: Long): Boolean = nowMillis in startMillis..endMillis

    // NOTE: computed properties (no backing field, not constructor parameters)
    // are ignored by Moshi's reflective Kotlin adapter, so they are safe to add
    // to a persisted class.

    /** [mediaType] parsed safely; unknown values read as [PromotionMediaType.NONE]. */
    val mediaTypeOrNone: PromotionMediaType
        get() = promotionMediaTypeOf(mediaType)

    /** [clickAction] parsed safely; unknown values read as [PromotionClickAction.NONE]. */
    val clickActionOrNone: PromotionClickAction
        get() = promotionClickActionOf(clickAction)

    /** True when this slide should attempt to draw remote artwork. */
    val hasRemoteMedia: Boolean
        get() = mediaUrl.isNotBlank() && mediaTypeOrNone != PromotionMediaType.NONE
}
