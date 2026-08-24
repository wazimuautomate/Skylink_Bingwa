package com.example.feature.home

import com.example.core.model.OfferCategory
import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionKind
import com.example.data.catalogue.RemoteBillboardSource
import com.example.data.fake.FakeBingwaRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * State tests for [CatalogueViewModel]. The clock is fixed so promotion and
 * daily-purchase derivation is deterministic. Because the exposed state uses
 * `WhileSubscribed`, every test keeps a collector alive on `backgroundScope`
 * before reading `.value`, and drains work with `runCurrent()` after intents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueViewModelTest {

    // 2024-01-10 12:00 Africa/Nairobi; fixed so selectPromotions/day logic is stable.
    private val fixedClock: () -> Long = { 1_704_877_200_000L }
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = CatalogueViewModel(FakeBingwaRepositoryImpl(), clock = fixedClock)

    /** Billboards are never hardcoded, so a test that needs some must publish them. */
    private class FakeBillboards(private val list: List<Promotion>?) : RemoteBillboardSource {
        override suspend fun fetch(): List<Promotion>? = list
    }

    private fun promo(id: String, weight: Int) = Promotion(
        id = id,
        kind = PromotionKind.OFFER,
        tag = "HOT",
        headline = "Headline $id",
        subhead = "Body $id",
        ctaLabel = "Buy now",
        accent = PromotionAccent.GREEN,
        priorityWeight = weight
    )

    @Test
    fun `home state exposes favourites`() = runTest(mainDispatcher) {
        val vm = newViewModel()
        backgroundScope.launch { vm.homeUiState.collect {} }
        backgroundScope.launch { vm.offersUiState.collect {} }
        runCurrent()

        // Fresh install has no seeded favourites; favourite one so the section shows.
        vm.toggleFavourite(vm.offersUiState.value.results.first())
        runCurrent()

        assertTrue(vm.homeUiState.value.sections.favourites.isNotEmpty())
    }

    @Test
    fun `home shows no billboard until one is published`() = runTest(mainDispatcher) {
        val vm = newViewModel()
        backgroundScope.launch { vm.homeUiState.collect {} }
        runCurrent()

        assertTrue(vm.homeUiState.value.promotions.isEmpty())
    }

    @Test
    fun `published billboards lead with the highest weight`() = runTest(mainDispatcher) {
        val repository = FakeBingwaRepositoryImpl(
            billboardSource = FakeBillboards(
                listOf(promo("low", weight = 10), promo("high", weight = 90), promo("mid", weight = 50))
            )
        )
        repository.syncBillboards()
        val vm = CatalogueViewModel(repository, clock = fixedClock)
        backgroundScope.launch { vm.homeUiState.collect {} }
        runCurrent()

        val promotions = vm.homeUiState.value.promotions
        assertTrue(promotions.isNotEmpty())
        assertEquals(promotions.maxOf { it.priorityWeight }, promotions.first().priorityWeight)
        assertEquals("high", promotions.first().id)
    }

    @Test
    fun `toggleFavourite adds then removes the offer from favourites`() = runTest(mainDispatcher) {
        val vm = newViewModel()
        backgroundScope.launch { vm.homeUiState.collect {} }
        backgroundScope.launch { vm.offersUiState.collect {} }
        runCurrent()

        val offer = vm.offersUiState.value.results.first { !it.isFavourite }

        vm.toggleFavourite(offer)
        runCurrent()
        assertTrue(vm.homeUiState.value.sections.favourites.any { it.id == offer.id })

        val nowFavourite = vm.homeUiState.value.sections.favourites.first { it.id == offer.id }
        vm.toggleFavourite(nowFavourite)
        runCurrent()
        assertTrue(vm.homeUiState.value.sections.favourites.none { it.id == offer.id })
    }

    @Test
    fun `search and category narrow results then clearFilters restores everything`() = runTest(mainDispatcher) {
        val vm = newViewModel()
        backgroundScope.launch { vm.offersUiState.collect {} }
        runCurrent()

        val total = vm.offersUiState.value.totalOffers
        assertTrue(total > 0)

        vm.setSearchQuery("SMS")
        runCurrent()
        vm.setCategory(OfferCategory.SMS)
        runCurrent()

        val results = vm.offersUiState.value.results
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.category == OfferCategory.SMS })

        vm.clearFilters()
        runCurrent()
        assertEquals(total, vm.offersUiState.value.results.size)
    }

    @Test
    fun `a search that matches nothing sets emptyFromFilters`() = runTest(mainDispatcher) {
        val vm = newViewModel()
        backgroundScope.launch { vm.offersUiState.collect {} }
        runCurrent()

        vm.setSearchQuery("zzzzz")
        runCurrent()

        val state = vm.offersUiState.value
        assertTrue(state.results.isEmpty())
        assertTrue(state.emptyFromFilters)
    }
}
