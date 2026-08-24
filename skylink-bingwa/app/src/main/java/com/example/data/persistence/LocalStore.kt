package com.example.data.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.NotificationItem
import com.example.core.model.OfferItem
import com.example.core.model.Promotion
import com.example.core.model.PurchaseRecord
import com.example.core.model.UserProfile
import com.example.data.payment.ActiveOrder
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first

/**
 * The customer's installation-local state, serialised as one JSON document under a
 * single Preferences DataStore key.
 *
 * This is the real replacement for the old "everything lives in memory and resets on
 * restart" behaviour: name, profile, favourites, Activity (purchases), notifications,
 * recent recipients and any in-flight order now survive process death — exactly what
 * CLAUDE.md §2 means by "Name, profile, Activity and favourites are local to the
 * installation". No network, no account, no cloud sync; strictly on-device.
 *
 * Serialisation is Moshi reflection (already used by the payment layer) so it needs
 * no codegen/KSP. The whole document is small (a few purchases + notifications), so a
 * full rewrite per change is cheap and avoids a partial-write inconsistency.
 */
private val Context.localDataStore: DataStore<Preferences> by preferencesDataStore(name = "mybingwa_local")

/**
 * A plain, serialisable snapshot of the persisted installation state. [initialized]
 * distinguishes "never saved on this device" (fresh install → keep seeded demo data)
 * from "saved, and legitimately empty" (e.g. after Clear local data).
 *
 * [offers] is the last catalogue the app synced from the server (validated,
 * non-empty). It is the on-device source the UI reads, so previously synced offers
 * remain available OFFLINE and across process death; an empty list means "nothing
 * synced yet → fall back to the seeded catalogue". [catalogueVersion] is the local
 * revision that only increases when a complete, validated catalogue was committed,
 * so a failed/empty sync can be told apart from a real update. Both are new fields
 * with defaults, so snapshots saved before this change still deserialise cleanly.
 *
 * [promotions] is the last set of Home billboards the app synced from the server; it is
 * the on-device source the UI reads, so previously synced promotions remain available
 * OFFLINE and across process death (an empty list means "nothing synced yet → fall back
 * to the seeded promotions"). Like [offers]/[catalogueVersion] it is a new field with a
 * default, so snapshots saved before this change still deserialise cleanly.
 */
data class PersistedState(
    val profile: UserProfile? = null,
    val theme: String? = null,
    val favouriteIds: List<String> = emptyList(),
    val boughtTodayIds: List<String> = emptyList(),
    val purchases: List<PurchaseRecord> = emptyList(),
    val notifications: List<NotificationItem> = emptyList(),
    val recentRecipients: List<String> = emptyList(),
    val activeOrder: ActiveOrder? = null,
    val offers: List<OfferItem> = emptyList(),
    val catalogueVersion: Long = 0L,
    val promotions: List<Promotion> = emptyList(),
    /**
     * True once this install has successfully told the seller's backend who its
     * customer is (name + number, sent once at the end of onboarding). It exists so
     * the call is made exactly ONCE: a failure (offline, weak signal) leaves it
     * false and the next launch retries, and a success means it is never sent again.
     * A new field with a default, so snapshots saved before it existed still
     * deserialise — they simply retry the registration once.
     */
    val customerRegistered: Boolean = false,
    /**
     * When the Play rating card was last launched for this install (0 = never).
     * Persisted so the 60-day gap in [com.example.core.review.ReviewPolicy] survives
     * a restart — the whole point of the limit is that it is not per-session. A new
     * field with a default, so older snapshots deserialise and simply become
     * eligible once they meet the purchase rule.
     */
    val lastReviewPromptMillis: Long = 0L,
    val initialized: Boolean = false
)

/**
 * Read/write seam for the installation snapshot. The app injects [LocalStore]
 * (DataStore-backed); unit tests inject a tiny in-memory implementation so the
 * offline-restore and no-data-loss behaviour can be verified without Android.
 */
interface SnapshotStore {
    /** Reads the saved snapshot, or null when nothing has ever been saved / it is unreadable. */
    suspend fun load(): PersistedState?

    /** Overwrites the saved snapshot. */
    suspend fun save(state: PersistedState)
}

class LocalStore(private val context: Context) : SnapshotStore {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(PersistedState::class.java)

    /** Reads the saved snapshot, or null when nothing has ever been saved / it is unreadable. */
    override suspend fun load(): PersistedState? {
        val prefs = context.localDataStore.data.first()
        val json = prefs[STATE_KEY] ?: return null
        return runCatching { adapter.fromJson(json) }.getOrNull()
    }

    /** Overwrites the saved snapshot. [PersistedState.initialized] is forced true. */
    override suspend fun save(state: PersistedState) {
        val json = adapter.toJson(state.copy(initialized = true))
        context.localDataStore.edit { it[STATE_KEY] = json }
    }

    private companion object {
        val STATE_KEY = stringPreferencesKey("state_json")
    }
}
