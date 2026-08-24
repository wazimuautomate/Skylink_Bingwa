package com.example.core.notifications.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import java.util.concurrent.CancellationException

/**
 * SERVER PLUG-IN POINT.
 *
 * The data layer implements this over Retrofit to fetch a newer
 * [NotificationTemplateSet] from the Skylink Bingwa backend. Returning null means
 * "nothing newer / offline / failed" and the caller keeps whatever it has —
 * a failed sync must never wipe the cached copy.
 *
 * Keep this signature stable: it is the contract between the notification engine
 * and the network layer.
 */
interface RemoteNotificationTemplateSource {
    suspend fun fetch(): NotificationTemplateSet?
}

/**
 * Read/write seam for the cached, server-synced template set. `null` from [load]
 * means "nothing has ever been cached" — the caller falls back to the in-APK seed.
 */
interface NotificationTemplateStore {
    suspend fun load(): NotificationTemplateSet?
    suspend fun save(set: NotificationTemplateSet)
}

/** Its own DataStore so a template sync can never disturb the engine's state. */
private val Context.notificationTemplateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mybingwa_notification_templates"
)

/**
 * DataStore-backed [NotificationTemplateStore]. Moshi reflection JSON under a
 * single key — no codegen/KSP. Once a set has been cached it keeps working
 * OFFLINE and across process death.
 */
class DataStoreNotificationTemplateStore(context: Context) : NotificationTemplateStore {

    private val appContext: Context = context.applicationContext

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(NotificationTemplateSet::class.java)

    override suspend fun load(): NotificationTemplateSet? {
        return try {
            val prefs = appContext.notificationTemplateDataStore.data.first()
            val json = prefs[TEMPLATES_KEY] ?: return null
            adapter.fromJson(json)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun save(set: NotificationTemplateSet) {
        try {
            val json = adapter.toJson(set)
            appContext.notificationTemplateDataStore.edit { it[TEMPLATES_KEY] = json }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // Keep the previous cache; the seed still guarantees usable copy.
        }
    }

    private companion object {
        val TEMPLATES_KEY = stringPreferencesKey("notification_templates_json")
    }
}

/** In-memory [NotificationTemplateStore] for tests. */
class InMemoryNotificationTemplateStore(
    initial: NotificationTemplateSet? = null
) : NotificationTemplateStore {

    private var cached: NotificationTemplateSet? = initial

    override suspend fun load(): NotificationTemplateSet? = cached

    override suspend fun save(set: NotificationTemplateSet) {
        cached = set
    }
}

/**
 * Decides which template set the composer should use right now, and owns the
 * (optional) refresh from the server.
 *
 * The stored set only wins when its version is HIGHER than the in-APK seed's, so
 * a stale or malformed download can never downgrade the shipped copy, and the
 * app always has honest, complete templates offline.
 */
class NotificationTemplateProvider(private val store: NotificationTemplateStore) {

    /** The set to compose from. Never null — the seed is the guaranteed floor. */
    suspend fun current(): NotificationTemplateSet {
        val stored = try {
            store.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            null
        }
        return if (stored != null &&
            stored.templates.isNotEmpty() &&
            stored.version > DefaultNotificationTemplates.SEED.version
        ) {
            stored
        } else {
            DefaultNotificationTemplates.SEED
        }
    }

    /**
     * Refreshes the cache from [source].
     *
     * Returns true only when a strictly newer, non-empty set was cached. A null
     * or empty fetch, a failure, or an equal/older version leaves the existing
     * cache untouched — this method NEVER wipes it.
     */
    suspend fun syncFrom(source: RemoteNotificationTemplateSource): Boolean {
        val fetched: NotificationTemplateSet? = try {
            source.fetch()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            null
        }
        if (fetched == null) return false
        if (fetched.templates.isEmpty()) return false

        val existing = try {
            store.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            null
        }
        if (existing != null && existing.version >= fetched.version) return false

        return try {
            store.save(fetched)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            false
        }
    }
}
