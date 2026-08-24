package com.example.data.fake

import com.example.core.model.DailyRule
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.data.catalogue.RemoteCatalogueSource
import com.example.data.persistence.PersistedState
import com.example.data.persistence.SnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueSyncTest {

    private class FakeCatalogue(private val list: List<OfferItem>?) : RemoteCatalogueSource {
        override suspend fun fetch(): List<OfferItem>? = list
    }

    /** In-memory snapshot store so restore/persist is observable without Android. */
    private class InMemoryStore(initial: PersistedState? = null) : SnapshotStore {
        var state: PersistedState? = initial
        override suspend fun load(): PersistedState? = state
        override suspend fun save(state: PersistedState) { this.state = state }
    }

    private fun offer(id: String, price: Int) = OfferItem(
        id = id, name = "$price bob", allowance = "$price bob", priceKsh = price,
        validity = "24 Hrs", validityBand = "Daily", category = OfferCategory.DATA,
        dailyRule = DailyRule.BUY_AGAIN_TODAY
    )

    @Test
    fun sync_replacesOffersWithServerList() = runTest {
        val repo = FakeBingwaRepositoryImpl(catalogueSource = FakeCatalogue(listOf(offer("srv_1", 42))))
        repo.syncCatalogue()
        assertEquals(1, repo.offers.value.size)
        assertEquals("srv_1", repo.offers.value.first().id)
    }

    @Test
    fun sync_nullKeepsLocalCatalogue() = runTest {
        val repo = FakeBingwaRepositoryImpl(catalogueSource = FakeCatalogue(null))
        val before = repo.offers.value.size
        assertTrue(before > 0)
        repo.syncCatalogue()
        assertEquals(before, repo.offers.value.size)
    }

    @Test
    fun sync_emptyKeepsLocalCatalogue() = runTest {
        val repo = FakeBingwaRepositoryImpl(catalogueSource = FakeCatalogue(emptyList()))
        val before = repo.offers.value.map { it.id }
        assertTrue(before.isNotEmpty())
        repo.syncCatalogue()
        assertEquals(before, repo.offers.value.map { it.id })
        assertEquals(0L, repo.catalogueVersion.value)
    }

    @Test
    fun sync_incompleteOfferRejectsWholePayloadAndKeepsLocalCatalogue() = runTest {
        // One valid offer plus one missing its validity → the payload is incomplete and
        // must be rejected wholesale; local offers and the version stay untouched.
        val incomplete = offer("srv_bad", 42).copy(validity = "")
        val repo = FakeBingwaRepositoryImpl(
            catalogueSource = FakeCatalogue(listOf(offer("srv_ok", 10), incomplete))
        )
        val before = repo.offers.value.map { it.id }
        repo.syncCatalogue()
        assertEquals(before, repo.offers.value.map { it.id })
        assertEquals(0L, repo.catalogueVersion.value)
    }

    @Test
    fun sync_zeroPriceOfferIsRejected() = runTest {
        val repo = FakeBingwaRepositoryImpl(
            catalogueSource = FakeCatalogue(listOf(offer("srv_ok", 10), offer("srv_free", 0)))
        )
        val before = repo.offers.value.map { it.id }
        repo.syncCatalogue()
        assertEquals(before, repo.offers.value.map { it.id })
        assertEquals(0L, repo.catalogueVersion.value)
    }

    @Test
    fun sync_validatedReplaceBumpsVersion() = runTest {
        val repo = FakeBingwaRepositoryImpl(
            catalogueSource = FakeCatalogue(listOf(offer("srv_1", 42), offer("srv_2", 50)))
        )
        assertEquals(0L, repo.catalogueVersion.value)
        repo.syncCatalogue()
        assertEquals(listOf("srv_1", "srv_2"), repo.offers.value.map { it.id })
        assertEquals(1L, repo.catalogueVersion.value)
    }

    @Test
    fun sync_preservesFavourite() = runTest {
        val existingId = FakeBingwaRepositoryImpl().offers.value.first().id
        val repo = FakeBingwaRepositoryImpl(catalogueSource = FakeCatalogue(listOf(offer(existingId, 42))))
        repo.setFavourite(existingId, true)
        repo.syncCatalogue()
        assertTrue(repo.offers.value.first { it.id == existingId }.isFavourite)
    }

    @Test
    fun validatedSync_persistsOffersAndVersion() = runTest {
        val store = InMemoryStore()
        val repo = FakeBingwaRepositoryImpl(
            catalogueSource = FakeCatalogue(listOf(offer("srv_1", 42), offer("srv_2", 50))),
            localStore = store,
            ioDispatcher = Dispatchers.Unconfined
        )
        repo.syncCatalogue()
        val saved = store.state
        assertEquals(listOf("srv_1", "srv_2"), saved?.offers?.map { it.id })
        assertEquals(1L, saved?.catalogueVersion)
    }

    @Test
    fun persistedOffers_restoredOnFreshInstance_offlineAvailable() {
        // A device that previously synced two offers, then goes fully offline (no
        // catalogue source at all): the offers must come back from local storage.
        val stored = PersistedState(
            offers = listOf(offer("srv_9", 99), offer("srv_10", 100)),
            catalogueVersion = 5L,
            initialized = true
        )
        val repo = FakeBingwaRepositoryImpl(
            localStore = InMemoryStore(stored),
            ioDispatcher = Dispatchers.Unconfined
        )
        assertEquals(listOf("srv_9", "srv_10"), repo.offers.value.map { it.id })
        assertEquals(5L, repo.catalogueVersion.value)
    }

    @Test
    fun failedSync_doesNotWipePreviouslyStoredOffers() = runTest {
        val stored = PersistedState(
            offers = listOf(offer("srv_9", 99)),
            catalogueVersion = 3L,
            initialized = true
        )
        val repo = FakeBingwaRepositoryImpl(
            catalogueSource = FakeCatalogue(null), // fetch fails
            localStore = InMemoryStore(stored),
            ioDispatcher = Dispatchers.Unconfined
        )
        assertEquals(listOf("srv_9"), repo.offers.value.map { it.id })
        repo.syncCatalogue()
        assertEquals(listOf("srv_9"), repo.offers.value.map { it.id })
        assertEquals(3L, repo.catalogueVersion.value)
    }

    @Test
    fun restoredFavourites_winOverPersistedOfferFlags() {
        // Offers persisted without favourite flags; the separate favourite id list is
        // authoritative and re-applied on restore.
        val stored = PersistedState(
            offers = listOf(offer("srv_9", 99), offer("srv_10", 100)),
            favouriteIds = listOf("srv_10"),
            catalogueVersion = 2L,
            initialized = true
        )
        val repo = FakeBingwaRepositoryImpl(
            localStore = InMemoryStore(stored),
            ioDispatcher = Dispatchers.Unconfined
        )
        assertTrue(repo.offers.value.first { it.id == "srv_10" }.isFavourite)
        assertTrue(!repo.offers.value.first { it.id == "srv_9" }.isFavourite)
    }
}
