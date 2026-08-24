package com.example.feature.home

import com.example.core.model.DailyStateKind
import com.example.core.model.OfferCategory
import com.example.core.model.OfferDailyState
import com.example.core.model.OfferItem
import com.example.core.model.PaymentStatus
import com.example.core.model.Promotion
import com.example.core.model.PurchasePolicy
import com.example.core.model.PurchaseRecord
import com.example.core.personalization.BehaviourProfile
import com.example.core.personalization.OfferRanker
import com.example.core.personalization.PersonalBadge
import com.example.core.personalization.isEmpty
import com.example.data.fake.OfferFilterState
import com.example.data.fake.SortOption
import com.example.data.fake.ValidityFilter
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure, framework-free catalogue logic for Phase 3 (Home, Offers, favourites,
 * daily purchase awareness, promotions). Everything here is deterministic and
 * unit-tested — no Compose, no Android UI, no clocks read internally (callers
 * pass `nowMillis`) so tests are stable.
 *
 * Product behaviour follows Plan.md §5.2/5.3/5.12/5.13; purchase awareness is
 * never a data-usage recommender.
 */

private val NAIROBI: TimeZone = TimeZone.getTimeZone("Africa/Nairobi")

/** Absolute day number in Africa/Nairobi, so "today" resets at local midnight. */
fun nairobiDayIndex(millis: Long): Long {
    val cal = Calendar.getInstance(NAIROBI)
    cal.timeInMillis = millis
    val year = cal.get(Calendar.YEAR)
    val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
    // Encode as a monotonically increasing key; exact value is irrelevant, only equality/order.
    return year * 1000L + dayOfYear
}

fun isSameNairobiDay(a: Long, b: Long): Boolean = nairobiDayIndex(a) == nairobiDayIndex(b)

/**
 * Approximate validity length in minutes, used only for Shortest/Longest sort.
 * Parses the human validity string ("1 hour", "24 hours", "7 days", "Weekend",
 * "Till midnight"). Unknown strings sort in the middle.
 */
fun validityRankMinutes(validity: String): Int {
    val v = validity.lowercase().trim()
    val number = Regex("\\d+").find(v)?.value?.toIntOrNull()
    return when {
        v.contains("month") -> (number ?: 1) * 30 * 24 * 60
        v.contains("week") || v.contains("weekend") -> (number ?: 1) * 7 * 24 * 60
        v.contains("day") -> (number ?: 1) * 24 * 60
        v.contains("midnight") -> 12 * 60 // "midnight": treat as a partial day
        v.contains("hour") || v.contains("hr") -> (number ?: 1) * 60
        v.contains("min") -> number ?: 30
        else -> 24 * 60
    }
}

private fun matchesValidityFilter(offer: OfferItem, filter: ValidityFilter): Boolean {
    if (filter == ValidityFilter.ALL) return true
    // Prefer the explicit band (HOURLY/DAILY/WEEKLY/MONTHLY) when the offer sets one.
    if (offer.validityBand.isNotEmpty()) {
        return offer.validityBand.equals(filter.name, ignoreCase = true)
    }
    val v = offer.validity.lowercase()
    return when (filter) {
        ValidityFilter.ALL -> true
        ValidityFilter.HOURLY -> v.contains("hour") || v.contains("hr")
        ValidityFilter.DAILY -> v.contains("day") || v.contains("24") || v.contains("midnight")
        ValidityFilter.WEEKLY -> v.contains("week") || v.contains("7")
        ValidityFilter.MONTHLY -> v.contains("month") || v.contains("30")
    }
}

private fun matchesSearch(offer: OfferItem, rawQuery: String): Boolean {
    val query = rawQuery.trim().lowercase()
    if (query.isEmpty()) return true
    return offer.name.lowercase().contains(query) ||
        offer.allowance.lowercase().contains(query) ||
        offer.category.label.lowercase().contains(query) ||
        offer.description.lowercase().contains(query) ||
        offer.priceKsh.toString().contains(query)
}

