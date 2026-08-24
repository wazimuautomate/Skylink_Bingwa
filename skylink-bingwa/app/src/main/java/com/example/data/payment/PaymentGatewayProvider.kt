package com.example.data.payment

/**
 * Selects the online payment gateway at composition-root time.
 *
 * - A real backend is used only when BOTH a usable `https://` base URL AND a
 *   non-blank app-key are present (the app-key is the shared token the backend
 *   requires; without it every call is rejected, so treating that build as
 *   "configured" would just fail every payment).
 * - Otherwise the caller supplies a fallback: a labelled simulation in a debug
 *   build, or [UnavailablePaymentGateway] in a release build so a misconfigured
 *   production APK fails honestly instead of faking a success.
 *
 * Both values are non-secret BuildConfig fields. Daraja credentials never live in
 * the app — only on the backend (CLAUDE.md §2/§10).
 */
object PaymentGatewayProvider {

    /** True only when a usable https base URL AND an app-key are both present. */
    fun isBackendConfigured(baseUrl: String?, appKey: String?): Boolean =
        !baseUrl.isNullOrBlank() &&
            baseUrl.startsWith("https://") &&
            !appKey.isNullOrBlank()

    fun create(
        baseUrl: String,
        appKey: String,
        debugLogging: Boolean
    ): PaymentGateway =
        BackendPaymentGateway.create(
            normaliseBaseUrl(baseUrl),
            appKey = appKey,
            enableLogging = debugLogging
        )

    /** Retrofit requires the base URL to end with a slash. */
    private fun normaliseBaseUrl(baseUrl: String): String =
        if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
}
