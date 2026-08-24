package com.example.data.catalogue

import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionClickAction
import com.example.core.model.PromotionKind
import com.example.core.model.PromotionMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Verifies the deterministic, design-safe server→[com.example.core.model.Promotion]
 * mapping in [BillboardDto.toPromotion]. Runs on the host JVM, so [Instant] is used only
 * to compute expected epoch millis independently of the SimpleDateFormat under test.
 */
class BillboardMappingTest {

    private fun dto(
        id: Long? = 1L,
        kind: String? = "offer",
        priority: Int? = 0,
        linkedOfferId: String? = null,
        headline: String? = "Headline",
        body: String? = "Body",
        startsAt: String? = null,
        endsAt: String? = null,
        imageUrl: String? = null,
        altText: String? = null,
        ctaDestination: String? = null,
        mediaUrl: String? = null,
        mediaType: String? = null,
        mediaVersion: String? = null,
        clickAction: String? = null,
        clickTarget: String? = null
    ) = BillboardDto(
        id = id, kind = kind, priority = priority, linkedOfferId = linkedOfferId,
        tag = "TAG", headline = headline, body = body, ctaLabel = "Buy now",
        ctaDestination = ctaDestination, imageUrl = imageUrl, altText = altText,
        startsAt = startsAt, endsAt = endsAt,
        mediaUrl = mediaUrl, mediaType = mediaType, mediaVersion = mediaVersion,
        clickAction = clickAction, clickTarget = clickTarget
    )

    @Test
    fun mapsCoreFields() {
        val p = dto(id = 42L, headline = "8GB", body = "Mega bundle").toPromotion(0)!!
        assertEquals("42", p.id)
        assertEquals("8GB", p.headline)
        assertEquals("Mega bundle", p.subhead)
        assertEquals("Buy now", p.ctaLabel)
        assertEquals("TAG", p.tag)
        assertNull(p.imageRes)
        assertNull(p.linkedCategory)
    }

    @Test
    fun kindAndPositionDetermineAccent() {
        // The FIRST slide of each kind keeps the colour it has always had.
        assertEquals(PromotionAccent.GREEN, dto(kind = "offer").toPromotion(0)!!.accent)
        assertEquals(PromotionAccent.BLUE, dto(kind = "announcement").toPromotion(0)!!.accent)
        assertEquals(PromotionAccent.NAVY, dto(kind = "update").toPromotion(0)!!.accent)
        // Case-insensitive on the server string.
        assertEquals(PromotionKind.UPDATE, dto(kind = "UPDATE").toPromotion(0)!!.kind)
        // Unknown kind → ANNOUNCEMENT, taking the informational palette.
        val unknown = dto(kind = "flashsale").toPromotion(0)!!
        assertEquals(PromotionKind.ANNOUNCEMENT, unknown.kind)
        assertEquals(PromotionAccent.BLUE, unknown.accent)
    }

    /**
     * A board of offers used to paint every card the same green, because the accent came
     * from the kind alone. It now cycles with the published position.
     */
    @Test
    fun consecutiveOfferSlidesGetDifferentAccents() {
        val accents = (0..3).map { dto(kind = "offer").toPromotion(it)!!.accent }
        assertEquals(4, accents.toSet().size)
        // Adjacent slides always differ, including where the rotation wraps.
        (0..4).map { dto(kind = "offer").toPromotion(it)!!.accent }
            .zipWithNext()
            .forEach { (a, b) -> assertNotEquals(a, b) }
        // The fifth slide restarts the cycle — deterministic, so a re-sync never reshuffles.
        assertEquals(accents[0], dto(kind = "offer").toPromotion(4)!!.accent)
    }

    /** Announcements alternate between the two informational colours; app news stays navy. */
    @Test
    fun announcementsAlternate_andUpdatesStayNavy() {
        assertEquals(PromotionAccent.NAVY, dto(kind = "announcement").toPromotion(1)!!.accent)
        assertEquals(PromotionAccent.BLUE, dto(kind = "announcement").toPromotion(2)!!.accent)
        assertEquals(PromotionAccent.NAVY, dto(kind = "update").toPromotion(3)!!.accent)
    }