/** Full Offers-screen pipeline: category + favourites + search + price + validity, then sort. */
fun filterAndSortOffers(offers: List<OfferItem>, filter: OfferFilterState): List<OfferItem> {
    val filtered = offers.filter { offer ->
        val matchesCategory = when (filter.selectedCategory) {
            OfferCategory.ALL -> true
            OfferCategory.FAVOURITES -> offer.isFavourite
            else -> offer.category == filter.selectedCategory
        }
        matchesCategory &&
            matchesSearch(offer, filter.searchQuery) &&
            offer.priceKsh <= filter.maxPriceKsh &&
            matchesValidityFilter(offer, filter.selectedValidity)
    }
    return sortOffers(filtered, filter.selectedSort)
}

fun sortOffers(offers: List<OfferItem>, sort: SortOption): List<OfferItem> = when (sort) {
    // "Popular" keeps flagged-popular first, then the highest-value offers, so the
    // list still reads sensibly when few offers are flagged.
    SortOption.POPULAR -> offers.sortedWith(
        compareByDescending<OfferItem> { it.isPopular }.thenByDescending { it.priceKsh }
    )
    SortOption.LOWEST_PRICE -> offers.sortedBy { it.priceKsh }
    SortOption.HIGHEST_VALUE -> offers.sortedByDescending { it.priceKsh }
    SortOption.SHORTEST_VALIDITY -> offers.sortedBy { validityRankMinutes(it.validity) }
    SortOption.LONGEST_VALIDITY -> offers.sortedByDescending { validityRankMinutes(it.validity) }
}

// ---------------------------------------------------------------------------
// Daily purchase awareness (presentation only)
// ---------------------------------------------------------------------------

/**
 * Presentation-level daily state for [offer] and a specific [recipientNumber],
 * derived only from this installation's Skylink Bingwa payments (Plan.md §5.12).
 * Offline installs can only see local history — that limitation is honoured by
 * callers, not hidden here.
 */
fun dailyStateFor(
    offer: OfferItem,
    purchases: List<PurchaseRecord>,
    recipientNumber: String,
    nowMillis: Long
): OfferDailyState {
    val todaysForRecipient = purchases.filter {
        it.offerId == offer.id &&
            normaliseNumber(it.recipientNumber) == normaliseNumber(recipientNumber) &&
            isSameNairobiDay(it.timestampMillis, nowMillis)
    }
    val received = todaysForRecipient.count { it.status == PaymentStatus.RECEIVED }
    val waiting = todaysForRecipient.count { it.status == PaymentStatus.WAITING_VERIFY }

    return when (offer.purchasePolicy) {
        PurchasePolicy.MULTIPLE_PER_DAY ->
            OfferDailyState(DailyStateKind.AVAILABLE, "Available today")

        PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY -> when {
            received > 0 -> OfferDailyState(DailyStateKind.AVAILABLE_TOMORROW, "Available again tomorrow")
            waiting > 0 -> OfferDailyState(DailyStateKind.WAITING_VERIFY, "Waiting to verify")
            else -> OfferDailyState(DailyStateKind.AVAILABLE, "Available today")
        }

        PurchasePolicy.MAX_PER_RECIPIENT_PER_DAY -> {
            val max = offer.maxPurchasesPerDay ?: 1
            val left = (max - received).coerceAtLeast(0)
            when {
                left <= 0 -> OfferDailyState(DailyStateKind.AVAILABLE_TOMORROW, "Available again tomorrow")
                waiting > 0 && left - waiting <= 0 -> OfferDailyState(DailyStateKind.WAITING_VERIFY, "Waiting to verify")
                else -> OfferDailyState(
                    DailyStateKind.PURCHASES_LEFT,
                    if (left == 1) "1 purchase left today" else "$left purchases left today",
                    purchasesLeft = left
                )
            }
        }
    }
}

/** True when at least one successful purchase for this offer happened today (any recipient). */
fun isBoughtTodayByInstall(offer: OfferItem, purchases: List<PurchaseRecord>, nowMillis: Long): Boolean =
    purchases.any {
        it.offerId == offer.id &&
            it.status == PaymentStatus.RECEIVED &&
            isSameNairobiDay(it.timestampMillis, nowMillis)
    }

