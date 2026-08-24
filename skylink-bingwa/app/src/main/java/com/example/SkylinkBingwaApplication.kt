package com.example

import android.app.Application
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.example.core.notifications.AppNotifier
import com.example.core.notifications.engine.DataStoreNotificationStateStore
import com.example.core.notifications.engine.DataStoreNotificationTemplateStore
import com.example.core.notifications.engine.DataStoreRemoteNotificationStore
import com.example.core.notifications.engine.NotificationEngine
import com.example.core.notifications.engine.NotificationTemplateProvider
import com.example.core.notifications.engine.RemoteNotificationStore
import com.example.core.personalization.DataStorePersonalizationStore
import com.example.core.personalization.PersonalizationStore
import com.example.data.catalogue.AndroidRemoteBillboardSource
import com.example.data.catalogue.AndroidRemoteCatalogueSource
import com.example.data.config.AndroidRemoteConfigSource
import com.example.data.fake.BingwaRepository
import com.example.data.fake.FakeBingwaRepositoryImpl
import com.example.data.payment.PaymentGatewayProvider
import com.example.data.payment.UnavailablePaymentGateway
import com.example.data.persistence.LocalStore
import com.example.data.remote.AndroidRemoteCustomerSource
import com.example.data.remote.AndroidRemoteNotificationSource
import com.example.data.remote.AndroidRemoteNotificationTemplateSource
import com.example.data.remote.AndroidRemoteSyncManifestSource
import com.example.data.sync.CatalogueSyncWorker
import com.example.data.sync.EngagementNotificationWorker
import com.example.data.sync.ContentSyncers
import com.example.data.sync.DataStoreSyncMetadataStore
import com.example.data.sync.RemoteSyncManifestSource
import com.example.data.sync.SyncMetadataStore
import com.example.data.sync.SyncOrchestrator
import com.example.data.sync.SyncOrchestratorProvider
import java.util.concurrent.TimeUnit

/**
 * Process-wide entry point. It owns the single [BingwaRepository] instance so the UI
 * ([MainActivity]) and the background [CatalogueSyncWorker] share exactly one
 * repository — the same StateFlows and the same on-device [LocalStore] — instead of
 * two divergent copies. It also schedules the periodic catalogue/config sync.
 */
class SkylinkBingwaApplication : Application(), SyncOrchestratorProvider {

    /**
     * The app's one repository. Built lazily on first access (UI start or the sync
     * worker), so construction never blocks [onCreate]/cold start. Buy-for-myself uses
     * the real backend proxy when a non-secret base URL + app-key are configured;
     * otherwise a labelled debug simulation or an honest-failing release gateway.
     * Daraja credentials never live in the app.
     */
    val repository: BingwaRepository by lazy { buildRepository() }

    /** True when a usable https base URL is configured; server syncing needs nothing else. */
    private val hasBaseUrl: Boolean
        get() = BuildConfig.PAYMENTS_BASE_URL.isNotBlank() &&
            BuildConfig.PAYMENTS_BASE_URL.startsWith("https://")

    // --- Notification engine ------------------------------------------------------
    // Built lazily so nothing here touches disk or the main thread during onCreate.

    /** Cached, server-synced notification wording; the in-APK seed is the floor. */
    val notificationTemplateProvider: NotificationTemplateProvider by lazy {
        NotificationTemplateProvider(DataStoreNotificationTemplateStore(applicationContext))
    }

    /** Admin-published messages, cached so they still display once offline. */
    val remoteNotificationStore: RemoteNotificationStore by lazy {
        DataStoreRemoteNotificationStore(applicationContext)
    }

    /**
     * The one notification entry point. Owns cooldowns, quiet hours, the daily cap
     * and de-duplication, so no caller can accidentally spam the customer.
     */
    val notificationEngine: NotificationEngine by lazy {
        NotificationEngine(
            context = applicationContext,
            notifier = AppNotifier(applicationContext),
            stateStore = DataStoreNotificationStateStore(applicationContext),
            templateProvider = notificationTemplateProvider
        )
    }

