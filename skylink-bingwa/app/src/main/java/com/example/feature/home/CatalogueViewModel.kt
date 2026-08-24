package com.example.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.OfferDailyState
import com.example.core.model.OfferItem
import com.example.core.model.Promotion
import com.example.core.model.PurchaseRecord
import com.example.core.personalization.BehaviourProfile
import com.example.core.personalization.PersonalBadge
import com.example.core.personalization.suggestedPayerNumber
import com.example.core.personalization.suggestedRecipients
import com.example.data.fake.BingwaRepository
import com.example.data.fake.OfferFilterState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Immutable Home state (Plan.md §5.2 content order). */
data class HomeUiState(
    val loading: Boolean = true,
    val greetingName: String = "",
    val isOffline: Boolean = false,
    val promotions: List<Promotion> = emptyList(),
    val sections: HomeSections = HomeSections(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
    val purchases: List<PurchaseRecord> = emptyList(),
    val recipientNumber: String = "",
    val nowMillis: Long = 0L,
    /**
     * At most a couple of quiet personal labels ("Buy again", "Your usual
     * bundle", "Bought yesterday"), keyed by offer id. Always empty until this
     * installation has real local purchase history, so a fresh install renders
     * exactly as before. Derived on-device only — never uploaded (CLAUDE.md §10).
     */
    val personalBadges: Map<String, PersonalBadge> = emptyMap(),
    /**
     * The learned on-device behaviour profile behind [personalBadges] and the
     * section ordering. Exposed so checkout can pre-fill the usual M-Pesa payment
     * number and recent bundle recipients without re-deriving anything.
     */
    val behaviourProfile: BehaviourProfile = BehaviourProfile.EMPTY
) {
    val hasAnyContent: Boolean
        get() = sections.popular.isNotEmpty() || sections.favourites.isNotEmpty() ||
            sections.buyAgain.isNotEmpty() || sections.boughtToday.isNotEmpty() ||
            sections.moreOffers.isNotEmpty() || sections.suggestions.isNotEmpty()
}

/** Immutable Offers state: filtered/sorted results plus which empty case applies. */
data class OffersUiState(
    val loading: Boolean = true,
    val isOffline: Boolean = false,
    val filter: OfferFilterState = OfferFilterState(),
    val results: List<OfferItem> = emptyList(),
    val totalOffers: Int = 0,
    val purchases: List<PurchaseRecord> = emptyList(),
    val recipientNumber: String = "",
    val nowMillis: Long = 0L
) {
    val resultCount: Int get() = results.size
    /** True when there are offers in the catalogue but the current filters hide them all. */
    val emptyFromFilters: Boolean get() = results.isEmpty() && totalOffers > 0
}

/**
 * Screen-level ViewModel for the catalogue experience (Home + Offers). It only
 * derives immutable UI state from the repository flows and forwards user intents;
 * it holds no mutable catalogue truth of its own. A [clock] is injected so the
 * daily-purchase and promotion logic is deterministic under test.
 *
 * [behaviourProfileFlow] carries the on-device personalization profile. This
 * ViewModel deliberately does **not** read the personalization store itself: the
 * caller owns construction and background refresh, and passes the profile in.
 * It defaults to a flow of [BehaviourProfile.EMPTY], which means "nothing learned
 * yet" and reproduces the exact pre-personalization Home ordering, so existing
 * construction sites and tests are unaffected.
 */
class CatalogueViewModel(
    private val repository: BingwaRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val behaviourProfileFlow: StateFlow<BehaviourProfile> =
        MutableStateFlow(BehaviourProfile.EMPTY)
) : ViewModel() {

    // isOffline + catalogueLoading folded into one flow so each UI state combines
    // exactly five flows (the type-safe combine arity), no casts.
    private val connectivity: Flow<Pair<Boolean, Boolean>> =
        combine(repository.isOffline, repository.catalogueLoading) { offline, loading -> offline to loading }

    // Home additionally needs the on-device behaviour profile. It is folded into
    // the same slot rather than added as a sixth flow, and deliberately NOT into
    // [connectivity] — the Offers screen is not personalised, so a profile refresh
    // must not make it recompute (CLAUDE.md §11).
    private val homeSignals: Flow<Triple<Boolean, Boolean, BehaviourProfile>> =
        combine(
            repository.isOffline,
            repository.catalogueLoading,
            behaviourProfileFlow
        ) { offline, loading, behaviour -> Triple(offline, loading, behaviour) }

    val homeUiState: StateFlow<HomeUiState> =
        combine(
            repository.offers,
            repository.promotions,
            repository.purchases,
            repository.userProfile,
            homeSignals
        ) { offers, promotions, purchases, profile, flags ->
            val offline = flags.first
            val loading = flags.second
            val behaviour = flags.third
            val now = clock()
            val personalized = personalizeHomeSections(
                deriveHomeSections(offers, purchases, now),
                behaviour,
                now
            )
            HomeUiState(
                loading = loading && offers.isEmpty(),
                greetingName = profile.name,
                isOffline = offline,
                promotions = selectPromotions(promotions, offers, now, seed = nairobiDayIndex(now)),
                sections = personalized.sections,
                purchases = purchases,
                recipientNumber = profile.primaryNumber,
                nowMillis = now,
                personalBadges = personalized.badges,
                behaviourProfile = behaviour
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    val offersUiState: StateFlow<OffersUiState> =
        combine(
            repository.offers,
            repository.filterState,
            repository.purchases,
            repository.userProfile,
            connectivity
        ) { offers, filter, purchases, profile, flags ->
            val (offline, loading) = flags
            OffersUiState(
                loading = loading && offers.isEmpty(),
                isOffline = offline,
                filter = filter,
                results = filterAndSortOffers(offers, filter),
                totalOffers = offers.size,
                purchases = purchases,
                recipientNumber = profile.primaryNumber,
                nowMillis = clock()
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OffersUiState())

    // --- Intents ---------------------------------------------------------

    fun setSearchQuery(query: String) = repository.setSearchQuery(query)
    fun setCategory(category: com.example.core.model.OfferCategory) = repository.setCategoryFilter(category)
    fun setFilter(filter: OfferFilterState) = repository.setFilterState(filter)
    fun clearFilters() = repository.clearFilters()

    fun toggleFavourite(offer: OfferItem) = repository.setFavourite(offer.id, !offer.isFavourite)
    fun setFavourite(offerId: String, isFavourite: Boolean) = repository.setFavourite(offerId, isFavourite)

    fun refresh() {
        viewModelScope.launch { repository.refreshCatalogue() }
    }

    /** Daily-purchase presentation for an offer against the profile's own number. */
    fun dailyState(offer: OfferItem, state: HomeUiState): OfferDailyState =
        dailyStateFor(offer, state.purchases, state.recipientNumber, state.nowMillis)

    // --- On-device personalization (never leaves the device) ---------------

    /** The current learned behaviour profile. */
    val behaviourProfile: BehaviourProfile get() = behaviourProfileFlow.value

    /**
     * The M-Pesa payment number to pre-fill at checkout — the one this
     * installation pays with most — falling back to [fallback] (normally the
     * customer's own primary number) when nothing has been learned yet.
     */
    fun suggestedPayerNumber(fallback: String = ""): String =
        behaviourProfileFlow.value.suggestedPayerNumber(fallback)

    /** Bundle recipient numbers to offer as quick chips, most-used first. */
    fun suggestedRecipients(limit: Int = 3): List<String> =
        behaviourProfileFlow.value.suggestedRecipients(limit)
}