    @Test
    fun priorityIsNegatedSoLowerPrioritySortsFirst() {
        assertEquals(0, dto(priority = 0).toPromotion(0)!!.priorityWeight)
        assertEquals(-5, dto(priority = 5).toPromotion(0)!!.priorityWeight)
        assertEquals(0, dto(priority = null).toPromotion(0)!!.priorityWeight)
    }

    @Test
    fun blankLinkedOfferIdBecomesNull() {
        assertNull(dto(linkedOfferId = "").toPromotion(0)!!.linkedOfferId)
        assertNull(dto(linkedOfferId = null).toPromotion(0)!!.linkedOfferId)
        assertEquals("data_1", dto(linkedOfferId = "data_1").toPromotion(0)!!.linkedOfferId)
    }

    @Test
    fun missingIdOrHeadlineIsSkipped() {
        assertNull(dto(id = null).toPromotion(0))
        assertNull(dto(headline = "").toPromotion(0))
        assertNull(dto(headline = null).toPromotion(0))
    }

    @Test
    fun parsesIsoWindow_andDefaultsWhenAbsent() {
        val p = dto(
            startsAt = "2026-07-26T00:00:00Z",
            endsAt = "2026-07-27T00:00:00Z"
        ).toPromotion(0)!!
        assertEquals(Instant.parse("2026-07-26T00:00:00Z").toEpochMilli(), p.startMillis)
        assertEquals(Instant.parse("2026-07-27T00:00:00Z").toEpochMilli(), p.endMillis)

        // Null/blank/garbage → sensible always-active defaults.
        assertEquals(0L, dto(startsAt = null).toPromotion(0)!!.startMillis)
        assertEquals(Long.MAX_VALUE, dto(endsAt = null).toPromotion(0)!!.endMillis)
        assertEquals(0L, dto(startsAt = "  ").toPromotion(0)!!.startMillis)
        assertEquals(0L, dto(startsAt = "not-a-date").toPromotion(0)!!.startMillis)
    }

    // --- timestamp formats (Nairobi-local support) ---------------------------

    @Test
    fun parsesNairobiLocalWindow_spaceSeparated() {
        // Africa/Nairobi is UTC+3 all year (no DST): 09:00 local == 06:00 UTC.
        val p = dto(
            startsAt = "2026-07-26 09:00:00",
            endsAt = "2026-07-26 21:30:00"
        ).toPromotion(0)!!
        assertEquals(Instant.parse("2026-07-26T06:00:00Z").toEpochMilli(), p.startMillis)
        assertEquals(Instant.parse("2026-07-26T18:30:00Z").toEpochMilli(), p.endMillis)
    }

    @Test
    fun parsesNairobiLocalWindow_isoWithoutZone() {
        val p = dto(startsAt = "2026-07-26T09:00:00").toPromotion(0)!!
        assertEquals(Instant.parse("2026-07-26T06:00:00Z").toEpochMilli(), p.startMillis)
    }

    @Test
    fun parsesDateOnlyAsNairobiMidnight() {
        val p = dto(startsAt = "2026-07-26").toPromotion(0)!!
        assertEquals(Instant.parse("2026-07-25T21:00:00Z").toEpochMilli(), p.startMillis)
    }

    @Test
    fun utcAndNairobiFormsOfTheSameInstantAgree() {
        val utc = dto(startsAt = "2026-07-26T06:00:00Z").toPromotion(0)!!.startMillis
        val local = dto(startsAt = "2026-07-26 09:00:00").toPromotion(0)!!.startMillis
        assertEquals(utc, local)
    }

    @Test
    fun millisecondUtcFormIsAccepted() {
        val p = dto(startsAt = "2026-07-26T06:00:00.000Z").toPromotion(0)!!
        assertEquals(Instant.parse("2026-07-26T06:00:00Z").toEpochMilli(), p.startMillis)
    }

    // --- media ---------------------------------------------------------------

    @Test
    fun mediaDefaultsToNoneWhenNothingIsPublished() {
        val p = dto().toPromotion(0)!!
        assertEquals("", p.mediaUrl)
        assertEquals(PromotionMediaType.NONE, p.mediaTypeOrNone)
        assertEquals("", p.mediaVersion)
        assertEquals("", p.mediaAltText)
        assertFalse(p.hasRemoteMedia)
    }

