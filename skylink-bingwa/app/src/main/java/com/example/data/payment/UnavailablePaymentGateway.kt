package com.example.data.payment

/**
 * The fallback used by a **release** build when no payment backend is configured
 * (missing base URL or app-key). It never contacts anyone and, critically, never
 * fabricates a success: every call fails with a transport error, which the checkout
 * maps to an honest "we couldn't reach the payment service" — not "Payment received".
 *
 * This is the guardrail that stops a misconfigured production APK from silently
 * showing a fake M-Pesa success (the previous behaviour of the dev simulation). A
 * debug build still uses [SimulatedPaymentGateway] so screens can be exercised on a
 * phone; a release build must talk to the real backend or fail truthfully.
 */
class UnavailablePaymentGateway : PaymentGateway {

    override val name: String = "unavailable"

    override suspend fun initiateStkPush(request: StkPushRequest): StkPushResult =
        throw PaymentTransportException("Online payment is not available in this build")

    override suspend fun queryStatus(query: StkStatusQuery): StkPushResult =
        throw PaymentTransportException("Online payment is not available in this build")
}