    /** On-device purchase behaviour. Never uploaded; disappears with app data. */
    val personalizationStore: PersonalizationStore by lazy {
        DataStorePersonalizationStore(applicationContext)
    }

    /**
     * Drives every sync. Nullable by contract: with no base URL there is no manifest
     * source, and the orchestrator falls back to throttled full syncs of whatever
     * sources exist — it still never wipes cached content.
     */
    override val syncOrchestrator: SyncOrchestrator? by lazy {
        SyncOrchestrator(
            targets = repository,
            // The SAME store the ForceSyncWatcher reads, so the watcher compares against
            // the versions the orchestrator actually committed.
            metadataStore = syncMetadataStore,
            manifestSource = manifestSource
        )
    }

    /** Tiny fingerprint document; polled while foreground to catch an admin publish. */
    val manifestSource: RemoteSyncManifestSource? by lazy {
        if (hasBaseUrl) {
            AndroidRemoteSyncManifestSource(
                baseUrl = BuildConfig.PAYMENTS_BASE_URL,
                appKey = BuildConfig.PAYMENTS_APP_KEY,
                enableLogging = BuildConfig.DEBUG
            )
        } else {
            null
        }
    }

    /** Metadata store shared with [syncOrchestrator] so the watcher sees the same versions. */
    val syncMetadataStore: SyncMetadataStore by lazy {
        DataStoreSyncMetadataStore(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        // Best-effort: scheduling the periodic sync must NEVER crash startup. It can throw
        // when WorkManager is not initialised for the process — e.g. under Robolectric unit
        // tests (no androidx.startup initializer), or a rare OEM WorkManager fault. The app
        // still runs and syncs on the next foreground refresh; we just skip the background job.
        runCatching { scheduleCatalogueSync() }
            .onFailure { Log.w("SkylinkBingwaApplication", "Background sync scheduling skipped: ${it.message}") }
        // Same best-effort contract: the daily engagement notifications must never be
        // able to crash a cold start. KEEP (not REPLACE) so this never disturbs a
        // pending/running job — including the one whose OWN fire time just caused this
        // cold start. It only seeds a fresh chain when one is genuinely missing (first
        // install, or repair after a reboot, force-stop or app update). See the policy
        // doc on EngagementNotificationWorker.scheduleNext for why REPLACE here used to
        // silently cancel the very notification it was about to show.
        runCatching { EngagementNotificationWorker.scheduleNext(this, policy = ExistingWorkPolicy.KEEP) }
            .onFailure { Log.w("SkylinkBingwaApplication", "Engagement scheduling skipped: ${it.message}") }

        // Fetch FCM token for remote admin push notifications (best-effort).
        //
        // Two delivery paths, on purpose. The token is uploaded with the customer
        // registration so the admin can address this exact handset; the `all_users`
        // topic is the safety net for a phone that has not finished onboarding yet, so
        // its token has never reached the server. Without the subscription the server's
        // topic fan-out is accepted by FCM (HTTP 200) and delivered to nobody.
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        repository.setFcmToken(token)
                    }
                }
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .subscribeToTopic(FCM_BROADCAST_TOPIC)
        }.onFailure { Log.w("SkylinkBingwaApplication", "FCM token initialization skipped: ${it.message}") }
    }

    private fun buildRepository(): BingwaRepository {
        val baseUrl = BuildConfig.PAYMENTS_BASE_URL
        val appKey = BuildConfig.PAYMENTS_APP_KEY
        // Real STK needs BOTH a usable https base URL AND the app-key (the backend
        // rejects calls without it). Config/catalogue sync only needs a base URL.
        val paymentConfigured = PaymentGatewayProvider.isBackendConfigured(baseUrl, appKey)
        val hasBaseUrl = baseUrl.isNotBlank() && baseUrl.startsWith("https://")

        return FakeBingwaRepositoryImpl(
            gateway = if (paymentConfigured) {
                PaymentGatewayProvider.create(
                    baseUrl = baseUrl,
                    appKey = appKey,
                    debugLogging = BuildConfig.DEBUG
                )
            } else {
                null
            },
            // No real backend: a debug build uses a labelled on-device simulation so
            // screens stay testable; a release build must NEVER fake a success, so it
            // uses UnavailablePaymentGateway (payments fail honestly until configured).
            fallbackGateway = if (!paymentConfigured && !BuildConfig.DEBUG) {
                UnavailablePaymentGateway()
            } else {
                null
            },
            // Seller Till/Paybill/support are synced from the server but always cached
            // for offline use. No base URL → baked-in defaults only.
            configSource = if (hasBaseUrl) {
                AndroidRemoteConfigSource(
                    context = applicationContext,
                    baseUrl = baseUrl,
                    appKey = appKey,
                    enableLogging = BuildConfig.DEBUG
                )
            } else {
                null
            },
            // Offers are synced from the server when online; the bundled catalogue is
            // the guaranteed offline base (server is only for syncing).
            catalogueSource = if (hasBaseUrl) {
                AndroidRemoteCatalogueSource(
                    baseUrl = baseUrl,
                    appKey = appKey,
                    enableLogging = BuildConfig.DEBUG
                )
            } else {
                null
            },
            // The seller's own record of who is using the app: name + number, sent
            // ONCE at the end of onboarding. No base URL → nobody is registered, and
            // the app is otherwise unaffected.
            customerSource = if (hasBaseUrl) {
                AndroidRemoteCustomerSource(
                    baseUrl = baseUrl,
                    appKey = appKey,
                    enableLogging = BuildConfig.DEBUG
                )
            } else {
                null
            },
            // Home billboards (promotions) are synced from the server when online; the
            // bundled promotions are the guaranteed offline base (server is only for
            // syncing). No base URL → seeded promotions only.
            billboardSource = if (hasBaseUrl) {
                AndroidRemoteBillboardSource(
                    baseUrl = baseUrl,
                    appKey = appKey,
                    enableLogging = BuildConfig.DEBUG
                )
            } else {
                null
            },
            // Notification wording and admin-published messages. These are what make
            // the app teachable from the dashboard without an app release. No base URL
            // → left null, and the app runs on its in-APK seeds.
            contentSyncers = if (hasBaseUrl) {
                val baseUrl = BuildConfig.PAYMENTS_BASE_URL
                val appKey = BuildConfig.PAYMENTS_APP_KEY
                ContentSyncers(
                    templateProvider = notificationTemplateProvider,
                    templateSource = AndroidRemoteNotificationTemplateSource(
                        baseUrl = baseUrl,
                        appKey = appKey,
                        enableLogging = BuildConfig.DEBUG
                    ),
                    notificationStore = remoteNotificationStore,
                    notificationSource = AndroidRemoteNotificationSource(
                        baseUrl = baseUrl,
                        appKey = appKey,
                        enableLogging = BuildConfig.DEBUG
                    )
                )
            } else {
                null
            },
            // Real on-device persistence: name, profile, favourites, Activity, synced
            // offers, notifications and any in-flight order survive process death.
            localStore = LocalStore(applicationContext)
        )
    }

    /**
     * Enqueue the periodic background sync as a unique job. [ExistingPeriodicWorkPolicy.KEEP]
     * leaves an already-scheduled job untouched across restarts. It runs only when the
     * device is CONNECTED, retries failures with exponential backoff, and never blocks
     * startup (enqueue is cheap and offloaded to WorkManager).
     */
    private fun scheduleCatalogueSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<CatalogueSyncWorker>(SYNC_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UNIQUE_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private companion object {
        const val UNIQUE_SYNC_WORK_NAME = "mybingwa_catalogue_sync"

        // The topic the admin dashboard fans a broadcast out to. Must match
        // FcmService::broadcast() on the server. Not renamed with the app: an existing
        // install's subscription lives on Google's side, and changing the string would
        // silently orphan every phone already subscribed.
        const val FCM_BROADCAST_TOPIC = "all_users"

        // Six hours: fresh enough for offer/config changes without draining battery or
        // hammering the backend. Comfortably above WorkManager's 15-minute floor.
        const val SYNC_INTERVAL_HOURS = 6L
    }
}