/**
 * The numbers that already received this offer today, newest first, de-duplicated
 * (Plan.md §5.12). This is what lets a list say "Already bought today for
 * 0712 345 678" instead of a bare "Bought today", and it resets by itself at
 * Nairobi midnight because the day comparison — not a stored flag — decides.
 *
 * A purchase still waiting to be verified counts: the customer has paid, and
 * asking them to pay again for the same number the same day is the exact mistake
 * this is here to prevent.
 */
fun boughtTodayRecipients(
    offer: OfferItem,
    purchases: List<PurchaseRecord>,
    nowMillis: Long
): List<String> = purchases
    .asSequence()
    .filter {
        it.offerId == offer.id &&
            (it.status == PaymentStatus.RECEIVED || it.status == PaymentStatus.WAITING_VERIFY) &&
            isSameNairobiDay(it.timestampMillis, nowMillis)
    }
    .sortedByDescending { it.timestampMillis }
    .map { it.recipientNumber }
    .filter { it.isNotBlank() }
    .distinctBy { normaliseNumber(it) }
    .toList()

/**
 * The short note an offer list shows under a once-per-day offer that has already
 * been used today, or null when there is nothing to say. Names the number the
 * bundle went to, because "bought today" without a number is what makes a
 * customer buy the same restricted bundle twice.
 */
fun boughtTodayNote(
    offer: OfferItem,
    purchases: List<PurchaseRecord>,
    nowMillis: Long
): String? {
    if (offer.purchasePolicy == PurchasePolicy.MULTIPLE_PER_DAY) return null
    val numbers = boughtTodayRecipients(offer, purchases, nowMillis)
    return when {
        numbers.isEmpty() -> null
        numbers.size == 1 -> "Already bought today for ${displayNumber(numbers[0])}"
        else -> "Already bought today for ${displayNumber(numbers[0])} +${numbers.size - 1} more"
    }
}

/**
 * The sentence shown at checkout when the number typed in has already had this
 * once-per-day offer today, or null when the purchase may proceed. The customer
 * is told what to do next — use another number, or come back after midnight —
 * rather than only being blocked.
 */
fun repeatPurchaseBlockMessage(
    offer: OfferItem,
    purchases: List<PurchaseRecord>,
    recipientNumber: String,
    nowMillis: Long
): String? {
    if (recipientNumber.isBlank()) return null
    val state = dailyStateFor(offer, purchases, recipientNumber, nowMillis)
    if (state.purchasable) return null
    val number = displayNumber(recipientNumber)
    return when (state.kind) {
        DailyStateKind.WAITING_VERIFY ->
            "A ${offer.name} purchase for $number today is still being verified. Wait for that one " +
                "to finish before buying it again for this number."
        else ->
            "$number already got ${offer.name} today. Safaricom allows this bundle once a day per " +
                "number — it can be bought for this number again after midnight. You can enter a " +
                "different number to buy it now."
    }
}

/**
 * Two phone numbers are the same line when their last nine digits match, so
 * 0712345678, 254712345678 and +254 712 345 678 all compare equal. Stripping
 * non-digits alone was not enough: the once-per-day ledger would then treat a
 * number saved in international form as a different line and let the same bundle
 * be bought for it twice in a day.
 */
private fun normaliseNumber(number: String): String =
    number.filter { it.isDigit() }.takeLast(9)

/** 254712345678 / 0712345678 → "0712 345 678", without depending on the payment layer. */
private fun displayNumber(number: String): String {
    val digits = normaliseNumber(number)
    val local = when {
        digits.length == 12 && digits.startsWith("254") -> "0" + digits.substring(3)
        digits.length == 9 -> "0$digits"
        else -> digits
    }
    return if (local.length == 10) {
        "${local.substring(0, 4)} ${local.substring(4, 7)} ${local.substring(7)}"
    } else {
        number
    }
}

// ---------------------------------------------------------------------------
// Home sections (Plan.md §5.2 content order)
// ---------------------------------------------------------------------------

data class HomeSections(
    val popular: List<OfferItem>,
    val boughtToday: List<OfferItem>,
    val moreOffers: List<OfferItem>,
    val buyAgain: List<OfferItem>,
    val favourites: List<OfferItem>,
    val suggestions: List<OfferItem>
)

