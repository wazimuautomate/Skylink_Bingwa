package com.example.core.payment

import com.example.core.payment.PaymentTxnState.AWAITING_APPROVAL
import com.example.core.payment.PaymentTxnState.CANCELLED
import com.example.core.payment.PaymentTxnState.COULD_NOT_VERIFY
import com.example.core.payment.PaymentTxnState.DRAFT
import com.example.core.payment.PaymentTxnState.INSTRUCTIONS_SHOWN
import com.example.core.payment.PaymentTxnState.PAYMENT_CONFIRMED
import com.example.core.payment.PaymentTxnState.PAYMENT_FAILED
import com.example.core.payment.PaymentTxnState.PAYMENT_REQUESTED
import com.example.core.payment.PaymentTxnState.PAYMENT_UNCONFIRMED
import com.example.core.payment.PaymentTxnState.TIMED_OUT
import com.example.core.payment.PaymentTxnState.WAITING_TO_VERIFY

/**
 * Events that drive the checkout [PaymentStateMachine].
 *
 * Online events are produced by the payment gateway / status polling; offline
 * events are produced by the customer's own actions on the instructions screen.
 */
sealed interface PaymentEvent {
    // Online (STK Push)
    /** The STK request was accepted by the backend and a prompt was sent. */
    data object StkRequested : PaymentEvent
    /** Status polling reports the prompt is delivered and awaiting the PIN. */
    data object PromptAwaitingApproval : PaymentEvent
    /** The backend confirmed the payment (`PAYMENT_CONFIRMED`). */
    data object Confirmed : PaymentEvent
    /** The customer cancelled the M-Pesa request. */
    data object Cancelled : PaymentEvent
    /** The payment failed (insufficient funds, wrong PIN, etc.). */
    data object Failed : PaymentEvent
    /** The request expired without a terminal result. */
    data object TimedOut : PaymentEvent

    // Offline (device-first)
    /** Cached Till/Paybill instructions were shown to the customer. */
    data object InstructionsShown : PaymentEvent
    /** The customer left without confirming they paid. */
    data object LeftUnconfirmed : PaymentEvent
    /** The customer marked "I've paid" (optionally with an M-Pesa receipt). */
    data object MarkedPaid : PaymentEvent
    /** Server reconciliation could not verify the offline receipt. */
    data object VerificationFailed : PaymentEvent
}

/** Thrown when an event is not legal for the current state. */
class IllegalPaymentTransition(from: PaymentTxnState, event: PaymentEvent) :
    IllegalStateException("No transition from $from on ${event::class.simpleName}")

/**
 * The pure, deterministic checkout state machine (Plan.md §6). It has no Android,
 * network or time dependencies so it is fully unit-testable.
 *
 * Online:  DRAFT → PAYMENT_REQUESTED → AWAITING_APPROVAL → {CONFIRMED | CANCELLED | FAILED | TIMED_OUT}
 * Offline: DRAFT → INSTRUCTIONS_SHOWN → {PAYMENT_UNCONFIRMED} → WAITING_TO_VERIFY → {CONFIRMED | COULD_NOT_VERIFY}
 *
 * Terminal states accept no further events. A cancel/fail/timeout may also arrive
 * directly in PAYMENT_REQUESTED (the customer can dismiss the prompt before it is
 * ever marked "awaiting approval").
 */
object PaymentStateMachine {

    /** All legal (from → to) edges, used by [canTransition] and the tests. */
    private val transitions: Map<PaymentTxnState, Map<PaymentEvent, PaymentTxnState>> = mapOf(
        DRAFT to mapOf(
            PaymentEvent.StkRequested to PAYMENT_REQUESTED,
            PaymentEvent.InstructionsShown to INSTRUCTIONS_SHOWN
        ),
        PAYMENT_REQUESTED to mapOf(
            PaymentEvent.PromptAwaitingApproval to AWAITING_APPROVAL,
            PaymentEvent.Confirmed to PAYMENT_CONFIRMED,
            PaymentEvent.Cancelled to CANCELLED,
            PaymentEvent.Failed to PAYMENT_FAILED,
            PaymentEvent.TimedOut to TIMED_OUT
        ),
        AWAITING_APPROVAL to mapOf(
            PaymentEvent.Confirmed to PAYMENT_CONFIRMED,
            PaymentEvent.Cancelled to CANCELLED,
            PaymentEvent.Failed to PAYMENT_FAILED,
            PaymentEvent.TimedOut to TIMED_OUT
        ),
        INSTRUCTIONS_SHOWN to mapOf(
            PaymentEvent.MarkedPaid to WAITING_TO_VERIFY,
            PaymentEvent.LeftUnconfirmed to PAYMENT_UNCONFIRMED
        ),
        PAYMENT_UNCONFIRMED to mapOf(
            PaymentEvent.MarkedPaid to WAITING_TO_VERIFY
        ),
        WAITING_TO_VERIFY to mapOf(
            PaymentEvent.Confirmed to PAYMENT_CONFIRMED,
            PaymentEvent.VerificationFailed to COULD_NOT_VERIFY
        )
        // Terminal states (PAYMENT_CONFIRMED, CANCELLED, PAYMENT_FAILED, TIMED_OUT,
        // COULD_NOT_VERIFY) intentionally have no outgoing edges.
    )

    /** True when [event] is legal in [from]. */
    fun canTransition(from: PaymentTxnState, event: PaymentEvent): Boolean =
        transitions[from]?.containsKey(event) == true

    /**
     * Returns the next state for [event] in [from], or throws
     * [IllegalPaymentTransition] if the event is not legal. Keeping illegal
     * transitions loud is what prevents the UI from, e.g., "confirming" a payment
     * that was never requested (CLAUDE.md §7 "never optimistically mark success").
     */
    fun next(from: PaymentTxnState, event: PaymentEvent): PaymentTxnState =
        transitions[from]?.get(event) ?: throw IllegalPaymentTransition(from, event)

    /** Like [next] but returns `null` instead of throwing; for tolerant callers. */
    fun nextOrNull(from: PaymentTxnState, event: PaymentEvent): PaymentTxnState? =
        transitions[from]?.get(event)
}
