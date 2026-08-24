package com.example.data.catalogue

import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionClickAction
import com.example.core.model.PromotionKind
import com.example.core.model.PromotionMediaType
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Retrofit implementation of [RemoteBillboardSource] backed by `get_billboards.php`.
 * Maps the admin's published billboards into [Promotion]. Any failure returns null so
 * the caller keeps the local billboards. No secrets — only the non-secret base URL +
 * app-key header.
 *
 * A slide may carry remote artwork (still image or animated GIF): the mapping only
 * records the url, type, version and click intent, and the Home billboard renders it
 * from Coil's on-disk cache (see `core/media/BillboardImageLoader`). A slide with no
 * artwork — or whose artwork fails to load — renders as the coloured text slide,
 * exactly like the seeded promotions.
 */
class AndroidRemoteBillboardSource(
    baseUrl: String,
    appKey: String,
    enableLogging: Boolean = false
) : RemoteBillboardSource {

    private val api: BillboardsApi? = if (baseUrl.startsWith("https://")) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-App-Key", appKey).build())
            })
            .build()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BillboardsApi::class.java)
    } else {
        null
    }

    /**
     * Null means "could not ask" (no client, transport failure) and the caller keeps
     * whatever it already has. An EMPTY list means the owner really has published no
     * billboards, and the caller clears the board — no billboard is hardcoded, so
     * un-publishing them all must take them off the device too rather than leave a
     * withdrawn advert running forever.
     *
     * The published index is passed into the mapping so consecutive slides get
     * different colours (see [BillboardDto.toPromotion]).
     */
    override suspend fun fetch(): List<Promotion>? {
        val response = try {
            api?.getBillboards() ?: return null
        } catch (t: Throwable) {
            return null
        }
        return response.billboards.mapIndexedNotNull { index, dto -> dto.toPromotion(index) }
    }
}

interface BillboardsApi {
    @GET("get_billboards.php")
    suspend fun getBillboards(): BillboardsResponseDto
}

data class BillboardsResponseDto(
    @Json(name = "billboards") val billboards: List<BillboardDto> = emptyList()
)

