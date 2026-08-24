package com.example.data.sync

import com.example.core.notifications.engine.NotificationTemplateProvider
import com.example.core.notifications.engine.RemoteNotificationSource
import com.example.core.notifications.engine.RemoteNotificationStore
import com.example.core.notifications.engine.RemoteNotificationTemplateSource

/**
 * The content syncs that belong to the notification engine rather than to the offer
 * catalogue.
 *
 * The repository owns the [SyncTargets] contract, but notification templates and
 * admin-published notifications live in their own stores — the repository has no
 * business knowing about them. This collaborator holds that knowledge in one place,
 * so the repository simply delegates.
 *
 * Every dependency is nullable and every method degrades to a silent no-op when
 * its pieces are absent. That is what keeps a build with no configured base URL
 * (and every unit test that constructs the repository bare) working unchanged.
 *
 * OFFLINE SAFETY (the rule the whole sync engine is built on): a failed or null
 * fetch KEEPS whatever is already cached. Nothing here ever clears a store
 * because the network was unavailable — old content beats no content.
 */
class ContentSyncers(
    private val templateProvider: NotificationTemplateProvider? = null,
    private val templateSource: RemoteNotificationTemplateSource? = null,
    private val notificationStore: RemoteNotificationStore? = null,
    private val notificationSource: RemoteNotificationSource? = null
) {

    /**
     * Refreshes the notification wording. The provider keeps the cached set when
     * the fetch fails or returns an older version, so the customer never loses
     * personalised copy because a sync failed.
     */
    suspend fun syncNotificationTemplates() {
        val provider = templateProvider ?: return
        val source = templateSource ?: return
        provider.syncFrom(source)
    }

    /**
     * Refreshes the admin-published notification queue.
     *
     * A null fetch means offline/failed — return without touching the cache, so
     * previously downloaded messages still display with no internet. An EMPTY
     * list is a real answer ("the owner cleared the queue") and is honoured,
     * matching [com.example.core.notifications.engine.RemoteNotificationSelector.merge].
     */
    suspend fun syncRemoteNotifications() {
        val store = notificationStore ?: return
        val source = notificationSource ?: return
        val fetched = source.fetch() ?: return
        store.save(fetched)
    }
}
