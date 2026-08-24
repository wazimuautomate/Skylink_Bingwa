package com.example.data.fake

import com.example.core.model.AppThemeSetting
import com.example.core.model.NotificationItem
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.PaymentStatus
import com.example.core.model.Promotion
import com.example.core.model.PurchaseRecord
import com.example.core.model.UserProfile
import com.example.core.notifications.ConnectionState
import com.example.data.config.AppConfig
import com.example.data.payment.ActiveOrder
import com.example.data.payment.OfflineEligibility
import com.example.data.payment.OfflinePaymentConfig
import com.example.data.sync.SyncTargets
import kotlinx.coroutines.flow.StateFlow

enum class DevStkOutcome {
    SUCCESS,
    CANCELLED,
    FAILED,
    DELAYED
}

enum class ValidityFilter(val label: String) {
    ALL("All"),
    HOURLY("Hourly"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly")
}

enum class SortOption(val label: String) {
    POPULAR("Popular"),
    LOWEST_PRICE("Lowest price"),
    HIGHEST_VALUE("Highest value"),
    SHORTEST_VALIDITY("Shortest validity"),
    LONGEST_VALIDITY("Longest validity")
}

/** Highest offer price in the catalogue; the price filter defaults to this so nothing is hidden. */
const val MAX_OFFER_PRICE_KSH = 1005

data class OfferFilterState(
    val selectedCategory: OfferCategory = OfferCategory.ALL,
    val selectedValidity: ValidityFilter = ValidityFilter.ALL,
    val searchQuery: String = "",
    val maxPriceKsh: Int = Int.MAX_VALUE,
    val selectedSort: SortOption = SortOption.LOWEST_PRICE
)

/**
 * The app's single data surface.
 *
 * It extends [SyncTargets] so the [com.example.data.sync.SyncOrchestrator] can drive
 * every server-fed resource through one contract. Each sync method honours the same
 * offline-first rule: a failed, null, empty or incomplete response KEEPS the existing
 * local content and never clears it. Purchases, favourites, recent recipients and
 * personalisation are LOCAL only and are never touched by a sync.
 */
interface BingwaRepository : SyncTargets {
    val userProfile: StateFlow<UserProfile>
    val appTheme: StateFlow<AppThemeSetting>
    val isOffline: StateFlow<Boolean>
    /** True until the first cached catalogue load resolves; drives the Home/Offers skeletons. */
    val catalogueLoading: StateFlow<Boolean>
    val offers: StateFlow<List<OfferItem>>
    /** Active promotions/announcements for the Home billboard (Plan.md §5.13). */
    val promotions: StateFlow<List<Promotion>>
    val filterState: StateFlow<OfferFilterState>
    val purchases: StateFlow<List<PurchaseRecord>>
    val notifications: StateFlow<List<NotificationItem>>
    val recentRecipients: StateFlow<List<String>>
    val devStkOutcome: StateFlow<DevStkOutcome>

    /**
     * Seller details (Till, Paybill, support) synced from the server but always
     * available offline from cache/defaults. Server is only for syncing (Phase 6).
     */
    val appConfig: StateFlow<AppConfig>

    /**
     * The device's current internet-transport state, pushed in from the
     * [ConnectivityObserver] in MainActivity. Feeds offer suggestion logic; the
     * canonical offline flag stays [isOffline].
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * The in-flight checkout, or null when idle. Exposed for process-death
     * restoration (Plan.md §3.4); persisted in Phase 6.
     */
    val activeOrder: StateFlow<ActiveOrder?>

    fun updateProfile(name: String, primaryNumber: String)
    fun setOnboardingCompleted(completed: Boolean)

    /**
     * Tell the seller's backend who this customer is — name and Safaricom number —
     * exactly once per install. Safe to call on every launch: it returns immediately
     * once it has succeeded, and it is the ONLY thing about a customer that leaves
     * the device (CLAUDE.md §10). A failure is silent and retried on a later launch.
     */
    suspend fun registerCustomer()

    /** When the Play rating card was last launched for this install (0 = never). */
    val lastReviewPromptMillis: StateFlow<Long>

    /**
     * Record that the rating card was launched. Called whether or not the card
     * actually appeared: Google never tells us, and a burned quota slot counts
     * either way, so re-asking on the assumption it did not show would be exactly
     * the nagging the limit exists to prevent.
     */
    fun markReviewPrompted(nowMillis: Long)
    /** Reflect the real OS notification-permission state into the profile (persisted). */
    fun setNotificationsEnabled(enabled: Boolean)
    fun setAppTheme(theme: AppThemeSetting)
    fun toggleOfflineMode()
    fun setOfflineMode(offline: Boolean)

