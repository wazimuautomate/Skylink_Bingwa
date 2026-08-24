package com.example.data.fake

import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionKind
import com.example.data.catalogue.RemoteBillboardSource
import com.example.data.persistence.PersistedState
import com.example.data.persistence.SnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillboardSyncTest {

    private class FakeBillboards(private val list: List<Promotion>?) : RemoteBillboardSource {
        override suspend fun fetch(): List<Promotion>? = list
    }

    /** In-memory snapshot store so restore/persist is observable without Android. */
    private class InMemoryStore(initial: PersistedState? = null) : SnapshotStore {
        var state: PersistedState? = initial
        override suspend fun load(): PersistedState? = state
        override suspend fun save(state: PersistedState) { this.state = state }
    }

    private fun promo(id: String, weight: Int = 0) = Promotion(
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
    fun sync_replacesPromotionsWithServerList() = runTest {
        val repo = FakeBingwaRepositoryImpl(billboardSource = FakeBillboards(listOf(promo("srv_1"))))
        repo.syncBillboards()
        assertEquals(1, repo.promotions.value.size)
        assertEquals("srv_1", repo.promotions.value.first().id)
    }

    /** A failure to reach the server must never blank a board that is already showing. */
    @Test
    fun sync_nullKeepsLocalPromotions() = runTest {
        val repo = FakeBingwaRepositoryImpl(
            billboardSource = FakeBillboards(listOf(promo("srv_1")))
        )
        repo.syncBillboards()
        assertEquals(listOf("srv_1"), repo.promotions.value.map { it.id })

        // Second sync fails outright (null) → the previously synced board survives.
        val offline = FakeBingwaRepositoryImpl(billboardSource = FakeBillboards(null))
        val before = offline.promotions.value.map { it.id }
        offline.syncBillboards()
        assertEquals(before, offline.promotions.value.map { it.id })
    }

    /**
     * An EMPTY successful response clears the board. No billboard is hardcoded, so
     * un-publishing them all in the admin must take them off the device too — otherwise
     * a withdrawn advert would keep running forever.
     */
    @Test
    fun sync_emptyClearsPromotions() = runTest {
        val repo = FakeBingwaRepositoryImpl(
            billboardSource = FakeBillboards(listOf(promo("srv_1"), promo("srv_2")))
        )
        repo.syncBillboards()
        assertTrue(repo.promotions.value.isNotEmpty())

        val cleared = FakeBingwaRepositoryImpl(billboardSource = FakeBillboards(emptyList()))
        cleared.syncBillboards()
        assertTrue(cleared.promotions.value.isEmpty())
    }

    @Test
    fun sync_replaceIsWholesale() = runTest {
        val repo = FakeBingwaRepositoryImpl(
            billboardSource = FakeBillboards(listOf(promo("srv_1"), promo("srv_2")))
        )
        repo.syncBillboards()
        assertEquals(listOf("srv_1", "srv_2"), repo.promotions.value.map { it.id })
    }

    @Test
    fun validatedSync_persistsPromotions() = runTest {
        val store = InMemoryStore()
        val repo = FakeBingwaRepositoryImpl(
            billboardSource = FakeBillboards(listOf(promo("srv_1"), promo("srv_2"))),
            localStore = store,
            ioDispatcher = Dispatchers.Unconfined
        )
        repo.syncBillboards()
        assertEquals(listOf("srv_1", "srv_2"), store.state?.promotions?.map { it.id })
    }

    @Test
    fun persistedPromotions_restoredOnFreshInstance_offlineAvailable() {
        // A device that previously synced two billboards, then goes fully offline (no
        // billboard source at all): the promotions must come back from local storage.
        val stored = PersistedState(
            promotions = listOf(promo("srv_9"), promo("srv_10")),
            initialized = true
        )
        val repo = FakeBingwaRepositoryImpl(
            localStore = InMemoryStore(stored),
            ioDispatcher = Dispatchers.Unconfined
        )
        assertEquals(listOf("srv_9", "srv_10"), repo.promotions.value.map { it.id })
    }

    @Test
    fun failedSync_doesNotWipePreviouslyStoredPromotions() = runTest {
        val stored = PersistedState(
            promotions = listOf(promo("srv_9")),
            initialized = true
        )
        val repo = FakeBingwaRepositoryImpl(
            billboardSource = FakeBillboards(null), // fetch fails
            localStore = InMemoryStore(stored),
            ioDispatcher = Dispatchers.Unconfined
        )
        assertEquals(listOf("srv_9"), repo.promotions.value.map { it.id })
        repo.syncBillboards()
        assertEquals(listOf("srv_9"), repo.promotions.value.map { it.id })
    }

    @Test
    fun freshInstall_hasNoHardcodedPromotions() {
        // Nothing persisted and nothing synced yet → an EMPTY board. Billboards are the
        // owner's published adverts, so the app ships none of its own; Home renders the
        // quiet empty state until the first sync arrives.
        val repo = FakeBingwaRepositoryImpl(
            localStore = InMemoryStore(null),
            ioDispatcher = Dispatchers.Unconfined
        )
        assertTrue(repo.promotions.value.isEmpty())
    }
}
