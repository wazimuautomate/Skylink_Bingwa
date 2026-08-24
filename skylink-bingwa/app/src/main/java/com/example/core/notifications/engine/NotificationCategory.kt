package com.example.core.notifications.engine

import com.example.core.notifications.NotificationChannels

/**
 * Every kind of notification the assistant engine can post.
 *
 * Each category carries the three facts the engine needs to post it:
 *  - [channelId]     which [NotificationChannels] channel it belongs on, so the
 *                    customer can silence promotions without losing transaction
 *                    updates (CLAUDE.md §9).
 *  - [transactional] transactional categories bypass quiet hours and the daily
 *                    cap because they are a direct consequence of something the
 *                    customer just did (a payment) or something the carrier just
 *                    told them. Everything else is optional chatter.
 *  - [defaultRoute]  the in-app route a tap opens when the caller does not
 *                    override it ("home" | "offers" | "activity" | "help" |
 *                    "settings" | "notifications").
 *
 * HONESTY (CLAUDE.md §7/§8): [isBalanceDriven] marks the categories that may
 * only ever be raised from a real Safaricom balance SMS. Those are the only
 * categories whose copy may state anything about how much data/SMS/minutes the
 * customer has left, because there the carrier — not Skylink Bingwa — is the factual
 * source. No category may ever claim Skylink Bingwa delivered a bundle.
 */
enum class NotificationCategory(
    val channelId: String,
    val transactional: Boolean,
    val defaultRoute: String
) {
    /** The device lost connectivity. Factual, observed locally. */
    OFFLINE(NotificationChannels.REMINDERS, false, "home"),

    /** The device came back online. */
    ONLINE(NotificationChannels.REMINDERS, false, "offers"),

    MORNING(NotificationChannels.REMINDERS, false, "home"),
    AFTERNOON(NotificationChannels.REMINDERS, false, "home"),
    EVENING(NotificationChannels.REMINDERS, false, "home"),
    LATE_NIGHT(NotificationChannels.REMINDERS, false, "home"),

    /** A payment was confirmed received. NEVER a delivery claim (CLAUDE.md §7). */
    PURCHASE_SUCCESS(NotificationChannels.TRANSACTIONS, true, "activity"),

    LOW_DATA(NotificationChannels.OFFERS, false, "offers"),
    VERY_LOW_DATA(NotificationChannels.OFFERS, false, "offers"),
    NO_DATA(NotificationChannels.OFFERS, false, "offers"),
    LOW_SMS(NotificationChannels.OFFERS, false, "offers"),
    LOW_MINUTES(NotificationChannels.OFFERS, false, "offers"),

    /**
     * Safaricom messaged the customer about a bundle. Only ever raised by the
     * caller from a real carrier SMS, and always attributed to Safaricom.
     */
    BUNDLE_RECEIVED(NotificationChannels.TRANSACTIONS, true, "activity"),

    /** A bundle was bought for/by someone else on this installation's behalf. */
    GIFT_RECEIVED(NotificationChannels.TRANSACTIONS, true, "activity"),

    PROMOTION(NotificationChannels.OFFERS, false, "offers"),

    /** Admin-published general messages. */
    GENERAL(NotificationChannels.NEWS, false, "notifications"),

    INACTIVITY(NotificationChannels.REMINDERS, false, "offers"),
    HABIT_REMINDER(NotificationChannels.REMINDERS, false, "offers");

    /**
     * True for the categories that are driven by a real Safaricom balance SMS.
     * Only these may say anything about the customer's remaining balance.
     */
    val isBalanceDriven: Boolean
        get() = this == LOW_DATA ||
            this == VERY_LOW_DATA ||
            this == NO_DATA ||
            this == LOW_SMS ||
            this == LOW_MINUTES

    companion object {
        /**
         * Maps a (possibly server-supplied) category name to a known category.
         * Returns null for anything unrecognised — an unknown server category
         * must never crash the app.
         */
        fun fromName(raw: String?): NotificationCategory? {
            val key = raw?.trim() ?: return null
            if (key.isEmpty()) return null
            for (value in values()) {
                if (value.name.equals(key, ignoreCase = true)) return value
            }
            return null
        }

        /** Same as [fromName] but falls back to [GENERAL] instead of null. */
        fun fromNameOrGeneral(raw: String?): NotificationCategory = fromName(raw) ?: GENERAL
    }
}
