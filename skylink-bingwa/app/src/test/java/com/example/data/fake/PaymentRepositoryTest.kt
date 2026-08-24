package com.example.data.fake

import com.example.core.model.PaymentMethod
import com.example.core.model.PaymentStatus
import com.example.core.payment.PaymentTxnState
import com.example.data.payment.SimulatedPaymentGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentRepositoryTest {

    private fun repo(outcome: PaymentTxnState = PaymentTxnState.PAYMENT_CONFIRMED) =
        FakeBingwaRepositoryImpl(
            gateway = SimulatedPaymentGateway(
                terminalOutcome = { outcome },
                initiateDelayMillis = 0,
                statusDelayMillis = 0
            )
        )

    @Test
    fun stkSuccess_recordsReceivedWithReceipt() = runTest {
        val repository = repo(PaymentTxnState.PAYMENT_CONFIRMED)
        val offer = repository.offers.value.first()
        val record = repository.executeMpesaStkPush(offer, "0712345678", "0712345678", "cid-1", true)

        assertEquals(PaymentStatus.RECEIVED, record.status)
        assertEquals(PaymentMethod.STK_PUSH, record.paymentMethod)
        assertTrue("receipt should be present on success", record.mpesaCode.isNotBlank() && record.mpesaCode != "-")
        assertNull("active order cleared after terminal", repository.activeOrder.value)
    }

    @Test
    fun doubleTap_isIdempotent_noSecondCharge() = runTest {
        val repository = repo()
        val offer = repository.offers.value.first()
        val before = repository.purchases.value.size

        val first = repository.executeMpesaStkPush(offer, "0712345678", "0712345678", "same-cid", true)
        val second = repository.executeMpesaStkPush(offer, "0712345678", "0712345678", "same-cid", true)

        assertEquals("same clientRequestId returns the same record", first.id, second.id)
        assertEquals("only one record was added", before + 1, repository.purchases.value.size)
        assertEquals(1, repository.purchases.value.count { it.clientRequestId == "same-cid" })
    }

    @Test
    fun stkCancelled_isNotSuccess() = runTest {
        val repository = repo(PaymentTxnState.CANCELLED)
        val offer = repository.offers.value.first()
        val record = repository.executeMpesaStkPush(offer, "0712345678", "0712345678", "cid-cancel", true)
        assertEquals(PaymentStatus.CANCELLED, record.status)
        assertEquals("-", record.mpesaCode)
    }

    @Test
    fun invalidPayer_failsWithoutCharge() = runTest {
        val repository = repo()
        val offer = repository.offers.value.first()
        val record = repository.executeMpesaStkPush(offer, "0712345678", "not-a-number", "cid-bad", true)
        assertEquals(PaymentStatus.FAILED, record.status)
    }

    @Test
    fun offlineWithReceipt_isWaitingToVerify_neverSuccess() = runTest {
        val repository = repo()
        val offer = repository.offers.value.first()
        val record = repository.executeOfflinePayment(offer, "0712345678", "0712345678", true, "RHK82910AZ")
        assertEquals(PaymentStatus.WAITING_VERIFY, record.status)
        assertEquals("RHK82910AZ", record.mpesaCode)
        assertNotEquals(PaymentStatus.RECEIVED, record.status)
    }

    @Test
    fun offlineWithoutReceipt_isNotConfirmed() = runTest {
        val repository = repo()
        val offer = repository.offers.value.first()
        val record = repository.executeOfflinePayment(offer, "0712345678", "0712345678", true, null)
        assertEquals(PaymentStatus.NOT_CONFIRMED, record.status)
        assertEquals(PaymentMethod.TILL, record.paymentMethod)
    }

    @Test
    fun offlineForAnother_usesPaybill() = runTest {
        val repository = repo()
        val offer = repository.offers.value.first()
        val record = repository.executeOfflinePayment(offer, "0722000111", "0712345678", false, "RHK1")
        assertEquals(PaymentMethod.PAYBILL, record.paymentMethod)
    }
}