    fun setSearchQuery(query: String)
    fun setCategoryFilter(category: OfferCategory)
    fun setFilterState(filter: OfferFilterState)
    fun clearFilters()

    fun toggleFavourite(offerId: String)
    /** Deterministic favourite set, so a "Removed from favourites" Undo restores the exact prior state. */
    fun setFavourite(offerId: String, isFavourite: Boolean)
    /** Re-load the cached catalogue (retry after an error / manual refresh). */
    suspend fun refreshCatalogue()
    fun setDevStkOutcome(outcome: DevStkOutcome)

    /**
     * Online M-Pesa STK Push. [clientRequestId] is the idempotency key: repeating it
     * must never create a second charge (double-tap, retry). Runs the payment state
     * machine behind the payment gateway and returns the settled/honest record.
     *
     * [isForSelf] selects the route: buy-for-myself uses the configured gateway (real
     * backend/Daraja when a base URL is set — the Till lands the money on the seller's
     * own-number identity); buy-for-another remains a simulation in this phase.
     */
    suspend fun executeMpesaStkPush(
        offer: OfferItem,
        recipientNumber: String,
        payerNumber: String,
        clientRequestId: String,
        isForSelf: Boolean
    ): PurchaseRecord

    /**
     * Records a customer-marked offline payment. With an M-Pesa [receipt] the
     * attempt becomes **Waiting to verify**; without one it becomes **Payment not
     * confirmed** (Plan.md §5.8). Never returns success.
     */
    suspend fun executeOfflinePayment(
        offer: OfferItem,
        recipientNumber: String,
        payerNumber: String,
        isTill: Boolean,
        receipt: String?
    ): PurchaseRecord

    /** Whether [offer] can be bought offline via the chosen route, and why not otherwise. */
    fun offlineEligibility(offer: OfferItem, isForSelf: Boolean): OfflineEligibility

    /** The current verified offline config (Till/Paybill), or null when unavailable/expired. */
    fun offlineConfig(): OfflinePaymentConfig?

    /** Clear the restored active order (checkout dismissed or reached a terminal state). */
    fun clearActiveOrder()

    /**
     * Fetch fresh seller config from the server and update [appConfig]; safe to call
     * when online. Only a valid, complete response replaces the cache; a failure or an
     * all-blank (incomplete) response keeps the last good config.
     */
    override suspend fun syncRemoteConfig()

    /**
     * Fetch the catalogue from the server and replace the offers ONLY on a complete,
     * validated, non-empty response (each offer must have id, name, price, category and
     * validity), then bump the stored catalogue version. A failed, null, empty or
     * incomplete response keeps the existing locally-stored offers — never clearing or
     * partially overwriting them — so the app always has offers offline (Phase 7).
     * Local favourite/bought-today state is preserved across the replace.
     */
    override suspend fun syncCatalogue()

    /**
     * Fetch the Home billboards (promotions) from the server and replace [promotions]
     * ONLY on a non-null, non-empty response, then persist them so they survive process
     * death and stay available OFFLINE. A failed, null or empty response keeps the
     * existing locally-stored billboards — never clearing them — so the app always has
     * promotions offline. Promotions carry no per-user flags, so nothing is merged.
     */
    override suspend fun syncBillboards()

    /**
     * Refresh the notification wording published by the admin. A failed fetch keeps
     * the cached templates, and the in-APK seed is the guaranteed floor — the
     * customer can never end up with no notification copy.
     */
    override suspend fun syncNotificationTemplates()

    /**
     * Refresh the admin-published notification queue. Cached messages keep working
     * after the device goes offline; a null fetch never clears them.
     */
    override suspend fun syncRemoteNotifications()

    fun deletePurchaseRecord(recordId: String)
    fun deletePurchaseRecords(recordIds: List<String>)
    fun undoDeletePurchaseRecord(record: PurchaseRecord)

    /** Record the latest observed connectivity state (from [ConnectivityObserver]). */
    fun setConnectionState(state: ConnectionState)

    val fcmToken: StateFlow<String?>
    fun setFcmToken(token: String)

    fun addNotification(item: NotificationItem)
    fun markNotificationRead(id: String)
    fun markAllNotificationsRead()
    /** Remove a single notification from the local centre (customer "clear"). */
    fun deleteNotification(id: String)
    /** Remove every notification from the local centre (customer "clear all"). */
    fun clearAllNotifications()

    fun clearAllLocalData()
}
