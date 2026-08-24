package com.example.core.personalization

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
 * The personalization profile's own private Preferences DataStore.
 *
 * PRIVACY (CLAUDE.md §10) — read this before changing anything in this file:
 *
 * - Everything written here is **on-device only**. It is never uploaded, never
 *   attached to an API request, never logged, and never shared with the seller's
 *   server. The server therefore cannot learn this customer's habits, favourite
 *   bundles, payment number or recipients.
 * - It disappears completely when the customer clears app data or uninstalls the
 *   app; there is no cloud copy and no way to restore it.
 * - It is a derived cache, not a source of truth. The authoritative data is the
 *   purchase history already stored in `mybingwa_local`; this file only keeps the
 *   *computed* profile so Home can be personalised instantly on cold start
 *   (CLAUDE.md §11 — cached Home within 300ms) while the real recomputation runs
 *   in the background afterwards.
 *
 * A separate DataStore file name (`mybingwa_personalization`) is used
 * deliberately: it must not collide with `mybingwa_local`, and it means the
 * profile can be dropped or rebuilt without touching the customer's real
 * Activity history.
 */
private val Context.personalizationDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "mybingwa_personalization")

/**
 * Read/write seam for the learned profile. The app uses
 * [DataStorePersonalizationStore]; tests use a tiny in-memory implementation.
 */
interface PersonalizationStore {
    /** The saved profile, or null when nothing has been saved yet or it is unreadable. */
    suspend fun load(): BehaviourProfile?

    /** Overwrites the saved profile. */
    suspend fun save(profile: BehaviourProfile)
}

class DataStorePersonalizationStore(private val context: Context) : PersonalizationStore {

    // Moshi reflection (no codegen/KSP), matching data/persistence/LocalStore.
    // Every BehaviourProfile field has a default and every map key is a String,
    // so an older document missing new fields still deserialises cleanly.
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(BehaviourProfile::class.java)

    /**
     * Returns null rather than throwing on any read/parse failure. Personalization
     * is an enhancement: a corrupt or unreadable profile must degrade to
     * "we have learned nothing yet", never crash or block Home.
     */
    override suspend fun load(): BehaviourProfile? = runCatching {
        val prefs = context.personalizationDataStore.data.first()
        val json = prefs[PROFILE_KEY]
        if (json.isNullOrBlank()) null else adapter.fromJson(json)
    }.getOrNull()

    /**
     * Best-effort write. A failed save only means the next cold start recomputes
     * from local purchase history, so it must never surface as an error.
     */
    override suspend fun save(profile: BehaviourProfile) {
        runCatching {
            val json = adapter.toJson(profile)
            context.personalizationDataStore.edit { it[PROFILE_KEY] = json }
        }
    }

    private companion object {
        val PROFILE_KEY = stringPreferencesKey("behaviour_profile_json")
    }
}
