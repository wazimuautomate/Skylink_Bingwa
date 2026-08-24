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
 * A message the owner published from the admin dashboard.
 *
 * The copy is authored server-side, so this carries a finished [title]/[body]
 * rather than a template. It is still policy-checked before it is posted, and it
 * is still bound by the honesty rules in [DefaultNotificationTemplates] — the
 * dashboard, not the app, is responsible for not writing a delivery claim.
 *
 * Every field has a default so an older cached document, or a payload from a
 * newer server that adds fields, still deserialises through Moshi reflection.
 * [category] is a STRING: an unknown category degrades to GENERAL instead of
 * crashing.
 */
data class RemoteNotification(
    val id: String = "",
    val category: String = "GENERAL",
    val title: String = "",
    val body: String = "",
    val deepLinkRoute: String = "home",
    /** Epoch millis the message becomes eligible. 0 = immediately. */
    val startsAt: Long = 0L,
    /** Epoch millis the message expires. Default = never. */
    val endsAt: Long = Long.MAX_VALUE,
    /** Higher wins when several are due at once. */
    val priority: Int = 0
)

/**
 * SERVER PLUG-IN POINT. The data layer implements this over Retrofit.
 * Returning null means "offline / failed" — the caller keeps the cache.
 *
 * Keep this signature stable: it is the contract between the notification engine
 * and the network layer.
 */
interface RemoteNotificationSource {
    suspend fun fetch(): List<RemoteNotification>?
}

/** Read/write seam for the cached admin-published messages. */
interface RemoteNotificationStore {
    suspend fun load(): List<RemoteNotification>
    suspend fun save(items: List<RemoteNotification>)
}

/**
 * Moshi reflection cannot build an adapter for a bare `List<T>` without
 * `Types.newParameterizedType`, so the cached list is wrapped in this tiny
 * envelope. It also leaves room for future metadata without a migration.
 */
data class RemoteNotificationEnvelope(
    val items: List<RemoteNotification> = emptyList()
)

/** Its own DataStore so admin messages never disturb the engine's state. */
private val Context.remoteNotificationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mybingwa_remote_notifications"
)

/**
 * DataStore-backed [RemoteNotificationStore].
 *
 * OFFLINE BEHAVIOUR: once fetched, admin messages are cached here, so a message
 * that was synced while online still displays later when the device has no
 * connectivity. The app never claims a remote message was delivered while
 * offline — it simply shows what was already cached (CLAUDE.md §9).
 */
class DataStoreRemoteNotificationStore(context: Context) : RemoteNotificationStore {

    private val appContext: Context = context.applicationContext

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(RemoteNotificationEnvelope::class.java)

    override suspend fun load(): List<RemoteNotification> {
        return try {
            val prefs = appContext.remoteNotificationDataStore.data.first()
            val json = prefs[ITEMS_KEY] ?: return emptyList()
            adapter.fromJson(json)?.items ?: emptyList()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun save(items: List<RemoteNotification>) {
        try {
            val json = adapter.toJson(RemoteNotificationEnvelope(items))
            appContext.remoteNotificationDataStore.edit { it[ITEMS_KEY] = json }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // Keep the previous cache.
        }
    }

    private companion object {
        val ITEMS_KEY = stringPreferencesKey("remote_notifications_json")
    }
}

/** In-memory [RemoteNotificationStore] for tests. */
class InMemoryRemoteNotificationStore(
    initial: List<RemoteNotification> = emptyList()
) : RemoteNotificationStore {

    private var cached: List<RemoteNotification> = initial

    override suspend fun load(): List<RemoteNotification> = cached

    override suspend fun save(items: List<RemoteNotification>) {
        cached = items
    }
}

/** Picks the single admin message that should be shown right now, if any. */
object RemoteNotificationSelector {

    /**
     * The highest-priority message that is inside its time window, has content,
     * and has not already been shown. Ties break towards the one that started
     * most recently. Returns null when nothing is due — silence beats noise.
     */
    fun due(
        items: List<RemoteNotification>,
        nowMillis: Long,
        alreadyShownIds: Set<String>
    ): RemoteNotification? {
        val eligible = items.filter { item ->
            item.id.isNotEmpty() &&
                !alreadyShownIds.contains(item.id) &&
                (item.title.isNotBlank() || item.body.isNotBlank()) &&
                nowMillis >= item.startsAt &&
                nowMillis <= item.endsAt
        }
        if (eligible.isEmpty()) return null
        return eligible.sortedWith(
            compareByDescending<RemoteNotification> { it.priority }
                .thenByDescending { it.startsAt }
                .thenBy { it.id }
        ).first()
    }

    /**
     * Merges a freshly fetched list into the cache. A null fetch (offline or
     * failed) keeps the cache exactly as it was; an empty list from the server is
     * treated as "the owner cleared the queue" and is honoured.
     */
    fun merge(cached: List<RemoteNotification>, fetched: List<RemoteNotification>?): List<RemoteNotification> =
        fetched ?: cached
}
