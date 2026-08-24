package com.example.core.model

enum class PaymentStatus(val label: String) {
    RECEIVED("Purchase Successful"),
    WAITING_VERIFY("Waiting to verify"),
    NOT_CONFIRMED("Payment not confirmed"),
    CANCELLED("Payment cancelled"),
    FAILED("Payment failed"),
    EXPIRED("Request expired"),
    COULD_NOT_VERIFY("We could not verify this payment")
}

enum class PaymentMethod(val label: String) {
    STK_PUSH("M-Pesa STK Push"),
    TILL("M-Pesa Till"),
    PAYBILL("M-Pesa Paybill")
}

data class PurchaseRecord(
    val id: String,
    val offerId: String,
    val offerName: String,
    val allowance: String,
    val priceKsh: Int,
    val recipientNumber: String,
    val payerNumber: String,
    val mpesaCode: String,
    val timestampMillis: Long,
    val status: PaymentStatus,
    val paymentMethod: PaymentMethod,
    /**
     * Idempotency key for this checkout attempt (Plan.md API §"clientRequestId").
     * The same key must never create a second charge on retry/double-tap.
     */
    val clientRequestId: String = "",
    /** Skylink Bingwa order reference for support ("Ref" shown in Activity), when known. */
    val orderReference: String = ""
)