/**
 * Derive Home's ordered sections from the cached catalogue and this
 * installation's purchase history. Personalised but restrained (Plan.md §5.2):
 * we surface Popular, what was bought today, repeatable "Buy again", favourites
 * and a small "You might also like" set drawn from the categories the customer
 * actually buys/favourites — never a data-usage recommendation.
 */
fun deriveHomeSections(
    offers: List<OfferItem>,
    purchases: List<PurchaseRecord>,
    nowMillis: Long,
    popularLimit: Int = 6,
    suggestionLimit: Int = 4
): HomeSections {
    val byId = offers.associateBy { it.id }

    val popular = offers.filter { it.isPopular }
        .sortedByDescending { it.priceKsh }
        .take(popularLimit)

    val boughtTodayIds = purchases
        .filter { it.status == PaymentStatus.RECEIVED && isSameNairobiDay(it.timestampMillis, nowMillis) }
        .map { it.offerId }
        .toSet()
    val boughtToday = offers.filter { it.id in boughtTodayIds }

    // "More offers you can buy" only earns its place once a once-per-day offer
    // has actually been used today (Plan.md §5.2 item 7).
    val usedAOncePerDay = boughtToday.any { it.purchasePolicy == PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY }
    val moreOffers = if (usedAOncePerDay) {
        offers.filter { it.id !in boughtTodayIds && it.purchasePolicy != PurchasePolicy.ONCE_PER_RECIPIENT_PER_DAY }
            .sortedByDescending { it.isPopular }
            .take(popularLimit)
    } else {
        emptyList()
    }

    // "Buy again": repeatable offers the customer has actually bought, newest first, de-duplicated.
    val buyAgain = purchases
        .asSequence()
        .filter { it.status == PaymentStatus.RECEIVED }
        .sortedByDescending { it.timestampMillis }
        .map { it.offerId }
        .distinct()
        .mapNotNull { byId[it] }
        .filter { it.purchasePolicy == PurchasePolicy.MULTIPLE_PER_DAY }
        .take(popularLimit)
        .toList()

    val favourites = offers.filter { it.isFavourite }
    val suggestions = suggestSimilar(offers, purchases, suggestionLimit)

    return HomeSections(
        popular = popular,
        boughtToday = boughtToday,
        moreOffers = moreOffers,
        buyAgain = buyAgain,
        favourites = favourites,
        suggestions = suggestions
    )
}

/**
 * "You might also like" — offers in the categories the customer favourites or
 * buys, excluding what they already favourite or bought today. Returns empty
 * when there is no signal, so the section stays silent rather than noisy.
 */
fun suggestSimilar(
    offers: List<OfferItem>,
    purchases: List<PurchaseRecord>,
    limit: Int = 4
): List<OfferItem> {
    val favouriteCategories = offers.filter { it.isFavourite }.map { it.category }
    val purchasedOfferIds = purchases.filter { it.status == PaymentStatus.RECEIVED }.map { it.offerId }.toSet()
    val purchasedCategories = offers.filter { it.id in purchasedOfferIds }.map { it.category }

    val affinity: Map<OfferCategory, Int> = (favouriteCategories + purchasedCategories)
        .filter { it != OfferCategory.ALL && it != OfferCategory.FAVOURITES }
        .groupingBy { it }
        .eachCount()

    if (affinity.isEmpty()) return emptyList()

    return offers
        .filter { !it.isFavourite && it.id !in purchasedOfferIds && affinity.containsKey(it.category) }
        .sortedWith(
            compareByDescending<OfferItem> { affinity[it.category] ?: 0 }
                .thenByDescending { it.isPopular }
                .thenByDescending { it.priceKsh }
        )
        .take(limit)
}

// ---------------------------------------------------------------------------
// On-device personalization of the Home sections
// ---------------------------------------------------------------------------

/**
 * Home's sections after the on-device behaviour profile has re-ordered them,
 * plus the badge (at most one) chosen for a handful of offers, keyed by offer id
 * so the UI can look one up without re-running any logic during composition.
 */
data class PersonalizedHome(
    val sections: HomeSections,
    val badges: Map<String, PersonalBadge> = emptyMap()
)

