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
 * Where the engine's anti-spam memory lives.
 *
 * The app injects [DataStoreNotificationStateStore]; unit tests inject a tiny
 * in-memory implementation so cooldown, quiet-hour and cap behaviour is testable
 * without Android.
 */
interface NotificationStateStore {
    /** Never throws. An unreadable/absent store reads as an empty [NotificationState]. */
    suspend fun load(): NotificationState

    /** Overwrites the saved state. Never throws. */
    suspend fun save(state: NotificationState)
}

/**
 * Its OWN Preferences DataStore, deliberately separate from the installation
 * snapshot in `data/persistence/LocalStore.kt` ("mybingwa_local"): the engine
 * writes on every post, and it must never risk clobbering the customer's
 * profile, favourites or Activity.
 */
private val Context.notificationStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mybingwa_notification_state"
)

/**
 * DataStore-backed [NotificationStateStore]. Serialises the whole state as one
 * Moshi-reflection JSON document under a single key — no codegen/KSP, matching
 * the pattern already used by `LocalStore`.
 *
 * Contains no personal content: category names, template ids, day counters and
 * opaque hashes only (CLAUDE.md §10).
 */
class DataStoreNotificationStateStore(context: Context) : NotificationStateStore {

    private val appContext: Context = context.applicationContext

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(NotificationState::class.java)

    override suspend fun load(): NotificationState {
        return try {
            val prefs = appContext.notificationStateDataStore.data.first()
            val json = prefs[STATE_KEY] ?: return NotificationState()
            adapter.fromJson(json) ?: NotificationState()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // A corrupt or unreadable store must never break a notification path.
            NotificationState()
        }
    }

    override suspend fun save(state: NotificationState) {
        try {
            val json = adapter.toJson(state)
            appContext.notificationStateDataStore.edit { it[STATE_KEY] = json }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // Losing anti-spam memory is far better than crashing a caller.
        }
    }

    private companion object {
        val STATE_KEY = stringPreferencesKey("notification_state_json")
    }
}

/** In-memory [NotificationStateStore] for tests and for a no-persistence fallback. */
class InMemoryNotificationStateStore(
    initial: NotificationState = NotificationState()
) : NotificationStateStore {

    private var state: NotificationState = initial

    override suspend fun load(): NotificationState = state

    override suspend fun save(state: NotificationState) {
        this.state = state
    }
}