data class BillboardDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "kind") val kind: String? = null,
    @Json(name = "priority") val priority: Int? = null,
    @Json(name = "linkedOfferId") val linkedOfferId: String? = null,
    @Json(name = "tag") val tag: String? = null,
    @Json(name = "headline") val headline: String? = null,
    @Json(name = "body") val body: String? = null,
    @Json(name = "ctaLabel") val ctaLabel: String? = null,
    @Json(name = "ctaDestination") val ctaDestination: String? = null,
    @Json(name = "imageUrl") val imageUrl: String? = null,
    @Json(name = "altText") val altText: String? = null,
    @Json(name = "audienceRule") val audienceRule: String? = null,
    @Json(name = "frequencyCap") val frequencyCap: Int? = null,
    @Json(name = "startsAt") val startsAt: String? = null,
    @Json(name = "endsAt") val endsAt: String? = null,
    // --- Billboard media (all optional; an older server simply omits them) ----
    /** Absolute https url of the slide artwork. Falls back to [imageUrl]. */
    @Json(name = "mediaUrl") val mediaUrl: String? = null,
    /** "IMAGE" | "GIF" | "NONE". Inferred from the url when absent/unknown. */
    @Json(name = "mediaType") val mediaType: String? = null,
    /** Cache-busting token; change it when republishing artwork at the same url. */
    @Json(name = "mediaVersion") val mediaVersion: String? = null,
    /** "OFFER" | "CATEGORY" | "EXTERNAL_LINK" | "INTERNAL_ROUTE" | "NONE". */
    @Json(name = "clickAction") val clickAction: String? = null,
    /** Offer id / category / https url / in-app route, per [clickAction]. */
    @Json(name = "clickTarget") val clickTarget: String? = null
) {
    /**
     * Map one published billboard row into a [Promotion]. Returns null (skipped) when the
     * core visible content is missing (no id or blank headline).
     *
     * The accent is chosen HERE, never sent by the server, so a billboard can never
     * paint an unreadable combination — every palette in `PromotionBillboard` is
     * contrast-checked and the server cannot reach past it.
     *
     * It varies by published position rather than by kind alone. Deriving it from the
     * kind painted every offer slide the same green, so a board of three offers was
     * three identical green cards; cycling by [index] gives neighbouring slides
     * different colours while staying deterministic, so the same board always paints
     * the same way and never flickers between syncs. Orange appears on offer slides
     * only — design.md §7 reserves it for a real promotion or discount, which is
     * exactly what an offer billboard is.
     *
     * Every media field is optional and degrades safely: no url → text slide; an
     * unknown media type → inferred from the file extension; an unknown or unsafe
     * click action → no click action at all.
     */
    fun toPromotion(index: Int): Promotion? {
        val safeId = id?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val safeHeadline = headline?.takeIf { it.isNotBlank() } ?: return null
        val promoKind = when ((kind ?: "").trim().lowercase()) {
            "offer" -> PromotionKind.OFFER
            "announcement" -> PromotionKind.ANNOUNCEMENT
            "update" -> PromotionKind.UPDATE
            else -> PromotionKind.ANNOUNCEMENT
        }
        val palette = when (promoKind) {
            PromotionKind.OFFER -> OFFER_ACCENTS
            PromotionKind.ANNOUNCEMENT -> ANNOUNCEMENT_ACCENTS
            PromotionKind.UPDATE -> UPDATE_ACCENTS
        }
        val promoAccent = palette[Math.floorMod(index, palette.size)]
        val safeLinkedOfferId = linkedOfferId?.takeIf { it.isNotBlank() }
        // `imageUrl` is the legacy field the admin console already publishes; a
        // newer server may send `mediaUrl` instead. Either is accepted.
        val resolvedMediaUrl = resolveMediaUrl(mediaUrl ?: imageUrl)
        val resolvedMediaType = resolveMediaType(mediaType, resolvedMediaUrl)
        val resolvedTarget = (clickTarget ?: ctaDestination).orEmpty().trim()
        val resolvedAction = resolveClickAction(clickAction, resolvedTarget, promoKind, safeLinkedOfferId)
        // An OFFER action with no explicit target points at the linked offer.
        val finalTarget = when (resolvedAction) {
            PromotionClickAction.NONE -> ""
            PromotionClickAction.OFFER -> resolvedTarget.ifBlank { safeLinkedOfferId.orEmpty() }
            else -> resolvedTarget
        }
        return Promotion(
            id = safeId,
            kind = promoKind,
            tag = tag.orEmpty(),
            headline = safeHeadline,
            subhead = body.orEmpty(),
            ctaLabel = ctaLabel.orEmpty(),
            accent = promoAccent,
            linkedOfferId = safeLinkedOfferId,
            linkedCategory = null,
            imageRes = null,
            // The server orders by priority ASC (lower = shown first); the app's billboard
            // selection (CatalogueLogic.selectPromotions) sorts by priorityWeight DESC, so
            // negate to preserve the published order.
            priorityWeight = -(priority ?: 0),
            startMillis = parseIsoMillis(startsAt, 0L),
            endMillis = parseIsoMillis(endsAt, Long.MAX_VALUE),
            mediaUrl = if (resolvedMediaType == PromotionMediaType.NONE) "" else resolvedMediaUrl,
            mediaType = resolvedMediaType.name,
            clickAction = resolvedAction.name,
            clickTarget = finalTarget,
            mediaVersion = mediaVersion.orEmpty().trim(),
            mediaAltText = altText.orEmpty().trim()
        )
    }

    private companion object {

        /**
         * Accent rotations, indexed by a slide's published position. Four distinct
         * colours for offer slides so a board of offers never repeats until the fifth
         * card; announcements alternate between the two informational colours; app news
         * is always navy so it reads as a system notice rather than a sale.
         */
        private val OFFER_ACCENTS = listOf(
            PromotionAccent.GREEN,
            PromotionAccent.ORANGE,
            PromotionAccent.NAVY,
            PromotionAccent.BLUE,
        )
        private val ANNOUNCEMENT_ACCENTS = listOf(PromotionAccent.BLUE, PromotionAccent.NAVY)
        private val UPDATE_ACCENTS = listOf(PromotionAccent.NAVY)

        private const val NAIROBI_ZONE = "Africa/Nairobi"

        /** UTC forms — the admin's ISO-8601 "…Z" timestamps. */
        private val UTC_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )

        /**
         * Local forms interpreted in Africa/Nairobi — what an admin typing a
         * wall-clock time into the console actually means.
         */
        private val LOCAL_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )

        /**
         * Parse an admin timestamp to epoch millis.
         *
         * Historically only the UTC form ("yyyy-MM-dd'T'HH:mm:ss'Z'") parsed, so a
         * Nairobi wall-clock timestamp published by the console fell back to the
         * permissive default and, for an end time, could hide a slide for up to
         * three hours (Nairobi is UTC+3, no DST). Both shapes are now accepted:
         *
         * - a value ending in "Z" is UTC;
         * - anything else is a local Nairobi wall-clock time.
         *
         * The two families are tried in separate branches on purpose:
         * [SimpleDateFormat.parse] ignores trailing unparsed text, so trying a
         * pattern without the literal 'Z' first would silently parse "…T00:00:00Z"
         * as Nairobi local time and shift it by three hours.
         *
         * Null / blank / unparseable → [default] (0L for start, Long.MAX_VALUE for
         * end), so a missing or malformed window still means "always active" — a
         * slide is never lost to a bad timestamp. SimpleDateFormat is used (not
         * java.time) because minSdk 24 has no core-library desugaring.
         */
        private fun parseIsoMillis(value: String?, default: Long): Long {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return default
            val isUtc = raw.endsWith("Z") || raw.endsWith("z")
            val patterns = if (isUtc) UTC_PATTERNS else LOCAL_PATTERNS
            val zone = if (isUtc) "UTC" else NAIROBI_ZONE
            for (pattern in patterns) {
                val parsed = tryParse(raw, pattern, zone)
                if (parsed != null) return parsed
            }
            return default
        }

        private fun tryParse(raw: String, pattern: String, zoneId: String): Long? = try {
            val fmt = SimpleDateFormat(pattern, Locale.US)
            fmt.timeZone = TimeZone.getTimeZone(zoneId)
            fmt.isLenient = false
            fmt.parse(raw)?.time
        } catch (t: Throwable) {
            null
        }

        /** Only absolute http(s) media is accepted; anything else becomes "no media". */
        private fun resolveMediaUrl(raw: String?): String {
            val url = raw?.trim().orEmpty()
            val lower = url.lowercase()
            return if (lower.startsWith("https://") || lower.startsWith("http://")) url else ""
        }

        /**
         * IMAGE / GIF / NONE. A blank url is always NONE. An explicit, known type
         * wins; a missing or unrecognised type is inferred from the file extension
         * (".gif" → GIF, otherwise IMAGE) so a valid picture is never dropped just
         * because the server sent a token this build does not know.
         */
        private fun resolveMediaType(raw: String?, url: String): PromotionMediaType {
            if (url.isBlank()) return PromotionMediaType.NONE
            return when (raw?.trim()?.uppercase()) {
                "NONE" -> PromotionMediaType.NONE
                "GIF" -> PromotionMediaType.GIF
                "IMAGE" -> PromotionMediaType.IMAGE
                else -> {
                    val path = url.substringBefore('?').substringBefore('#').lowercase()
                    if (path.endsWith(".gif")) PromotionMediaType.GIF else PromotionMediaType.IMAGE
                }
            }
        }

        /**
         * Resolve the tap intent. Unknown tokens degrade to NONE (the slide keeps
         * its legacy kind-based behaviour). EXTERNAL_LINK is only ever produced for
         * an absolute http(s) target — never for `intent://`, `file://` or any other
         * scheme — so a compromised or careless admin row cannot make the app open
         * an arbitrary component.
         */
        private fun resolveClickAction(
            raw: String?,
            target: String,
            kind: PromotionKind,
            linkedOfferId: String?
        ): PromotionClickAction {
            val declared = when (raw?.trim()?.uppercase()) {
                "OFFER" -> PromotionClickAction.OFFER
                "CATEGORY" -> PromotionClickAction.CATEGORY
                "EXTERNAL_LINK", "EXTERNAL", "LINK", "URL" -> PromotionClickAction.EXTERNAL_LINK
                "INTERNAL_ROUTE", "INTERNAL", "ROUTE" -> PromotionClickAction.INTERNAL_ROUTE
                "NONE" -> PromotionClickAction.NONE
                else -> null
            }
            val lowerTarget = target.lowercase()
            val looksExternal = lowerTarget.startsWith("https://") || lowerTarget.startsWith("http://")
            val resolved = declared ?: when {
                // No declared action: infer only the two unambiguous cases.
                looksExternal -> PromotionClickAction.EXTERNAL_LINK
                kind == PromotionKind.OFFER && linkedOfferId != null -> PromotionClickAction.OFFER
                else -> PromotionClickAction.NONE
            }
            return when {
                // An external link must be a real web url.
                resolved == PromotionClickAction.EXTERNAL_LINK && !looksExternal ->
                    PromotionClickAction.NONE
                // Every other action needs somewhere to go.
                resolved != PromotionClickAction.NONE &&
                    target.isBlank() &&
                    !(resolved == PromotionClickAction.OFFER && linkedOfferId != null) ->
                    PromotionClickAction.NONE
                else -> resolved
            }
        }
    }
}
