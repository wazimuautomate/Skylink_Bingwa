package com.example.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first

/**
 * The sync engine's own bookkeeping, kept in a SEPARATE Preferences DataStore from the
 * customer's installation snapshot.
 *
 * Separation is deliberate and required: `mybingwa_local` (see
 * [com.example.data.persistence.LocalStore]) holds USER BEHAVIOUR — purchases,
 * favourites, recent recipients, the in-flight order — and the sync engine must never
 * be able to touch it. This store holds only "which revision of shared content does
 * this device already have", so a corrupt or reset sync file costs at most one extra
 * download and can never lose a purchase record.
 *
 * Modelled directly on `LocalStore`: Moshi REFLECTION (no codegen/KSP in this module),
 * one JSON document under one key, whole-document rewrite per change (the document is
 * a handful of numbers, so a partial-write inconsistency is not worth the complexity).
 */
private val Context.syncMetadataDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mybingwa_sync_meta"
)

/**
 * What this device knows about its own sync state.
 *
 * Keys in [local] and [lastAttemptAtByResource] are [SyncResource.name] strings rather
 * than the enum itself, so a document written by a build that knew about a resource
 * this build does not still deserialises — the unknown key is simply never read.
 *
 * Every field has a default, so a document written before a field existed still loads.
 */
data class SyncMetadata(
    /** Last successfully synced server fingerprint per resource name. */
    val local: Map<String, ResourceVersion> = emptyMap(),

    /** Last ATTEMPT (success or failure) per resource name — drives throttling. */
    val lastAttemptAtByResource: Map<String, Long> = emptyMap(),

    /** The manifest publishVersion this device has already reacted to. */
    val lastPublishVersion: Long = 0L,

    /** When the manifest was last fetched successfully (diagnostics). */
    val lastManifestFetchAt: Long = 0L,

    /** When at least one resource last synced successfully (diagnostics). */
    val lastSuccessfulSyncAt: Long = 0L
)

/**
 * Read/write seam for [SyncMetadata]. The app uses [DataStoreSyncMetadataStore]; unit
 * tests inject a tiny in-memory implementation so the planner and orchestrator can be
 * verified without Android.
 *
 * Neither method may throw: a failed read returns empty metadata (worst case: one
 * extra full sync) and a failed write is silently ignored (worst case: the same
 * resources are checked again next time).
 */
interface SyncMetadataStore {
    suspend fun load(): SyncMetadata
    suspend fun save(m: SyncMetadata)
}

class DataStoreSyncMetadataStore(private val context: Context) : SyncMetadataStore {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(SyncMetadata::class.java)

    /**
     * Reads the stored metadata. ANY failure — no document yet, unreadable DataStore,
     * malformed or truncated JSON — returns empty metadata rather than throwing. Empty
     * metadata is always safe: it makes the planner sync everything once.
     */
    override suspend fun load(): SyncMetadata = runCatching {
        val prefs = context.syncMetadataDataStore.data.first()
        val json = prefs[METADATA_KEY] ?: return@runCatching SyncMetadata()
        adapter.fromJson(json) ?: SyncMetadata()
    }.getOrElse { SyncMetadata() }

    /** Overwrites the stored metadata. A write failure is swallowed, never thrown. */
    override suspend fun save(m: SyncMetadata) {
        runCatching {
            val json = adapter.toJson(m)
            context.syncMetadataDataStore.edit { it[METADATA_KEY] = json }
        }
    }

    private companion object {
        val METADATA_KEY = stringPreferencesKey("sync_meta_json")
    }
}

/**
 * In-memory [SyncMetadataStore] for tests and for the "no Context available" case.
 * Not persistent: every process start behaves like a fresh install.
 */
class InMemorySyncMetadataStore(initial: SyncMetadata = SyncMetadata()) : SyncMetadataStore {

    @Volatile
    private var state: SyncMetadata = initial

    override suspend fun load(): SyncMetadata = state

    override suspend fun save(m: SyncMetadata) {
        state = m
    }
}
