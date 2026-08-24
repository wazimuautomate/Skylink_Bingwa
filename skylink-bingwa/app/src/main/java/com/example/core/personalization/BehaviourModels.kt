package com.example.core.personalization

import com.example.core.model.OfferCategory

/**
 * On-device behaviour models for the Skylink Bingwa personalization engine.
 *
 * PRIVACY (CLAUDE.md §10): every value in this file is derived from, and stays
 * on, this installation. Nothing here is ever uploaded, attached to a network
 * request, or shared with the server. Clearing app data or uninstalling removes
 * all of it. No code in this package may perform I/O other than the app's own
 * private DataStore ([PersonalizationStore]).
 *
 * These models describe **what the customer bought from Skylink Bingwa** — purchase
 * behaviour only. They never describe data usage, browsing, or need, and no
 * downstream surface may present them as such (CLAUDE.md §8).
 */

/** Which part of the Nairobi day the customer usually buys in. */
enum class BuyerTimeProfile { MORNING, AFTERNOON, EVENING, NIGHT, UNKNOWN }

/** How often the customer buys, used for ordering and reminder eligibility. */
enum class BuyerFrequency { NEW, OCCASIONAL, WEEKEND, DAILY, HEAVY }

/**
 * A number this installation has bought for, with how often and when it was last
 * used. [number] keeps the form the customer originally typed so checkout can
 * offer it back verbatim; duplicates in other formats are folded into one entry.
 */
data class RecipientAffinity(
    val number: String = "",
    val count: Int = 0,
    val lastUsedAtMillis: Long = 0L
)

/**
 * The learned, installation-local picture of how this customer buys.
 *
 * Every field defaults, both because Moshi reflection requires it for
 * persistence and because a fresh install must deserialise/construct cleanly to
 * [EMPTY]. An [EMPTY] profile means "we have learned nothing yet" and every
 * downstream surface must then behave exactly as it did before personalization
 * existed (see [isEmpty]).
 *
 * [purchaseCountByOfferId] and [lastPurchaseAtMillisByOfferId] are keyed by
 * offer id (a `String`) so the whole profile survives Moshi reflection.
 *
 * NOTE for other agents: [lastPurchaseAtMillisByOfferId] is an addition to the
 * originally specified shape. Without a per-offer "last bought at" the ranker
 * cannot compute per-offer recency or decide the "Bought yesterday" badge for
 * any offer other than the single most recent one. It is a defaulted, purely
 * additive field, so existing construction sites are unaffected.
 */
data class BehaviourProfile(
    val totalPurchases: Int = 0,
    val mostPurchasedOfferId: String = "",
    val mostPurchasedAmountKsh: Int = 0,
    val favouriteCategory: OfferCategory? = null,
    /** Validity band the customer buys most: "HOURLY" | "DAILY" | "WEEKLY" | "MONTHLY" | "". */
    val preferredValidity: String = "",
    val favouriteHourlyOfferId: String = "",
    val favouriteDailyOfferId: String = "",
    val timeProfile: BuyerTimeProfile = BuyerTimeProfile.UNKNOWN,
    /** Dominant Nairobi hour-of-day (0..23), or -1 when unknown. */
    val usualPurchaseHour: Int = -1,
    val frequency: BuyerFrequency = BuyerFrequency.NEW,
    val preferredPayerNumber: String = "",
    val topRecipients: List<RecipientAffinity> = emptyList(),
    val purchaseCountByOfferId: Map<String, Int> = emptyMap(),
    val lastPurchaseAtMillis: Long = 0L,
    val lastPurchaseOfferId: String = "",
    val lastPurchaseAtMillisByOfferId: Map<String, Long> = emptyMap()
) {
    companion object {
        val EMPTY = BehaviourProfile()
    }
}

/**
 * True when there is no usable purchase history to personalise from.
 *
 * This is the single regression guard for the whole feature: when it is true,
 * [OfferRanker.rank] returns the input untouched, Home keeps its existing
 * ordering and shows no badges, and [HabitReminderPolicy] never nudges. Only
 * real, locally recorded purchases can flip it — recipient numbers alone are
 * deliberately not enough, so a fresh install always looks exactly as before.
 */
fun BehaviourProfile.isEmpty(): Boolean = totalPurchases <= 0

// ---------------------------------------------------------------------------
// Checkout suggestion surface (pure; consumed by the purchase flow)
// ---------------------------------------------------------------------------

/**
 * The M-Pesa payment number to pre-fill at checkout: the number this
 * installation pays with most (weighted towards recent payments), falling back
 * to [fallback] (normally the profile's own primary number) when nothing has
 * been learned. Never a "Phone number" — always the M-Pesa payment number
 * (CLAUDE.md §7).
 */
fun BehaviourProfile.suggestedPayerNumber(fallback: String = ""): String =
    preferredPayerNumber.ifEmpty { fallback }

/**
 * Bundle recipient numbers to offer as quick chips, most-used first, already
 * de-duplicated across number formats. Purely a typing shortcut.
 */
fun BehaviourProfile.suggestedRecipients(limit: Int = 3): List<String> =
    topRecipients.take(limit.coerceAtLeast(0)).map { it.number }