    @Test
    fun legacyImageUrlBecomesImageMedia() {
        val p = dto(imageUrl = "https://cdn.example.com/a.png", altText = "Weekend deal").toPromotion(0)!!
        assertEquals("https://cdn.example.com/a.png", p.mediaUrl)
        assertEquals(PromotionMediaType.IMAGE, p.mediaTypeOrNone)
        assertEquals("Weekend deal", p.mediaAltText)
        assertTrue(p.hasRemoteMedia)
    }

    @Test
    fun gifIsDetectedFromTypeOrExtension() {
        assertEquals(
            PromotionMediaType.GIF,
            dto(mediaUrl = "https://cdn.example.com/a.png", mediaType = "gif").toPromotion(0)!!.mediaTypeOrNone
        )
        assertEquals(
            PromotionMediaType.GIF,
            dto(mediaUrl = "https://cdn.example.com/a.GIF?v=2").toPromotion(0)!!.mediaTypeOrNone
        )
    }

    @Test
    fun unknownMediaTypeFallsBackToImageRatherThanLosingTheArtwork() {
        val p = dto(mediaUrl = "https://cdn.example.com/a.webp", mediaType = "lottie").toPromotion(0)!!
        assertEquals(PromotionMediaType.IMAGE, p.mediaTypeOrNone)
    }

    @Test
    fun explicitNoneOrNonHttpMediaIsDropped() {
        assertEquals(
            PromotionMediaType.NONE,
            dto(mediaUrl = "https://cdn.example.com/a.png", mediaType = "none").toPromotion(0)!!.mediaTypeOrNone
        )
        val unsafe = dto(mediaUrl = "file:///sdcard/a.png").toPromotion(0)!!
        assertEquals(PromotionMediaType.NONE, unsafe.mediaTypeOrNone)
        assertEquals("", unsafe.mediaUrl)
    }

    @Test
    fun mediaVersionIsCarriedForCacheBusting() {
        val p = dto(mediaUrl = "https://cdn.example.com/a.png", mediaVersion = " v7 ").toPromotion(0)!!
        assertEquals("v7", p.mediaVersion)
    }

    // --- click action --------------------------------------------------------

    @Test
    fun clickActionIsParsedAndUnknownDegradesToNone() {
        assertEquals(
            PromotionClickAction.CATEGORY,
            dto(clickAction = "category", clickTarget = "SMS").toPromotion(0)!!.clickActionOrNone
        )
        assertEquals(
            PromotionClickAction.INTERNAL_ROUTE,
            dto(clickAction = "internal_route", clickTarget = "offers").toPromotion(0)!!.clickActionOrNone
        )
        // Unknown token → NONE (the slide keeps its legacy kind-based behaviour).
        assertEquals(
            PromotionClickAction.NONE,
            dto(clickAction = "teleport", clickTarget = "somewhere").toPromotion(0)!!.clickActionOrNone
        )
        // A declared action with nowhere to go → NONE.
        assertEquals(
            PromotionClickAction.NONE,
            dto(clickAction = "category", clickTarget = "").toPromotion(0)!!.clickActionOrNone
        )
    }

    @Test
    fun externalLinkOnlyEverPointsAtAWebUrl() {
        val web = dto(clickAction = "external_link", clickTarget = "https://bingwa.example/promo").toPromotion(0)!!
        assertEquals(PromotionClickAction.EXTERNAL_LINK, web.clickActionOrNone)
        assertEquals("https://bingwa.example/promo", web.clickTarget)

        // A non-web scheme can never become an external link.
        val unsafe = dto(clickAction = "external_link", clickTarget = "intent://evil").toPromotion(0)!!
        assertEquals(PromotionClickAction.NONE, unsafe.clickActionOrNone)
        assertEquals("", unsafe.clickTarget)
    }

    @Test
    fun offerSlideWithALinkedOfferGetsAnOfferAction() {
        val p = dto(kind = "offer", linkedOfferId = "data_1").toPromotion(0)!!
        assertEquals(PromotionClickAction.OFFER, p.clickActionOrNone)
        assertEquals("data_1", p.clickTarget)
        // No linked offer and no target → no action at all.
        assertEquals(PromotionClickAction.NONE, dto(kind = "offer").toPromotion(0)!!.clickActionOrNone)
    }
}