/**
 * Re-order Home from what this installation actually buys, and pick the small
 * "Buy again" / "Your usual bundle" / "Bought yesterday" labels.
 *
 * Everything here is derived from local purchase history only; nothing is
 * uploaded and nothing about data usage, need or browsing is inferred
 * (CLAUDE.md §8).
 *
 * **Fresh-install guarantee:** when [profile] is empty this returns [sections]
 * unchanged with no badges, so Home's ordering and labelling are byte-identical
 * to the behaviour before personalization existed.
 *
 * `boughtToday` is deliberately left alone — it is a factual record of today, so
 * re-ranking it would only make it harder to read.
 */
fun personalizeHomeSections(
    sections: HomeSections,
    profile: BehaviourProfile,
    nowMillis: Long
): PersonalizedHome {
    if (profile.isEmpty()) return PersonalizedHome(sections)

    // Badges appear only on the two sections the customer reads first, and only
    // a couple of them, so a card is never louder than the offer itself.
    val favourites = OfferRanker.rank(sections.favourites, profile, nowMillis, maxBadges = 2)
    val suggestions = OfferRanker.rank(sections.suggestions, profile, nowMillis, maxBadges = 1)

    val badges = LinkedHashMap<String, PersonalBadge>()
    favourites.forEach { ranked -> ranked.badge?.let { badges[ranked.offer.id] = it } }
    suggestions.forEach { ranked -> ranked.badge?.let { badges[ranked.offer.id] = it } }

    return PersonalizedHome(
        sections = sections.copy(
            popular = personalizeOrder(sections.popular, profile, nowMillis),
            moreOffers = personalizeOrder(sections.moreOffers, profile, nowMillis),
            buyAgain = personalizeOrder(sections.buyAgain, profile, nowMillis),
            favourites = favourites.map { it.offer },
            suggestions = suggestions.map { it.offer }
        ),
        badges = badges
    )
}

/**
 * Personalised ordering for one list of offers, with no badges. Returns the input
 * unchanged for an empty profile.
 */
fun personalizeOrder(
    offers: List<OfferItem>,
    profile: BehaviourProfile,
    nowMillis: Long
): List<OfferItem> {
    if (profile.isEmpty()) return offers
    return OfferRanker.rank(offers, profile, nowMillis, maxBadges = 0).map { it.offer }
}

// ---------------------------------------------------------------------------
// Promotion / announcement billboard selection (Plan.md §5.13)
// ---------------------------------------------------------------------------

/**
 * Pick the promotions shown on the Home billboard. We keep every slide whose
 * time window is currently active, then rank so the seller's biggest offers
 * (higher price / longer validity, encoded via [Promotion.priorityWeight]) and
 * announcements lead, and finally break ties with a caller-supplied [seed] so
 * the surface feels freshly shuffled per session without ever auto-rotating.
 *
 * A slide is intentionally NOT hidden just because its [Promotion.linkedOfferId]
 * is absent from the current catalogue. Admin-published (synced) billboards
 * routinely link to a server offer id that this install's cached catalogue may
 * not carry yet (a catalogue sync that hasn't run, or a different id scheme), so
 * gating visibility on the linked offer silently swallowed every synced OFFER
 * billboard — the reported "billboards never show" bug. Visibility now depends
 * only on the active window; an unresolvable OFFER CTA degrades gracefully to
 * "browse offers" in the action handler (see MainActivity.onPromotionAction).
 * A blank/uncapped window (start 0, end Long.MAX_VALUE) is always active.
 */
fun selectPromotions(
    pool: List<Promotion>,
    offers: List<OfferItem>,
    nowMillis: Long,
    seed: Long,
    max: Int = 5
): List<Promotion> {
    val active = pool.filter { promo -> promo.isActive(nowMillis) }
    if (active.isEmpty()) return emptyList()

    // Deterministic per-slide jitter from the seed keeps ordering stable within a
    // session but varied between sessions (the "randomly posts offers" feel).
    fun jitter(id: String): Int = ((id.hashCode().toLong() xor seed) and 0xFFFF).toInt()

    return active
        .sortedWith(
            compareByDescending<Promotion> { it.priorityWeight }
                .thenBy { jitter(it.id) }
        )
        .take(max)
}
