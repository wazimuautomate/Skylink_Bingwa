package com.example.data.payment

/** The M-Pesa route for an offline purchase (Plan.md §API `routes`). */
enum class PaymentRoute {
    /** Buy Goods (Till) for the customer's own number; the payer becomes the recipient. */
    TILL_SELF,
    /** Pay Bill for another number; the recipient is the account reference. */
    PAYBILL_OTHER
}

/**
 * Cached, signed offline-payment configuration (Plan.md §5.8). The real signing +
 * pinned-public-key verification is delivered by the Phase 7 backend; this app only
 * ever trusts a config whose signature it has verified and whose validity window
 * still covers "now". The app must never invent or hardcode a live Till/Paybill
 * outside a verified config for a production build.
 *
 * `signatureValid` stands in for that verification result at this phase boundary so
 * the eligibility rules are already wired and tested; [CachedOfflineConfigProvider]
 * supplies a development config until real signed material exists.
 */
data class OfflinePaymentConfig(
    val tillNumber: String,
    val paybillNumber: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val signatureValid: Boolean
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis
}

/** Outcome of loading the cached offline configuration. */
sealed interface OfflineConfigResult {
    data class Valid(val config: OfflinePaymentConfig) : OfflineConfigResult
    /** Present but past its validity window — internet is needed to refresh it. */
    data object Expired : OfflineConfigResult
    /** No cached configuration at all. */
    data object Missing : OfflineConfigResult
    /** Present but its signature failed verification — never trust it. */
    data object InvalidSignature : OfflineConfigResult
}

/**
 * Supplies the current offline configuration. Phase 7 replaces the cached
 * implementation with one backed by signed material synced from the backend and
 * stored via Keystore-protected DataStore/Room.
 */
interface OfflinePaymentConfigProvider {
    fun load(nowMillis: Long): OfflineConfigResult
}

/**
 * Development provider holding the seller's current Till/Paybill with a validity
 * window. This is a stand-in test double, not signed production material — it
 * exists so the offline flow, its expiry state and its ambiguity checks are real
 * and testable now. Replace in Phase 7 with a verified signed config.
 */
class CachedOfflineConfigProvider(
    private val config: OfflinePaymentConfig = DEFAULT
) : OfflinePaymentConfigProvider {

    override fun load(nowMillis: Long): OfflineConfigResult = when {
        !config.signatureValid -> OfflineConfigResult.InvalidSignature
        config.isExpired(nowMillis) -> OfflineConfigResult.Expired
        else -> OfflineConfigResult.Valid(config)
    }

    companion object {
        /**
         * Blank Till/Paybill — no seller numbers are baked into the app. The displayed
         * Till/Paybill always come from the server-synced [AppConfig]; this provider only
         * gates eligibility (signature/validity window). A far-future window keeps the
         * gate open so the synced numbers are what the checkout sheet copies.
         */
        val DEFAULT = OfflinePaymentConfig(
            tillNumber = "",
            paybillNumber = "",
            issuedAtMillis = 0L,
            expiresAtMillis = Long.MAX_VALUE,
            signatureValid = true
        )
    }
}
