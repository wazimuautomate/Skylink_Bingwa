package com.example.data.referral

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.referralDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "skylink_referral"
)

/**
 * Local storage for the referral feature.
 *
 * Three things live here and nothing else:
 *
 *  - **pendingReferralCode** — what the customer typed during onboarding, held until
 *    registration actually reaches the server. Onboarding can complete offline, so
 *    this has to survive until the next successful launch or the referrer silently
 *    loses their credit.
 *  - **myCode** — this customer's own code, as issued by the server. Cached so the
 *    Earn screen can show and share it instantly, and offline.
 *  - **authToken** — the bearer token issued after SMS verification. This is the
 *    credential that authorises a withdrawal, so it is the one piece of app state
 *    that can move money.
 *
 * The token is deliberately NOT in the main app profile blob: that document is
 * rewritten wholesale on every state change, and a credential has no business
 * riding along with favourites and purchase history.
 */
class ReferralStore(context: Context) {

    private val appContext = context.applicationContext

    suspend fun pendingReferralCode(): String? = read(PENDING_CODE)

    suspend fun setPendingReferralCode(code: String?) = write(PENDING_CODE, code)

    suspend fun myCode(): String? = read(MY_CODE)

    suspend fun setMyCode(code: String?) = write(MY_CODE, code)

    suspend fun authToken(): String? = read(AUTH_TOKEN)

    suspend fun setAuthToken(token: String?) = write(AUTH_TOKEN, token)

    /** The verified payout number, cached so the withdraw sheet can name it offline. */
    suspend fun payoutMsisdn(): String? = read(PAYOUT_MSISDN)

    suspend fun setPayoutMsisdn(msisdn: String?) = write(PAYOUT_MSISDN, msisdn)

    private suspend fun read(key: Preferences.Key<String>): String? = try {
        appContext.referralDataStore.data.first()[key]?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        // A corrupt or unreadable store must never crash the Earn screen; the
        // feature simply behaves as "not verified yet".
        null
    }

    private suspend fun write(key: Preferences.Key<String>, value: String?) {
        try {
            appContext.referralDataStore.edit { prefs ->
                if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
            }
        } catch (t: Throwable) {
            // Best effort. Losing the cached code costs a refetch; losing the token
            // costs one extra SMS verification. Neither is worth a crash.
        }
    }

    private companion object {
        val PENDING_CODE = stringPreferencesKey("pending_referral_code")
        val MY_CODE = stringPreferencesKey("my_referral_code")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val PAYOUT_MSISDN = stringPreferencesKey("payout_msisdn")
    }
}
