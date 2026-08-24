package com.example.data.payment

import com.example.core.payment.PaymentTxnState
import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit contract for the Skylink Bingwa payment API (the cPanel PHP endpoints). The
 * server owns the Daraja credentials, the STK passkey and the Daraja CallbackURL;
 * this app only calls these two endpoints. No secrets appear here.
 *
 * Paths map to the PHP files at the base URL root: `stk.php` and `status.php`.
 * Kept intentionally small: create-order-and-STK, then poll status until terminal.
 */
interface PaymentApi {

    @POST("stk.php")
    suspend fun initiateStk(@Body body: StkRequestDto): StkResponseDto

    @GET("status.php")
    suspend fun status(
        @Query("clientRequestId") clientRequestId: String,
        @Query("orderReference") orderReference: String?
    ): StkResponseDto
}

data class StkRequestDto(
    @Json(name = "offerId") val offerId: String,
    @Json(name = "amountKsh") val amountKsh: Int,
    @Json(name = "payerMsisdn") val payerMsisdn: String,
    @Json(name = "recipientMsisdn") val recipientMsisdn: String,
    @Json(name = "clientRequestId") val clientRequestId: String,
    /** Till/Buy-Goods route when true; Paybill + recipient account when false. */
    @Json(name = "forSelf") val forSelf: Boolean = true
)

data class StkResponseDto(
    /** One of the API status strings below; unknown values are treated as awaiting. */
    @Json(name = "status") val status: String?,
    @Json(name = "orderReference") val orderReference: String? = null,
    @Json(name = "mpesaReceipt") val mpesaReceipt: String? = null,
    @Json(name = "customerMessage") val customerMessage: String? = null,
    @Json(name = "errorCode") val errorCode: String? = null
) {
    /** Maps the backend status register onto the domain state machine (Plan.md §6/§API). */
    fun toTxnState(): PaymentTxnState = when (status?.uppercase()) {
        "PAYMENT_REQUESTED" -> PaymentTxnState.PAYMENT_REQUESTED
        "AWAITING_APPROVAL" -> PaymentTxnState.AWAITING_APPROVAL
        "PAYMENT_CONFIRMED" -> PaymentTxnState.PAYMENT_CONFIRMED
        "CANCELLED" -> PaymentTxnState.CANCELLED
        "PAYMENT_FAILED", "FAILED" -> PaymentTxnState.PAYMENT_FAILED
        "TIMED_OUT", "EXPIRED" -> PaymentTxnState.TIMED_OUT
        // Unknown/absent → keep the customer waiting rather than inventing a result.
        else -> PaymentTxnState.AWAITING_APPROVAL
    }
}
