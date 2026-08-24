package com.example.core.personalization

import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.PaymentStatus
import com.example.core.model.PurchaseRecord
import com.example.core.payment.KenyanPhone
import kotlin.math.pow

/**
 * Builds the installation-local [BehaviourProfile] from this device's own
 * Skylink Bingwa payment records.
 *
 * The engine is **pure**: no Android types, no I/O, no network, and it never
 * reads a clock — `nowMillis` is always supplied by the caller, so the same
 * inputs always produce the same profile and every rule is unit-testable.
 *
 * PRIVACY (CLAUDE.md §10): the inputs are already on this device and the output
 * never leaves it. This file must never gain a network call, an analytics hook,
 * or a logging statement that prints a full phone number.
 *
 * ### Which purchases are learned from
 * Only records that represent a payment the customer actually made:
 * [PaymentStatus.RECEIVED] (confirmed online payment) and
 * [PaymentStatus.WAITING_VERIFY] (the normal end state of an offline
 * Till/Paybill purchase the customer reported — excluding it would make offline
 * buyers invisible to their own app). Abandoned or failed attempts —
 * `CANCELLED`, `FAILED`, `EXPIRED`, `NOT_CONFIRMED`, `COULD_NOT_VERIFY` — teach
 * nothing and are ignored, so a run of failed STK pushes can never reshape Home.
 *
 * ### Recency weighting
 * Every learned record contributes `0.5 ^ (ageInDays / [RECENCY_HALF_LIFE_DAYS])`
 * rather than a flat 1. With a 30-day half-life a purchase made today counts
 * ~16x a purchase made 120 days ago, so the profile follows a habit that changes
 * instead of being frozen by whatever the customer bought when they installed
 * the app. Counts exposed on the profile ([BehaviourProfile.purchaseCountByOfferId])
 * stay unweighted so they can still be read as plain "times bought".
 */
object PersonalizationEngine {

    /** Days after which a purchase counts half as much towards the profile. */
    const val RECENCY_HALF_LIFE_DAYS = 30.0

    /** Below this many learned purchases the customer is [BuyerFrequency.NEW]. */
    const val MIN_PURCHASES_FOR_FREQUENCY = 3

    /** Average purchases per week at or above which the customer is [BuyerFrequency.HEAVY]. */
    const val HEAVY_PURCHASES_PER_WEEK = 5.0

    /** Distinct active days within the last 7 at or above which the customer is [BuyerFrequency.DAILY]. */
    const val DAILY_ACTIVE_DAYS_IN_LAST_7 = 5

    /** Share of purchases falling on Sat/Sun at or above which the customer is [BuyerFrequency.WEEKEND]. */
    const val WEEKEND_SHARE = 0.7

    /** Statuses that represent a payment the customer really made. */
    private val LEARNABLE_STATUSES = setOf(
        PaymentStatus.RECEIVED,
        PaymentStatus.WAITING_VERIFY
    )

