package com.example.data.remote

import com.example.data.sync.RemoteSyncManifestSource
import com.example.data.sync.ResourceVersion
import com.example.data.sync.SyncManifest
import com.squareup.moshi.Json
import retrofit2.http.GET

/**
 * Retrofit implementation of [RemoteSyncManifestSource] backed by
 * `get_sync_manifest.php`.
 *
 * This is the cheapest and most frequently called request in the app — the foreground
 * force-sync watcher fetches it about every 90 seconds — so it deliberately carries
 * only versions, timestamps and checksums, never content.
 *
 * Any failure (offline, non-2xx, malformed body, or a server old enough not to have
 * the endpoint) returns null. The planner treats null as "sync everything, subject to
 * throttle", so a manifest outage degrades to the old full-sync behaviour instead of
 * freezing every device's content.
 */
class AndroidRemoteSyncManifestSource(
    baseUrl: String,
    appKey: String,
    enableLogging: Boolean = false
) : RemoteSyncManifestSource {

    private val api: SyncManifestApi? =
        RemoteHttp.createApi(baseUrl, appKey, enableLogging, SyncManifestApi::class.java)

    override suspend fun fetch(): SyncManifest? {
        val response = try {
            api?.getSyncManifest() ?: return null
        } catch (t: Throwable) {
            return null
        }
        return response.toManifest()
    }
}

interface SyncManifestApi {
    @GET("get_sync_manifest.php")
    suspend fun getSyncManifest(): SyncManifestDto
}

/**
 * Wire shape. Every field is nullable with a default so a missing or newly added key
 * can never throw — an absent value falls back to the domain default.
 */
data class SyncManifestDto(
    @Json(name = "publishVersion") val publishVersion: Long? = null,
    @Json(name = "generatedAt") val generatedAt: Long? = null,
    @Json(name = "resources") val resources: Map<String, ResourceVersionDto?>? = null
) {
    /**
     * Map to the domain [SyncManifest]. Resource keys are passed through verbatim: the
     * planner only ever looks up the [com.example.data.sync.SyncResource] names this
     * build knows, so a key from a future server is carried harmlessly and ignored.
     * A null entry is dropped rather than crashing.
     */
    fun toManifest(): SyncManifest {
        val mapped = LinkedHashMap<String, ResourceVersion>()
        resources?.forEach { (key, value) ->
            if (value != null) mapped[key] = value.toResourceVersion()
        }
        return SyncManifest(
            publishVersion = publishVersion ?: 0L,
            generatedAt = generatedAt ?: 0L,
            resources = mapped
        )
    }
}

data class ResourceVersionDto(
    @Json(name = "version") val version: Long? = null,
    @Json(name = "updatedAt") val updatedAt: Long? = null,
    @Json(name = "checksum") val checksum: String? = null
) {
    fun toResourceVersion(): ResourceVersion = ResourceVersion(
        version = version ?: 0L,
        updatedAt = updatedAt ?: 0L,
        checksum = checksum.orEmpty()
    )
}
