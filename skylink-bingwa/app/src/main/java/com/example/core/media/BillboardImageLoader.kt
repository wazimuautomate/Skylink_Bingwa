package com.example.core.media

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File

/**
 * The process-wide Coil [ImageLoader] used by the Home billboard, and nothing
 * else. Constructed lazily on first use (never on the startup critical path —
 * CLAUDE.md §11) and reused for the life of the process.
 *
 * ## Why this exists rather than Coil's global singleton
 * The billboard needs three specific behaviours that the default loader does not
 * give us:
 *
 * 1. **Animated GIF support.** `ImageDecoderDecoder` (API 28+, hardware
 *    `android.graphics.ImageDecoder`) or `GifDecoder` (API 24–27, the older
 *    `android.graphics.Movie` path) is registered here. Without a GIF decoder an
 *    animated slide would render as a single still frame.
 *
 * 2. **A real, bounded disk cache — this is what makes billboards work
 *    OFFLINE.** Media is downloaded ONCE into [DISK_CACHE_DIR] (bounded at
 *    [DISK_CACHE_MAX_BYTES] ≈ 32 MB, so it can never grow without limit). Every
 *    later render of that slide — including with no internet at all — is served
 *    from that on-disk copy, so a synced billboard never becomes a blank space
 *    when the customer is offline. `respectCacheHeaders(false)` is deliberate:
 *    admin media is often served with `Cache-Control: no-cache`, which would
 *    otherwise force a revalidation the app cannot perform while offline.
 *
 * 3. **Deterministic cache-busting.** See [requestFor].
 *
 * The memory cache is intentionally modest ([MEMORY_CACHE_PERCENT] of the app
 * heap): a billboard shows a handful of small slides, and low-cost A05/A06-class
 * devices must not lose heap to promotional artwork.
 */
object BillboardImageLoader {

    /** Sub-directory of the app cache dir holding downloaded billboard media. */
    const val DISK_CACHE_DIR = "billboard_media"

    /** Hard ceiling for the billboard media cache (~32 MB). */
    const val DISK_CACHE_MAX_BYTES = 32L * 1024L * 1024L

    /** Share of the app heap the billboard memory cache may use. */
    const val MEMORY_CACHE_PERCENT = 0.12

    @Volatile
    private var instance: ImageLoader? = null

    /**
     * The shared billboard loader. Safe to call from any thread; the first call
     * builds it (cheap — Coil defers all real work until a request runs).
     */
    fun get(context: Context): ImageLoader {
        instance?.let { return it }
        val appContext = context.applicationContext
        return synchronized(this) {
            instance ?: build(appContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): ImageLoader =
        ImageLoader.Builder(appContext)
            .components {
                // ImageDecoder is only available from API 28 (P); below that Coil
                // falls back to the Movie-based decoder. Both decode animated GIFs.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appContext.cacheDir, DISK_CACHE_DIR))
                    .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                    .build()
            }
            // Keep the downloaded copy usable offline even when the media host
            // sends no-cache/no-store headers (see the class KDoc).
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // The billboard animates its own entrance fade, so Coil's crossfade
            // is off: two overlapping fades read as a flicker.
            .crossfade(false)
            .build()

    /**
     * The effective cache key for one slide's artwork.
     *
     * The admin can republish DIFFERENT artwork at the SAME url. Coil keys its
     * caches by the request data (the url) by default, so the stale copy would
     * be served forever. The server therefore supplies a `mediaVersion` token;
     * folding it into the key means:
     *
     * - unchanged version → cache hit → zero network, works offline;
     * - changed version   → key miss  → refetched EXACTLY once, then cached again.
     *
     * The url itself is left untouched (no query parameter is appended) so signed
     * / CDN urls are never corrupted.
     */
    fun cacheKey(url: String, mediaVersion: String): String =
        if (mediaVersion.isBlank()) url else "$url#v=$mediaVersion"

    /**
     * Build the [ImageRequest] for a billboard slide, wired to the versioned
     * [cacheKey] on both the memory and the disk cache.
     */
    fun requestFor(context: Context, url: String, mediaVersion: String): ImageRequest {
        val key = cacheKey(url, mediaVersion)
        return ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .crossfade(false)
            .build()
    }
}