    /**
     * Derive the behaviour profile.
     *
     * @param purchases     this installation's payment records (any status; filtered here).
     * @param favouriteIds  offer ids the customer has hearted; a favourite is a
     *                      deliberate signal, so it counts towards category affinity
     *                      even before the offer has ever been bought.
     * @param recentRecipients numbers the customer recently typed at checkout, newest
     *                      first. They seed [BehaviourProfile.topRecipients] with
     *                      count 0 so checkout can still offer them, but they never
     *                      make the profile "non-empty" on their own.
     * @param nowMillis     the caller's clock; never read internally.
     * @param offers        the cached catalogue, when available. Supplying it makes
     *                      category / validity exact instead of inferred from the
     *                      record's stored offer name + allowance text. Optional so
     *                      the profile can still be rebuilt before the catalogue loads.
     */
    fun buildProfile(
        purchases: List<PurchaseRecord>,
        favouriteIds: Set<String>,
        recentRecipients: List<String>,
        nowMillis: Long,
        offers: List<OfferItem> = emptyList()
    ): BehaviourProfile {
        val learnable = purchases.filter {
            it.status in LEARNABLE_STATUSES && it.timestampMillis > 0L
        }
        val recipients = buildRecipients(learnable, recentRecipients)

        if (learnable.isEmpty()) {
            // No usable history: stays BehaviourProfile.isEmpty() == true so Home,
            // ranking and reminders all behave exactly as before personalization.
            return BehaviourProfile(topRecipients = recipients)
        }

        val byId: Map<String, OfferItem> = offers.associateBy { it.id }

        val mostPurchasedOfferId = weightedTop(learnable, nowMillis) {
            it.offerId.takeIf { id -> id.isNotBlank() }
        }.orEmpty()

        val mostPurchasedAmountKsh = weightedTop(learnable, nowMillis) {
            it.priceKsh.takeIf { price -> price > 0 }
        } ?: 0

        val favouriteCategory = weightedTop(learnable, nowMillis) { record ->
            categoryOf(record, byId)
        } ?: favouriteCategoryFromHearts(favouriteIds, byId)

        val preferredValidity = weightedTop(learnable, nowMillis) { record ->
            bandOf(record, byId).takeIf { band -> band.isNotEmpty() }
        }.orEmpty()

        val favouriteHourlyOfferId = weightedTop(
            learnable.filter { bandOf(it, byId) == BAND_HOURLY },
            nowMillis
        ) { it.offerId.takeIf { id -> id.isNotBlank() } }.orEmpty()

        val favouriteDailyOfferId = weightedTop(
            learnable.filter { bandOf(it, byId) == BAND_DAILY },
            nowMillis
        ) { it.offerId.takeIf { id -> id.isNotBlank() } }.orEmpty()

        val hourWeights = DoubleArray(24)
        learnable.forEach { record ->
            hourWeights[NairobiTime.hourOfDay(record.timestampMillis)] +=
                recencyWeight(record.timestampMillis, nowMillis)
        }
        var bestHour = -1
        var bestHourWeight = 0.0
        for (hour in 0 until 24) {
            if (hourWeights[hour] > bestHourWeight) {
                bestHourWeight = hourWeights[hour]
                bestHour = hour
            }
        }

        val lastPurchase = learnable.maxByOrNull { it.timestampMillis }

        return BehaviourProfile(
            totalPurchases = learnable.size,
            mostPurchasedOfferId = mostPurchasedOfferId,
            mostPurchasedAmountKsh = mostPurchasedAmountKsh,
            favouriteCategory = favouriteCategory,
            preferredValidity = preferredValidity,
            favouriteHourlyOfferId = favouriteHourlyOfferId,
            favouriteDailyOfferId = favouriteDailyOfferId,
            timeProfile = timeProfileOf(hourWeights),
            usualPurchaseHour = bestHour,
            frequency = frequencyOf(learnable, nowMillis),
            preferredPayerNumber = preferredPayerNumberOf(learnable, nowMillis),
            topRecipients = recipients,
            purchaseCountByOfferId = learnable.groupingBy { it.offerId }.eachCount(),
            lastPurchaseAtMillis = lastPurchase?.timestampMillis ?: 0L,
            lastPurchaseOfferId = lastPurchase?.offerId.orEmpty(),
            lastPurchaseAtMillisByOfferId = learnable
                .groupBy { it.offerId }
                .mapValues { entry -> entry.value.maxOf { it.timestampMillis } }
        )
    }

    // -----------------------------------------------------------------------
    // Weighting
    // -----------------------------------------------------------------------

    /** Exponential recency weight in (0, 1]; 1.0 for "just now", 0.5 per half-life. */
    fun recencyWeight(timestampMillis: Long, nowMillis: Long): Double {
        val ageDays = ((nowMillis - timestampMillis).toDouble() / NairobiTime.DAY_MILLIS_D)
            .coerceAtLeast(0.0)
        return 0.5.pow(ageDays / RECENCY_HALF_LIFE_DAYS)
    }

    /**
     * The key with the highest recency-weighted mass. Ties break on the most
     * recent use, then on first-seen order, so the result is deterministic.
     */
    private fun <K : Any> weightedTop(
        records: List<PurchaseRecord>,
        nowMillis: Long,
        keyOf: (PurchaseRecord) -> K?
    ): K? {
        val weights = LinkedHashMap<K, Double>()
        val lastSeen = HashMap<K, Long>()
        records.forEach { record ->
            val key = keyOf(record) ?: return@forEach
            weights[key] = (weights[key] ?: 0.0) + recencyWeight(record.timestampMillis, nowMillis)
            lastSeen[key] = maxOf(lastSeen[key] ?: 0L, record.timestampMillis)
        }
        if (weights.isEmpty()) return null
        return weights.entries
            .sortedWith(
                compareByDescending<Map.Entry<K, Double>> { it.value }
                    .thenByDescending { lastSeen[it.key] ?: 0L }
            )
            .first()
            .key
    }

    // -----------------------------------------------------------------------
    // Category / validity resolution
    // -----------------------------------------------------------------------

