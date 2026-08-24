package com.example.data.fake

import com.example.core.model.NotificationItem
import com.example.data.payment.SimulatedPaymentGateway
import com.example.core.payment.PaymentTxnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local notification-centre behaviour: read, mark-all-read, clear a single
 * notification and clear all. These back the slide-up notification overlay
 * (read / copy / clear actions).
 */
class NotificationRepositoryTest {

    private fun repo() = FakeBingwaRepositoryImpl(
        gateway = SimulatedPaymentGateway(
            terminalOutcome = { PaymentTxnState.PAYMENT_CONFIRMED },
            initiateDelayMillis = 0,
            statusDelayMillis = 0
        ),
        // The app no longer seeds demo notifications; this test provides its own.
        seedNotifications = listOf(
            NotificationItem(id = "n1", title = "Payment received", body = "recorded", timestampMillis = 3L, isRead = false),
            NotificationItem(id = "n2", title = "Buy again", body = "available", timestampMillis = 2L, isRead = true),
            NotificationItem(id = "n3", title = "New offer", body = "2GB KSh 110", timestampMillis = 1L, isRead = true)
        )
    )

    @Test
    fun markNotificationRead_setsOnlyThatOneRead() {
        val repository = repo()
        val target = repository.notifications.value.first { !it.isRead }

        repository.markNotificationRead(target.id)

        assertTrue(repository.notifications.value.first { it.id == target.id }.isRead)
    }

    @Test
    fun markAllNotificationsRead_clearsUnread() {
        val repository = repo()
        repository.markAllNotificationsRead()
        assertTrue(repository.notifications.value.all { it.isRead })
    }

    @Test
    fun deleteNotification_removesOnlyThatNotification() {
        val repository = repo()
        val before = repository.notifications.value
        val target = before.first()

        repository.deleteNotification(target.id)

        val after = repository.notifications.value
        assertEquals(before.size - 1, after.size)
        assertFalse(after.any { it.id == target.id })
    }

    @Test
    fun clearAllNotifications_emptiesTheCentre() {
        val repository = repo()
        assertTrue(repository.notifications.value.isNotEmpty())

        repository.clearAllNotifications()

        assertTrue(repository.notifications.value.isEmpty())
    }
}
