package com.example.data.payment

import com.example.core.payment.PaymentTxnState

/**
 * Transport-agnostic contract for online M-Pesa payment (Phase 4 "payment
 * repository interface"). The checkout logic depends only on this; the concrete
 * implementation may be the real backend proxy ([BackendPaymentGateway]) or a
 * local simulation ([SimulatedPaymentGateway]).
 *
 * IMPORTANT (CLAUDE.md §2/§10): the app never holds Daraja consumer key/secret or
 * the STK passkey, and never talks to Safaricom directly. Those live only on the
 * backend, which also owns the Daraja CallbackURL. This interface therefore speaks
 * to *our* backend, not to Daraja.
 */
interface PaymentGateway {

    /** Human label for logs/diagnostics; never contains secrets. */
    val name: String

    /**
     * Ask the backend to create the order (server recomputes the price from
     * `offerId`) and send the STK Push to the payer's number. Idempotent on
     * [StkPushRequest.clientRequestId]: repeating the same key must not create a
     * second charge (Plan.md API §idempotency, CLAUDE.md §7).
     */
    suspend fun initiateStkPush(request: StkPushRequest): StkPushResult

    /**
     * Poll the current status for an initiated order. Called every few seconds
     * while the status screen is visible and once on reopen to restore an
     * unfinished order (Plan.md §API status transport, §3.4).
     */
    suspend fun queryStatus(query: StkStatusQuery): StkPushResult
}

/**
 * A create-order + STK request. The price is sent for display/audit only; the
 * server is authoritative and revalidates it before charging (CLAUDE.md §7,
 * Plan.md §API "The app never sends a trusted price").
 */
data class StkPushRequest(
    val offerId: String,
    val amountKsh: Int,
    val payerMsisdn: String,      // 2547XXXXXXXX (E.164 without '+')
    val recipientMsisdn: String,  // 2547XXXXXXXX
    val clientRequestId: String,  // idempotency key
    /**
     * True for a buy-for-myself purchase (Till / Buy Goods route); false for a
     * buy-for-another purchase, which the backend routes to Paybill with the
     * recipient number as the account reference (CLAUDE.md §7). The backend, not
     * the app, owns the actual shortcodes.
     */
    val forSelf: Boolean = true
)

data class StkStatusQuery(
    val clientRequestId: String,
    val orderReference: String?
)

/**
 * The outcome of an initiate/status call, already mapped onto the domain state
 * machine so the caller never parses backend strings.
 */
data class StkPushResult(
    val state: PaymentTxnState,
    val orderReference: String? = null,
    val mpesaReceipt: String? = null,
    /** Customer-safe message when the backend supplies one; UI may prefer the state's own copy. */
    val customerMessage: String? = null,
    /** Non-sensitive error code for logs/support (e.g. "STK_TIMEOUT"); never a stack trace. */
    val errorCode: String? = null
)

/**
 * Raised for transport-level failures (no network, backend unreachable, malformed
 * response). The checkout maps this to a human-safe "we couldn't send the prompt"
 * state — it never leaks HTTP codes to the customer (design.md §18).
 */
class PaymentTransportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
