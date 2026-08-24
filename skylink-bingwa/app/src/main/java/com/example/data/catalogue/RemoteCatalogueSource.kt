package com.example.data.catalogue

import com.example.core.model.OfferItem

/**
 * Fetches the offer catalogue from the server. Returns null on any failure (offline
 * or error), in which case the app keeps its current local catalogue — the app is
 * never left without offers (Phase 6/7: server is only for syncing).
 */
interface RemoteCatalogueSource {
    suspend fun fetch(): List<OfferItem>?
}
