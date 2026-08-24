package com.example.core.payment

import com.example.core.model.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentStateMachineTest {

    // --- Online (STK Push) happy path ------------------------------------------

    @Test
    fun onlineHappyPath_draftToConfirmed() {
        var s = PaymentTxnState.DRAFT
        s = PaymentStateMachine.next(s, PaymentEvent.StkRequested)
        assertEquals(PaymentTxnState.PAYMENT_REQUESTED, s)
        s = PaymentStateMachine.next(s, PaymentEvent.PromptAwaitingApproval)
        assertEquals(PaymentTxnState.AWAITING_APPROVAL, s)
        s = PaymentStateMachine.next(s, PaymentEvent.Confirmed)
        assertEquals(PaymentTxnState.PAYMENT_CONFIRMED, s)
        assertTrue(s.isTerminal)
    }

    @Test
    fun onlineFastConfirm_directlyFromRequested() {
        val s = PaymentStateMachine.next(PaymentTxnState.PAYMENT_REQUESTED, PaymentEvent.Confirmed)
        assertEquals(PaymentTxnState.PAYMENT_CONFIRMED, s)
    }

    @Test
    fun awaitingApproval_canCancelFailOrTimeOut() {
        assertEquals(
            PaymentTxnState.CANCELLED,
            PaymentStateMachine.next(PaymentTxnState.AWAITING_APPROVAL, PaymentEvent.Cancelled)
        )
        assertEquals(
            PaymentTxnState.PAYMENT_FAILED,
            PaymentStateMachine.next(PaymentTxnState.AWAITING_APPROVAL, PaymentEvent.Failed)
        )
        assertEquals(
            PaymentTxnState.TIMED_OUT,
            PaymentStateMachine.next(PaymentTxnState.AWAITING_APPROVAL, PaymentEvent.TimedOut)
        )
    }

    // --- Offline (device-first) path -------------------------------------------

    @Test
    fun offlineHappyPath_instructionsToConfirmed() {
        var s = PaymentStateMachine.next(PaymentTxnState.DRAFT, PaymentEvent.InstructionsShown)
        assertEquals(PaymentTxnState.INSTRUCTIONS_SHOWN, s)
        s = PaymentStateMachine.next(s, PaymentEvent.MarkedPaid)
        assertEquals(PaymentTxnState.WAITING_TO_VERIFY, s)
        s = PaymentStateMachine.next(s, PaymentEvent.Confirmed)
        assertEquals(PaymentTxnState.PAYMENT_CONFIRMED, s)
    }

    @Test
    fun offline_unconfirmedThenMarkedPaid() {
        var s = PaymentStateMachine.next(PaymentTxnState.INSTRUCTIONS_SHOWN, PaymentEvent.LeftUnconfirmed)
        assertEquals(PaymentTxnState.PAYMENT_UNCONFIRMED, s)
        s = PaymentStateMachine.next(s, PaymentEvent.MarkedPaid)
        assertEquals(PaymentTxnState.WAITING_TO_VERIFY, s)
    }

    @Test
    fun offline_verificationCanFail() {
        val s = PaymentStateMachine.next(PaymentTxnState.WAITING_TO_VERIFY, PaymentEvent.VerificationFailed)
        assertEquals(PaymentTxnState.COULD_NOT_VERIFY, s)
        assertTrue(s.isTerminal)
    }

    // --- Illegal transitions ----------------------------------------------------

    @Test
    fun cannotConfirmFromDraft() {
        assertThrows(IllegalPaymentTransition::class.java) {
            PaymentStateMachine.next(PaymentTxnState.DRAFT, PaymentEvent.Confirmed)
        }
    }

    @Test
    fun terminalStatesAcceptNoEvents() {
        val terminal = PaymentTxnState.entries.filter { it.isTerminal }
        assertTrue(terminal.isNotEmpty())
        for (state in terminal) {
            for (event in allEvents()) {
                assertFalse(
                    "Terminal $state must not accept $event",
                    PaymentStateMachine.canTransition(state, event)
                )
            }
        }
    }

    @Test
    fun nextOrNull_returnsNullForIllegal() {
        assertNull(PaymentStateMachine.nextOrNull(PaymentTxnState.PAYMENT_CONFIRMED, PaymentEvent.Confirmed))
    }

    // --- Record-status mapping & copy -------------------------------------------

    @Test
    fun toRecordStatus_mapsTerminalAndWaitingStates() {
        assertEquals(PaymentStatus.RECEIVED, PaymentTxnState.PAYMENT_CONFIRMED.toRecordStatus())
        assertEquals(PaymentStatus.CANCELLED, PaymentTxnState.CANCELLED.toRecordStatus())
        assertEquals(PaymentStatus.FAILED, PaymentTxnState.PAYMENT_FAILED.toRecordStatus())
        assertEquals(PaymentStatus.EXPIRED, PaymentTxnState.TIMED_OUT.toRecordStatus())
        assertEquals(PaymentStatus.WAITING_VERIFY, PaymentTxnState.WAITING_TO_VERIFY.toRecordStatus())
        assertEquals(PaymentStatus.NOT_CONFIRMED, PaymentTxnState.PAYMENT_UNCONFIRMED.toRecordStatus())
        assertEquals(PaymentStatus.COULD_NOT_VERIFY, PaymentTxnState.COULD_NOT_VERIFY.toRecordStatus())
    }

    @Test
    fun transientOnlineStates_haveNoRecordStatus() {
        assertNull(PaymentTxnState.DRAFT.toRecordStatus())
        assertNull(PaymentTxnState.PAYMENT_REQUESTED.toRecordStatus())
        assertNull(PaymentTxnState.AWAITING_APPROVAL.toRecordStatus())
    }

    @Test
    fun customerCopy_matchesPlanExactly() {
        assertEquals("Check your phone", PaymentTxnState.PAYMENT_REQUESTED.heading)
        assertEquals("Purchase Successful", PaymentTxnState.PAYMENT_CONFIRMED.heading)
        assertEquals("Request expired", PaymentTxnState.TIMED_OUT.heading)
        assertEquals("We could not verify this payment", PaymentTxnState.COULD_NOT_VERIFY.heading)
        assertEquals("Waiting to verify", PaymentTxnState.WAITING_TO_VERIFY.heading)
        assertEquals("Payment not confirmed", PaymentTxnState.PAYMENT_UNCONFIRMED.heading)
    }

    private fun allEvents(): List<PaymentEvent> = listOf(
        PaymentEvent.StkRequested,
        PaymentEvent.PromptAwaitingApproval,
        PaymentEvent.Confirmed,
        PaymentEvent.Cancelled,
        PaymentEvent.Failed,
        PaymentEvent.TimedOut,
        PaymentEvent.InstructionsShown,
        PaymentEvent.LeftUnconfirmed,
        PaymentEvent.MarkedPaid,
        PaymentEvent.VerificationFailed
    )
}
