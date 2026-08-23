package com.example.core.notifications.engine

import android.content.Context
import com.example.core.notifications.AppNotifier
import com.example.core.notifications.NotificationChannels
import java.util.concurrent.CancellationException

/**
 * The one entry point the rest of the app calls to post a notification.
 *
 * Constructed manually with a [Context] — no Hilt, matching the existing
 * `core/notifications` style:
 *
 * ```
 * val engine = NotificationEngine(
 *     context = applicationContext,
 *     notifier = AppNotifier(applicationContext),
 *     stateStore = DataStoreNotificationStateStore(applicationContext),
 *     templateProvider = NotificationTemplateProvider(
 *         DataStoreNotificationTemplateStore(applicationContext)
 *     )
 * )
 * ```
 *
 * [notify] runs the whole pipeline: load state -> policy check -> compose ->
 * post -> record. It returns false whenever the notification was suppressed, and
 * it NEVER throws: a failing store, a missing permission or a notifier error can
 * never crash the caller's coroutine (a purchase must not fail because a
 * notification did).
 *
 * Honesty (CLAUDE.md §7/§8) is enforced upstream in the templates and in
 * [NotificationComposer]: balance copy only for balance-driven categories, no
 * delivery claim anywhere, bundle suggestions from local purchase history only.
 */
class NotificationEngine(
    context: Context,
    private val notifier: AppNotifier,
    private val stateStore: NotificationStateStore,
    private val templateProvider: NotificationTemplateProvider
) {

    private val appContext: Context = context.applicationContext

    /**
     * Registers the notification channels. Safe to call repeatedly and a no-op
     * below API 26, so callers do not have to track whether it already ran.
     */
    fun ensureChannels() {
        try {
            NotificationChannels.createChannels(appContext)
        } catch (e: Exception) {
            // Channel registration must never break startup.
        }
    }

    /**
     * Composes and posts a personalised notification for [category].
     *
     * @param deepLinkRoute overrides [NotificationCategory.defaultRoute].
     * @return true only when a notification was actually handed to the system.
     */
    suspend fun notify(
        category: NotificationCategory,
        personalization: NotificationPersonalization,
        deepLinkRoute: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        try {
            return runNotify(category, personalization, deepLinkRoute, nowMillis)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // A notification must never take a purchase, a sync or a screen down.
            return false
        }
    }

    private suspend fun runNotify(
        category: NotificationCategory,
        personalization: NotificationPersonalization,
        deepLinkRoute: String?,
        nowMillis: Long
    ): Boolean {
        val state = stateStore.load()

        // Cheap pre-check: skip composing entirely when it cannot be posted.
        if (!NotificationPolicy.shouldPost(category, nowMillis, state, null).allowed) return false

        val templates = templateProvider.current()
        val composed = NotificationComposer.compose(
            category = category,
            personalization = personalization.copy(nowMillis = nowMillis),
            templates = templates,
            seed = nowMillis,
            lastTemplateId = state.lastTemplateIdByCategory[category.name]
        )
        if (composed == null) return false

        val hash = contentHash(category.name, composed.title, composed.body)
        if (!NotificationPolicy.shouldPost(category, nowMillis, state, hash).allowed) return false

        val posted = notifier.postEngine(
            channelId = category.channelId,
            notificationId = notificationId(category),
            title = composed.title,
            body = composed.body,
            deepLinkRoute = routeOr(category, deepLinkRoute)
        )
        if (!posted) return false

        stateStore.save(
            NotificationPolicy.record(
                category = category,
                templateId = composed.templateId,
                nowMillis = nowMillis,
                state = state,
                contentHash = hash
            )
        )
        return true
    }

    /**
     * Posts copy that was authored elsewhere — an admin-published
     * [RemoteNotification], or any message the caller already worded.
     *
     * Still fully policy-checked (quiet hours, daily cap, cooldown, duplicate),
     * so the dashboard cannot spam the customer. [stableId] identifies the
     * message for de-duplication; use the admin message id.
     */
    suspend fun notifyRaw(
        category: NotificationCategory,
        title: String,
        body: String,
        stableId: String,
        deepLinkRoute: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        try {
            return runNotifyRaw(category, title, body, stableId, deepLinkRoute, nowMillis)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun runNotifyRaw(
        category: NotificationCategory,
        title: String,
        body: String,
        stableId: String,
        deepLinkRoute: String?,
        nowMillis: Long
    ): Boolean {
        if (body.isBlank() && title.isBlank()) return false

        val state = stateStore.load()
        val hash = contentHash(category.name, stableId, body)
        if (!NotificationPolicy.shouldPost(category, nowMillis, state, hash).allowed) return false

        val safeTitle = if (title.isBlank()) "My Bingwa" else title
        val safeBody = if (body.isBlank()) title else body

        val posted = notifier.postEngine(
            channelId = category.channelId,
            notificationId = rawNotificationId(stableId),
            title = safeTitle,
            body = safeBody,
            deepLinkRoute = routeOr(category, deepLinkRoute)
        )
        if (!posted) return false

        stateStore.save(
            NotificationPolicy.record(
                category = category,
                templateId = stableId,
                nowMillis = nowMillis,
                state = state,
                contentHash = hash
            )
        )
        return true
    }

    private fun routeOr(category: NotificationCategory, override: String?): String {
        val route = override?.trim()
        return if (route.isNullOrEmpty()) category.defaultRoute else route
    }

    /**
     * One live notification per category, so a newer message replaces the older
     * one in the tray instead of stacking up.
     */
    private fun notificationId(category: NotificationCategory): Int =
        ("mybingwa_engine:" + category.name).hashCode()

    /** Admin messages get their own slot so several can coexist. */
    private fun rawNotificationId(stableId: String): Int =
        ("mybingwa_engine_raw:" + stableId).hashCode()

    private companion object {
        /**
         * An opaque, non-reversible fingerprint of the composed copy, used only
         * for de-duplication. Never stores or logs the copy itself, and the copy
         * never contains a full phone number (the caller masks it first) —
         * CLAUDE.md §10.
         */
        fun contentHash(categoryName: String, title: String, body: String): String {
            val combined = categoryName + "|" + title + "|" + body
            return combined.hashCode().toString()
        }
    }
}
