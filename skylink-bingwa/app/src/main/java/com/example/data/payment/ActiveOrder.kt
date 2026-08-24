package com.example.data.payment

import com.example.core.payment.PaymentTxnState

/**
 * A snapshot of an in-flight checkout, exposed so the app can restore an unfinished
 * payment after process death (Plan.md §3.4, §API "reconcile unfinished orders on
 * reopen"). This phase keeps it in memory and defines the restoration *contract*;
 * Phase 6 persists it (DataStore/Room) so it truly survives process death, and the
 * integration phase re-opens the checkout sheet from it on launch.
 */
data class ActiveOrder(
    val clientRequestId: String,
    val offerId: String,
    val offerName: String,
    val priceKsh: Int,
    val recipientNumber: String,
    val payerNumber: String,
    val isForSelf: Boolean,
    val state: PaymentTxnState,
    val orderReference: String? = null
)
