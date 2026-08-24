package com.example.data.payment

import com.example.core.payment.PaymentTxnState
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * A local, offline simulation of the online gateway. It performs NO network call
 * and contacts NEITHER Daraja NOR any backend — it just returns a configurable
 * outcome after a short delay.
 *
 * It exists for two honest reasons:
 * 1. Until the backend base URL is provided, buy-for-myself has nowhere real to
 *    call; the app stays testable on a phone with a clearly-labelled simulation
 *    rather than a fake "success".
 * 2. Buy-for-another remains mocked in this phase by product decision.
 *
 * This must never be selected for a production release that advertises real STK —
 * [PaymentGatewayProvider] only chooses it when no base URL is configured.
 *
 * [terminalOutcome] lets the dev harness pick SUCCESS/CANCELLED/FAILED/DELAYED so
 * every result screen can be exercised on a device.
 */
class SimulatedPaymentGateway(
    private val terminalOutcome: () -> PaymentTxnState = { PaymentTxnState.PAYMENT_CONFIRMED },
    private val initiateDelayMillis: Long = 900,
    private val statusDelayMillis: Long = 900
) : PaymentGateway {

    override val name: String = "simulated"

    override suspend fun initiateStkPush(request: StkPushRequest): StkPushResult {
        delay(initiateDelayMillis)
        // A simulated backend always "accepts" and sends the prompt.
        return StkPushResult(
            state = PaymentTxnState.PAYMENT_REQUESTED,
            orderReference = "SIM-" + request.clientRequestId.take(8).uppercase()
        )
    }

    override suspend fun queryStatus(query: StkStatusQuery): StkPushResult {
        delay(statusDelayMillis)
        val outcome = terminalOutcome()
        return StkPushResult(
            state = outcome,
            orderReference = query.orderReference,
            mpesaReceipt = if (outcome == PaymentTxnState.PAYMENT_CONFIRMED) simulatedReceipt() else null,
            errorCode = if (outcome == PaymentTxnState.PAYMENT_FAILED) "SIM_FAILED" else null
        )
    }

    private fun simulatedReceipt(): String =
        "R" + UUID.randomUUID().toString().replace("-", "").take(9).uppercase()
}
