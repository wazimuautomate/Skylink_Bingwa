package com.example.core.payment

import com.example.core.model.PaymentStatus

/**
 * The live checkout transaction state (Plan.md §6 "Transaction State Model").
 *
 * This is the in-flight state machine that drives the checkout UI. It is distinct
 * from two other registers that must never be conflated (Plan.md §6):
 *
 * - [PaymentStatus] — the terminal/record status persisted in Activity.
 * - The backend API strings (`PAYMENT_REQUESTED`, `PAYMENT_CONFIRMED`, …) that the
 *   gateway maps into these states.
 *
 * The machine intentionally ends at [PAYMENT_CONFIRMED]; bundle fulfilment runs on
 * the owner's agent phone and is outside the version-1 customer-app model. The app
 * never invents a delivery state (CLAUDE.md §7, Plan.md §3.3).
 *
 * `heading`/`explanation` are the exact customer-facing strings from Plan.md §6 and
 * design.md §19.3. Treat them as product logic, not decorative placeholder text
 * (design.md §24).
 */
enum class PaymentTxnState(
    val heading: String,
    val explanation: String,
    val isOnline: Boolean,
    val isTerminal: Boolean
) {
    // --- Online (STK Push) path -------------------------------------------------
    /** Local starting point before the STK request is sent. Never shown alone. */
    DRAFT("", "", isOnline = true, isTerminal = false),
    PAYMENT_REQUESTED(
        heading = "Check your phone",
        explanation = "Approve the M-Pesa request on the payment number",
        isOnline = true,
        isTerminal = false
    ),
    AWAITING_APPROVAL(
        heading = "Waiting for payment",
        explanation = "No payment confirmation has been received yet",
        isOnline = true,
        isTerminal = false
    ),

    // --- Offline (device-first) path -------------------------------------------
    INSTRUCTIONS_SHOWN(
        heading = "Complete payment in M-Pesa",
        explanation = "The app has shown the Till or Paybill steps but cannot know whether payment was made",
        isOnline = false,
        isTerminal = false
    ),
    PAYMENT_UNCONFIRMED(
        heading = "Payment not confirmed",
        explanation = "Enter the M-Pesa receipt when available or check again online",
        isOnline = false,
        isTerminal = false
    ),
    WAITING_TO_VERIFY(
        heading = "Waiting to verify",
        explanation = "The receipt will be checked when the app has internet",
        isOnline = false,
        isTerminal = false
    ),

    // --- Terminal states --------------------------------------------------------
    PAYMENT_CONFIRMED(
        heading = "Purchase Successful",
        explanation = "The purchase was received; ask the customer to wait for the bundle",
        isOnline = true,
        isTerminal = true
    ),
    CANCELLED(
        heading = "Payment cancelled",
        explanation = "The M-Pesa request was cancelled",
        isOnline = true,
        isTerminal = true
    ),
    PAYMENT_FAILED(
        heading = "Payment failed",
        explanation = "Explain whether money was deducted",
        isOnline = true,
        isTerminal = true
    ),
    TIMED_OUT(
        heading = "Request expired",
        explanation = "Let the customer resend safely",
        isOnline = true,
        isTerminal = true
    ),
    COULD_NOT_VERIFY(
        heading = "We could not verify this payment",
        explanation = "Check the receipt and payment details or contact support",
        isOnline = false,
        isTerminal = true
    );

    /**
     * The Activity/record status this live state persists as, or `null` for a
     * purely transient state that is never written to the record on its own.
     */
    fun toRecordStatus(): PaymentStatus? = when (this) {
        PAYMENT_CONFIRMED -> PaymentStatus.RECEIVED
        CANCELLED -> PaymentStatus.CANCELLED
        PAYMENT_FAILED -> PaymentStatus.FAILED
        TIMED_OUT -> PaymentStatus.EXPIRED
        WAITING_TO_VERIFY -> PaymentStatus.WAITING_VERIFY
        PAYMENT_UNCONFIRMED, INSTRUCTIONS_SHOWN -> PaymentStatus.NOT_CONFIRMED
        COULD_NOT_VERIFY -> PaymentStatus.COULD_NOT_VERIFY
        DRAFT, PAYMENT_REQUESTED, AWAITING_APPROVAL -> null
    }
}