    const val BAND_HOURLY = "HOURLY"
    const val BAND_DAILY = "DAILY"
    const val BAND_WEEKLY = "WEEKLY"
    const val BAND_MONTHLY = "MONTHLY"

    private fun categoryOf(record: PurchaseRecord, byId: Map<String, OfferItem>): OfferCategory? {
        byId[record.offerId]?.let { offer ->
            return offer.category.takeIf { it.isRealCategory() }
        }
        return inferCategory("${record.offerName} ${record.allowance}")
    }

    private fun favouriteCategoryFromHearts(
        favouriteIds: Set<String>,
        byId: Map<String, OfferItem>
    ): OfferCategory? = favouriteIds
        .mapNotNull { byId[it]?.category }
        .filter { it.isRealCategory() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .firstOrNull()
        ?.key

    private fun OfferCategory.isRealCategory(): Boolean =
        this != OfferCategory.ALL && this != OfferCategory.FAVOURITES

    /**
     * Best-effort category from the text stored on the record, used only when the
     * catalogue is not available to resolve the real offer.
     */
    fun inferCategory(text: String): OfferCategory? {
        val t = text.lowercase()
        return when {
            t.contains("sms") || t.contains("text") -> OfferCategory.SMS
            t.contains("min") || t.contains("talktime") || t.contains("call") -> OfferCategory.MINUTES
            t.contains("gb") || t.contains("mb") || t.contains("data") -> OfferCategory.DATA
            else -> null
        }
    }

    /** Validity band of a catalogue offer: explicit band first, else parsed from the text. */
    fun bandOf(offer: OfferItem): String =
        if (offer.validityBand.isNotBlank()) normaliseBand(offer.validityBand)
        else parseBand(offer.validity)

    private fun bandOf(record: PurchaseRecord, byId: Map<String, OfferItem>): String {
        byId[record.offerId]?.let { return bandOf(it) }
        return parseBand("${record.offerName} ${record.allowance}")
    }

    private fun normaliseBand(raw: String): String = when (raw.trim().uppercase()) {
        BAND_HOURLY -> BAND_HOURLY
        BAND_DAILY -> BAND_DAILY
        BAND_WEEKLY -> BAND_WEEKLY
        BAND_MONTHLY -> BAND_MONTHLY
        else -> parseBand(raw)
    }

    private fun parseBand(text: String): String {
        val v = text.lowercase()
        return when {
            v.contains("month") -> BAND_MONTHLY
            v.contains("week") -> BAND_WEEKLY
            v.contains("day") || v.contains("24") || v.contains("midnight") -> BAND_DAILY
            v.contains("hour") || v.contains("hr") -> BAND_HOURLY
            else -> ""
        }
    }

    // -----------------------------------------------------------------------
    // Time of day
    // -----------------------------------------------------------------------

    /**
     * Dominant Nairobi time band:
     * MORNING 05–11, AFTERNOON 12–16, EVENING 17–21, NIGHT 22–04.
     * Ties resolve in that declared order; no history at all stays UNKNOWN.
     */
    private fun timeProfileOf(hourWeights: DoubleArray): BuyerTimeProfile {
        var morning = 0.0
        var afternoon = 0.0
        var evening = 0.0
        var night = 0.0
        for (hour in 0 until 24) {
            val w = hourWeights[hour]
            when (hour) {
                in 5..11 -> morning += w
                in 12..16 -> afternoon += w
                in 17..21 -> evening += w
                else -> night += w // 22, 23, 0..4
            }
        }
        var best = BuyerTimeProfile.UNKNOWN
        var bestWeight = 0.0
        if (morning > bestWeight) {
            best = BuyerTimeProfile.MORNING
            bestWeight = morning
        }
        if (afternoon > bestWeight) {
            best = BuyerTimeProfile.AFTERNOON
            bestWeight = afternoon
        }
        if (evening > bestWeight) {
            best = BuyerTimeProfile.EVENING
            bestWeight = evening
        }
        if (night > bestWeight) {
            best = BuyerTimeProfile.NIGHT
        }
        return best
    }

    // -----------------------------------------------------------------------
    // Frequency
    // -----------------------------------------------------------------------

    /**
     * Frequency band, evaluated in this exact order:
     *
     * 1. **NEW** — fewer than [MIN_PURCHASES_FOR_FREQUENCY] (3) learned purchases.
     * 2. **HEAVY** — average of at least [HEAVY_PURCHASES_PER_WEEK] (5) purchases
     *    per week across the observed span (span = first learned purchase → now,
     *    in whole Nairobi days, counted as at least one week).
     * 3. **DAILY** — bought on at least [DAILY_ACTIVE_DAYS_IN_LAST_7] (5) distinct
     *    Nairobi days out of the last 7.
     * 4. **WEEKEND** — at least [WEEKEND_SHARE] (70%) of learned purchases fall on
     *    a Nairobi Saturday or Sunday.
     * 5. **OCCASIONAL** — everything else.
     */
    private fun frequencyOf(learnable: List<PurchaseRecord>, nowMillis: Long): BuyerFrequency {
        val total = learnable.size
        if (total < MIN_PURCHASES_FOR_FREQUENCY) return BuyerFrequency.NEW

        val nowDay = NairobiTime.epochDay(nowMillis)
        val firstDay = learnable.minOf { NairobiTime.epochDay(it.timestampMillis) }
        val spanDays = (nowDay - firstDay + 1L).coerceAtLeast(1L)
        val weeks = (spanDays.toDouble() / 7.0).coerceAtLeast(1.0)
        val perWeek = total.toDouble() / weeks
        if (perWeek >= HEAVY_PURCHASES_PER_WEEK) return BuyerFrequency.HEAVY

        val activeDaysInLast7 = learnable
            .map { NairobiTime.epochDay(it.timestampMillis) }
            .filter { it in (nowDay - 6L)..nowDay }
            .distinct()
            .size
        if (activeDaysInLast7 >= DAILY_ACTIVE_DAYS_IN_LAST_7) return BuyerFrequency.DAILY

        val weekendShare = learnable.count { NairobiTime.isWeekend(it.timestampMillis) }
            .toDouble() / total
        if (weekendShare >= WEEKEND_SHARE) return BuyerFrequency.WEEKEND

        return BuyerFrequency.OCCASIONAL
    }

    // -----------------------------------------------------------------------
    // Numbers
    // -----------------------------------------------------------------------

    /**
     * The M-Pesa payment number used most (recency-weighted). Numbers typed in
     * different formats are folded together, and the form returned is the one
     * most recently typed so checkout shows the customer their own spelling.
     */
    private fun preferredPayerNumberOf(learnable: List<PurchaseRecord>, nowMillis: Long): String {
        val withPayer = learnable.filter { it.payerNumber.isNotBlank() }
        val key = weightedTop(withPayer, nowMillis) { numberKey(it.payerNumber) } ?: return ""
        return withPayer
            .filter { numberKey(it.payerNumber) == key }
            .maxByOrNull { it.timestampMillis }
            ?.payerNumber
            ?.trim()
            .orEmpty()
    }

    /**
     * Recipient numbers, most-used first, de-duplicated across formats
     * (`0700…`, `254700…`, `+254700…` are one recipient). Numbers seen only in
     * [recentRecipients] are appended with count 0 so checkout can still offer
     * them without them counting as purchase history.
     */
    private fun buildRecipients(
        learnable: List<PurchaseRecord>,
        recentRecipients: List<String>
    ): List<RecipientAffinity> {
        val byKey = LinkedHashMap<String, RecipientAffinity>()

        learnable.sortedBy { it.timestampMillis }.forEach { record ->
            val raw = record.recipientNumber.trim()
            if (raw.isEmpty()) return@forEach
            val key = numberKey(raw) ?: return@forEach
            val existing = byKey[key]
            byKey[key] = if (existing == null) {
                RecipientAffinity(raw, 1, record.timestampMillis)
            } else {
                existing.copy(
                    count = existing.count + 1,
                    lastUsedAtMillis = maxOf(existing.lastUsedAtMillis, record.timestampMillis)
                )
            }
        }

        recentRecipients.forEach { candidate ->
            val raw = candidate.trim()
            if (raw.isEmpty()) return@forEach
            val key = numberKey(raw) ?: return@forEach
            if (!byKey.containsKey(key)) byKey[key] = RecipientAffinity(raw, 0, 0L)
        }

        return byKey.values.sortedWith(
            compareByDescending<RecipientAffinity> { it.count }
                .thenByDescending { it.lastUsedAtMillis }
        )
    }

    /**
     * Canonical grouping key for a phone number. Never logged, never sent
     * anywhere — it only exists to fold formats of the same number together.
     */
    private fun numberKey(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        KenyanPhone.toE164(trimmed)?.let { return it }
        return trimmed.filter { it.isDigit() }.takeIf { it.isNotEmpty() }
    }
}
