package com.example.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * The app's notification channels, kept separate so the customer can silence
 * promotions without losing transaction updates (CLAUDE.md §9: separate
 * transaction updates from promotions and reminders).
 *
 * Channels are only registered on API 26+ (guarded below). Call
 * [createChannels] once, early (the integration step calls it from the
 * Application/first-launch path). Safe to call repeatedly — the system merges by id.
 */
object NotificationChannels {

    /** Payment status and bundle-delivery updates. Default importance (may alert). */
    const val TRANSACTIONS = "transactions"

    /** Promotions and top-up suggestions. Low importance — quiet, no sound/vibration. */
    const val OFFERS = "offers"

    /** Gentle reminders (e.g. an offer available again). Low importance. */
    const val REMINDERS = "reminders"

    /** App-update news. Low importance. */
    const val UPDATES = "updates"

    /**
     * Admin-published general messages from My Bingwa (the assistant engine's
     * GENERAL category). Low importance — never urgent, never a payment update,
     * so it stays out of the way and can be silenced on its own.
     */
    const val NEWS = "news"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val transactions = NotificationChannel(
            TRANSACTIONS,
            "Payment & delivery updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Payment status and updates about the bundles you buy."
        }

        val offers = NotificationChannel(
            OFFERS,
            "Offers & suggestions",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Occasional deals and top-up ideas. Quiet by default."
            setSound(null, null)
            enableVibration(false)
        }

        val reminders = NotificationChannel(
            REMINDERS,
            "Reminders",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Light reminders, such as an offer becoming available again."
            enableVibration(false)
        }

        val updates = NotificationChannel(
            UPDATES,
            "App updates",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "News about new versions of My Bingwa."
            enableVibration(false)
        }

        val news = NotificationChannel(
            NEWS,
            "News from My Bingwa",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Occasional messages from My Bingwa. Quiet by default."
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannels(listOf(transactions, offers, reminders, updates, news))
    }
}
