package com.example.data.catalogue

import com.example.core.model.Promotion

/**
 * Fetches the Home billboard promotions from the server. Returns null on any failure
 * (offline or error), in which case the app keeps its current local billboards — the
 * app is never left without promotions (server is only for syncing).
 *
 * A fetched [Promotion] carries only media METADATA (url, type, version, click
 * intent). The artwork itself is downloaded once and cached on disk by
 * `core/media/BillboardImageLoader`, so a slide that has been synced keeps
 * rendering — image, GIF and all — with no internet.
 */
interface RemoteBillboardSource {
    suspend fun fetch(): List<Promotion>?
}
