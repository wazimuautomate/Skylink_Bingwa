package com.example.data.fake

import com.example.BuildConfig
import com.example.core.model.AppThemeSetting
import com.example.core.model.DailyRule
import com.example.core.model.NotificationItem
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.PaymentMethod
import com.example.core.model.PaymentStatus
import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionKind
import com.example.core.model.PurchasePolicy
import com.example.core.model.PurchaseRecord
import com.example.core.model.UserProfile
import com.example.core.notifications.ConnectionState
import com.example.core.payment.KenyanPhone
import com.example.core.payment.PaymentTxnState
import com.example.data.catalogue.RemoteBillboardSource
import com.example.data.catalogue.RemoteCatalogueSource
import com.example.data.config.AppConfig
import com.example.data.config.RemoteConfigSource
import com.example.data.payment.ActiveOrder
import com.example.data.payment.CachedOfflineConfigProvider
import com.example.data.payment.OfflineConfigResult
import com.example.data.payment.OfflineEligibility
import com.example.data.payment.OfflineEligibilityChecker
import com.example.data.payment.OfflinePaymentConfig
import com.example.data.payment.OfflinePaymentConfigProvider
import com.example.data.payment.PaymentGateway
import com.example.data.payment.PaymentTransportException
import com.example.data.payment.SimulatedPaymentGateway
import com.example.data.payment.StkPushRequest
import com.example.data.payment.StkStatusQuery
import com.example.data.persistence.PersistedState
import com.example.data.remote.RemoteCustomerSource
import com.example.data.persistence.SnapshotStore
import com.example.data.sync.ContentSyncers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * App-readable data source. State lives in [MutableStateFlow]s and — when a
 * [localStore] is injected (the running app always injects one) — is loaded from and
 * saved to on-device persistence, so name, profile, favourites, Activity,
 * notifications and any in-flight order survive process death. Unit tests inject no
 * store and run purely in memory.
 *
 * [gateway] handles online M-Pesa (real backend proxy in a configured build).
 * [fallbackGateway] is used when no backend is configured — a labelled simulation in
 * debug, or an honest-failing [UnavailablePaymentGateway] in release so a
 * misconfigured production build never fabricates a success. [configProvider] supplies
 * the offline Till/Paybill config.
 */
class FakeBingwaRepositoryImpl(
    gateway: PaymentGateway? = null,
    fallbackGateway: PaymentGateway? = null,
    private val configProvider: OfflinePaymentConfigProvider = CachedOfflineConfigProvider(),
    private val configSource: RemoteConfigSource? = null,
    private val catalogueSource: RemoteCatalogueSource? = null,
    private val billboardSource: RemoteBillboardSource? = null,
    // Notification templates, admin-published notifications and SMS rules. Null (the
    // default) means those resources simply are not synced — the app runs on its
    // in-APK seed, which is exactly what an unconfigured build and every unit test want.
    private val contentSyncers: ContentSyncers? = null,
    // Sends the customer's name + number to the seller's backend once per install.
    // Null (the default) means the build simply does not register anyone, which is
    // what an unconfigured build and every unit test want.
    private val customerSource: RemoteCustomerSource? = null,
    private val localStore: SnapshotStore? = null,
    // The dispatcher persistence/restore run on. The app uses IO; tests can inject a
    // deterministic dispatcher so a restore is observable synchronously.
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Seed data for TESTS only. The real app always uses the empty defaults so a
    // fresh install has no prefilled Activity / notifications / recipients.
    seedPurchases: List<PurchaseRecord> = emptyList(),
    seedNotifications: List<NotificationItem> = emptyList(),
    seedRecentRecipients: List<String> = emptyList()
) : BingwaRepository {

    // Fallback used when no real backend is configured. MainActivity injects a
    // labelled simulation for debug builds and an UnavailablePaymentGateway for
    // release (which fails honestly rather than faking a success). With no injection
    // (unit tests) it defaults to a dev simulation wired to the DevStkOutcome switch.
    private val fallback: PaymentGateway = fallbackGateway ?: SimulatedPaymentGateway(
        terminalOutcome = { devOutcomeToState(_devStkOutcome.value) }
    )

    // Buy-for-myself uses the real backend gateway when one is configured.
    private val selfGateway: PaymentGateway = gateway ?: fallback

    // Buy-for-another now also goes through the real backend when configured: the
    // request carries forSelf=false, so the backend charges the payer (Paybill, with
    // the recipient's number as the account reference) and, on success, sends the
    // fulfilment operator a mocked M-Pesa SMS naming the RECIPIENT (server callback.php,
    // per docs/"Buy For Another Number - Implementation Spec.md"). Falls back to the
    // simulation only when no backend is configured.
    private val anotherNumberGateway: PaymentGateway = gateway ?: fallback

    // A fresh install has NO prefilled identity: empty name/number and onboarding
    // NOT completed, so MainActivity starts on onboarding on first launch and the
    // customer enters their own details. Never seed real details here.
    private val defaultProfile = UserProfile(
        name = "",
        primaryNumber = "",
        isOnboardingCompleted = false,
        notificationsEnabled = false
    )

    private val _userProfile = MutableStateFlow(defaultProfile)
    override val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _appTheme = MutableStateFlow(AppThemeSetting.SYSTEM)
    override val appTheme: StateFlow<AppThemeSetting> = _appTheme.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    override val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    // Real Skylink Bingwa catalogue. name = allowance, validity = the duration shown
    // next to it on the card, validityBand = the Offers filter band, dailyRule =
    // the "Buy once a day" / "Buy many times" tag.
    private fun dataOffer(
        id: String, name: String, validity: String, band: String, price: Int,
        once: Boolean, category: OfferCategory = OfferCategory.DATA, favourite: Boolean = false
    ) = OfferItem(
        id = id,
        name = name,
        allowance = name,
        priceKsh = price,
        validity = validity,
        validityBand = band,
        category = category,
        dailyRule = if (once) DailyRule.ONCE_PER_DAY else DailyRule.BUY_AGAIN_TODAY,
        isFavourite = favourite,
        description = "$name of ${category.label.lowercase()} valid $validity."
    )

    private val initialOffers = listOf(
        // Data
        dataOffer("data_1", "1GB", "1 Hr", "Hourly", 19, once = true),
        dataOffer("data_2", "250MB", "24 Hrs", "Daily", 20, once = true),
        dataOffer("data_3", "1.5GB", "3 Hrs", "Hourly", 50, once = true),
        dataOffer("data_5", "1GB", "24 Hrs", "Daily", 95, once = true),
        dataOffer("data_6", "2GB", "24 Hrs", "Daily", 110, once = false),
        dataOffer("data_7", "350MB", "7 days", "Weekly", 49, once = true),
        dataOffer("data_8", "2.5GB", "7 days", "Weekly", 300, once = true),
        dataOffer("data_9", "6GB", "7 days", "Weekly", 700, once = true),
        dataOffer("data_10", "1.2GB", "30 days", "Monthly", 250, once = true),
        dataOffer("data_11", "2.5GB", "30 days", "Monthly", 500, once = true),
        dataOffer("data_12", "10GB", "30 days", "Monthly", 1000, once = true),
        dataOffer("data_13", "8GB + 400 Min", "30 days", "Monthly", 1005, once = true),
        // SMS
        dataOffer("sms_1", "10 SMS", "24 Hrs", "Daily", 5, once = false, category = OfferCategory.SMS),
        dataOffer("sms_2", "200 SMS", "24 Hrs", "Daily", 10, once = false, category = OfferCategory.SMS),
        dataOffer("sms_3", "1,000 SMS", "7 days", "Weekly", 30, once = false, category = OfferCategory.SMS),
        dataOffer("sms_4", "1,500 SMS", "30 days", "Monthly", 101, once = false, category = OfferCategory.SMS),
        dataOffer("sms_5", "3,500 SMS", "30 days", "Monthly", 201, once = false, category = OfferCategory.SMS),
        // Minutes
        dataOffer("min_1", "20 Min", "Midnight", "Daily", 22, once = false, category = OfferCategory.MINUTES),
        dataOffer("min_2", "35 Min", "2 Hrs", "Hourly", 23, once = false, category = OfferCategory.MINUTES),
        dataOffer("min_3", "45 Min", "3 Hrs", "Hourly", 24, once = false, category = OfferCategory.MINUTES),
        dataOffer("min_4", "50 Min", "Midnight", "Daily", 48, once = false, category = OfferCategory.MINUTES),
        dataOffer("min_5", "250 Min", "7 days", "Weekly", 205, once = false, category = OfferCategory.MINUTES),
        dataOffer("min_6", "100 Min", "Midnight", "Daily", 105, once = false, category = OfferCategory.MINUTES),
        dataOffer("min_7", "300 Min", "30 days", "Monthly", 499, once = false, category = OfferCategory.MINUTES),
        dataOffer("min_8", "800 Min", "30 days", "Monthly", 950, once = false, category = OfferCategory.MINUTES),
        // Special
        dataOffer("spec_1", "1GB", "1 Hr", "Hourly", 21, once = true, category = OfferCategory.SPECIAL),
        dataOffer("spec_2", "1.5GB", "3 Hrs", "Hourly", 51, once = true, category = OfferCategory.SPECIAL),
        dataOffer("spec_3", "2GB", "24 Hrs", "Daily", 110, once = false, category = OfferCategory.SPECIAL)
    )

    private val _offers = MutableStateFlow(initialOffers)
    override val offers: StateFlow<List<OfferItem>> = _offers.asStateFlow()

    // Local revision of the persisted catalogue. Starts at 0 (only the seeded
    // catalogue) and increases by one every time a COMPLETE, validated, non-empty
    // server catalogue is committed — so a failed/empty sync (which keeps the old
    // offers) is distinguishable from a real update. Persisted alongside [offers].
    // Not part of the public [BingwaRepository] contract; exposed on the concrete
    // class for sync bookkeeping and tests. When the app later adopts the versioned
    // /api/v1/app/sync endpoint (docs/APP_SYNC_CONTRACT.md), the server configVersion
    // becomes the source of this value.
    private val _catalogueVersion = MutableStateFlow(0L)
    val catalogueVersion: StateFlow<Long> = _catalogueVersion.asStateFlow()

    // The catalogue is cached, so the first load resolves immediately. The flag
    // still exists so Home/Offers can show skeletons on a cold, empty cache and
    // so Phase 6/7 can drive it from a real Room-then-network refresh.
    private val _catalogueLoading = MutableStateFlow(false)
    override val catalogueLoading: StateFlow<Boolean> = _catalogueLoading.asStateFlow()

    // Billboard promotions pool, synced from the admin (Plan.md §5.13). No gradients:
    // each slide paints a single brand colour (see PromotionAccent).
    //
    // NO billboard is hardcoded. The Home advert surface shows exactly what the owner
    // published in the admin and nothing else, so a slide can never advertise an offer,
    // a price or a message the seller did not choose. A fresh install therefore starts
    // with an empty pool until the first sync, and Home simply renders no billboard —
    // an intended quiet empty state, not a gap to be filled with samples.
    private val initialPromotions = emptyList<Promotion>()

    private val _promotions = MutableStateFlow(initialPromotions)
    override val promotions: StateFlow<List<Promotion>> = _promotions.asStateFlow()

    private val _filterState = MutableStateFlow(OfferFilterState())
    override val filterState: StateFlow<OfferFilterState> = _filterState.asStateFlow()

    // Fresh install starts with an empty Activity (no prefilled data); tests seed via
    // the [seedPurchases] constructor param.
    private val _purchases = MutableStateFlow(seedPurchases)
    override val purchases: StateFlow<List<PurchaseRecord>> = _purchases.asStateFlow()

    // Fresh install has no notifications and no recent recipients; tests seed via
    // [seedNotifications] / [seedRecentRecipients].
    private val _notifications = MutableStateFlow(seedNotifications)
    override val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    private val _recentRecipients = MutableStateFlow(seedRecentRecipients)
    override val recentRecipients: StateFlow<List<String>> = _recentRecipients.asStateFlow()

    private val _devStkOutcome = MutableStateFlow(DevStkOutcome.SUCCESS)
    override val devStkOutcome: StateFlow<DevStkOutcome> = _devStkOutcome.asStateFlow()

    private val _activeOrder = MutableStateFlow<ActiveOrder?>(null)
    override val activeOrder: StateFlow<ActiveOrder?> = _activeOrder.asStateFlow()

    // True once the seller's backend has confirmed who this customer is. Held in
    // memory and persisted, so the registration happens exactly once per install.
    private val _customerRegistered = MutableStateFlow(false)

    // When the Play rating card was last launched. Persisted, so the 60-day gap is
    // a real gap and not merely a per-session one.
    private val _lastReviewPromptMillis = MutableStateFlow(0L)
    override val lastReviewPromptMillis: StateFlow<Long> = _lastReviewPromptMillis.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.NONE)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Seller config: seeded from the last sync (or defaults) so it is available
    // offline immediately; refreshed by syncRemoteConfig() when online.
    private val _appConfig = MutableStateFlow(configSource?.cached() ?: AppConfig.DEFAULT)
    override val appConfig: StateFlow<AppConfig> = _appConfig.asStateFlow()

    // --- Real on-device persistence (installation-local; no cloud/account) ---------
    // When a SnapshotStore is injected, state is loaded from disk on start and every
    // mutation re-saves the whole (small) snapshot, so name, profile, favourites,
    // synced offers, Activity, notifications and any in-flight order survive process
    // death. Unit tests inject no store (or a tiny in-memory one).
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val saveTick = MutableStateFlow(0L)

    // Completes once the on-disk restore has finished (or immediately when there is
    // no store, e.g. unit tests). A background sync awaits this before committing, so
    // it can never persist a blank snapshot over the customer's real local data
    // during the brief cold-start restore window.
    private val restoreComplete = CompletableDeferred<Unit>()

    init {
        val store = localStore
        if (store != null) {
            ioScope.launch {
                restoreFromDisk(store)
                restoreComplete.complete(Unit)
                // Persist on every subsequent mutation (StateFlow conflates bursts).
                saveTick.collect { store.save(currentSnapshot()) }
            }
        } else {
            restoreComplete.complete(Unit)
        }
    }

    private suspend fun restoreFromDisk(store: SnapshotStore) {
        val s = store.load() ?: return
        // A never-saved device keeps the seeded demo content; only override once the
        // customer has real saved state (also lets Clear local data persist as empty).
        if (!s.initialized) return
        s.profile?.let { _userProfile.value = it }
        s.theme?.let { name ->
            runCatching { AppThemeSetting.valueOf(name) }.getOrNull()?.let { _appTheme.value = it }
        }
        _purchases.value = s.purchases
        _notifications.value = s.notifications
        _recentRecipients.value = s.recentRecipients
        _catalogueVersion.value = s.catalogueVersion
        _customerRegistered.value = s.customerRegistered
        _lastReviewPromptMillis.value = s.lastReviewPromptMillis
        // Previously synced billboards are the offline source of truth; fall back to the
        // seeded promotions only when nothing has been synced yet. No per-user flags to
        // merge (unlike offers), so this is a plain restore.
        _promotions.value = s.promotions.ifEmpty { initialPromotions }
        val favs = s.favouriteIds.toSet()
        val bought = s.boughtTodayIds.toSet()
        // Previously synced offers are the offline source of truth; fall back to the
        // seeded catalogue only when nothing has been synced yet. Local favourite /
        // bought-today state is re-applied on top so it always wins (same merge the
        // in-memory refresh/sync uses).
        val base = s.offers.ifEmpty { initialOffers }
        _offers.value = base.map {
            it.copy(isFavourite = favs.contains(it.id), isBoughtToday = bought.contains(it.id))
        }
        // Safe process-death payment restore: an order still in-flight when the app
        // died is settled to an honest "Waiting to verify" — never silently lost and
        // never re-charged — so the customer can follow it up in Activity.
        s.activeOrder?.let { restoreUnfinishedOrder(it) }
    }

    private fun restoreUnfinishedOrder(order: ActiveOrder) {
        _activeOrder.value = null
        // Idempotent: if it already produced a record, do nothing.
        if (_purchases.value.any { it.clientRequestId == order.clientRequestId }) return
        val record = PurchaseRecord(
            id = "pur_" + UUID.randomUUID().toString().take(8),
            offerId = order.offerId,
            offerName = order.offerName,
            allowance = order.offerName,
            priceKsh = order.priceKsh,
            recipientNumber = order.recipientNumber,
            payerNumber = order.payerNumber,
            mpesaCode = "-",
            timestampMillis = System.currentTimeMillis(),
            status = PaymentStatus.WAITING_VERIFY,
            paymentMethod = PaymentMethod.STK_PUSH,
            clientRequestId = order.clientRequestId,
            orderReference = order.orderReference ?: ""
        )
        _purchases.update { listOf(record) + it }
    }

    private fun currentSnapshot(): PersistedState = PersistedState(
        profile = _userProfile.value,
        theme = _appTheme.value.name,
        favouriteIds = _offers.value.filter { it.isFavourite }.map { it.id },
        boughtTodayIds = _offers.value.filter { it.isBoughtToday }.map { it.id },
        purchases = _purchases.value,
        notifications = _notifications.value,
        recentRecipients = _recentRecipients.value,
        activeOrder = _activeOrder.value,
        // Persist the synced catalogue (without per-device favourite/bought flags, which
        // are stored separately above and re-applied on restore) and its local version,
        // so the UI keeps reading offers from on-device storage — offline and across
        // process death.
        offers = _offers.value.map { it.copy(isFavourite = false, isBoughtToday = false) },
        catalogueVersion = _catalogueVersion.value,
        // Persist the synced billboards so the Home promotions surface stays populated
        // offline and across process death (no per-user flags to strip).
        promotions = _promotions.value,
        customerRegistered = _customerRegistered.value,
        lastReviewPromptMillis = _lastReviewPromptMillis.value,
        initialized = true
    )

    /** Signal a save of the current snapshot (no-op when persistence is disabled). */
    private fun persist() {
        if (localStore != null) saveTick.value = saveTick.value + 1
    }

    override fun updateProfile(name: String, primaryNumber: String) {
        _userProfile.update { it.copy(name = name, primaryNumber = primaryNumber) }
        persist()
    }

    override fun setNotificationsEnabled(enabled: Boolean) {
        _userProfile.update { it.copy(notificationsEnabled = enabled) }
        persist()
    }

    override fun setOnboardingCompleted(completed: Boolean) {
        _userProfile.update { it.copy(isOnboardingCompleted = completed) }
        persist()
    }

    override fun setAppTheme(theme: AppThemeSetting) {
        _appTheme.value = theme
        persist()
    }

    override fun toggleOfflineMode() {
        _isOffline.value = !_isOffline.value
    }

    override fun setOfflineMode(offline: Boolean) {
        _isOffline.value = offline
    }

    override fun setSearchQuery(query: String) {
        _filterState.update { it.copy(searchQuery = query) }
    }

    override fun setCategoryFilter(category: OfferCategory) {
        _filterState.update { it.copy(selectedCategory = category) }
    }

    override fun setFilterState(filter: OfferFilterState) {
        _filterState.value = filter
    }

    override fun clearFilters() {
        _filterState.value = OfferFilterState()
    }

    override fun toggleFavourite(offerId: String) {
        _offers.update { list ->
            list.map { offer ->
                if (offer.id == offerId) offer.copy(isFavourite = !offer.isFavourite) else offer
            }
        }
        persist()
    }

    override fun setFavourite(offerId: String, isFavourite: Boolean) {
        _offers.update { list ->
            list.map { offer ->
                if (offer.id == offerId) offer.copy(isFavourite = isFavourite) else offer
            }
        }
        persist()
    }

    override suspend fun refreshCatalogue() {
        _catalogueLoading.value = true
        delay(400) // Simulate a cached-first read; the cache is already warm.
        _offers.value = initialOffers.map { fresh ->
            // Preserve the customer's local favourite/bought-today state across a refresh.
            val current = _offers.value.find { it.id == fresh.id }
            if (current != null) fresh.copy(
                isFavourite = current.isFavourite,
                isBoughtToday = current.isBoughtToday
            ) else fresh
        }
        _catalogueLoading.value = false
    }

    override fun setDevStkOutcome(outcome: DevStkOutcome) {
        _devStkOutcome.value = outcome
    }

    private fun devOutcomeToState(outcome: DevStkOutcome): PaymentTxnState = when (outcome) {
        DevStkOutcome.SUCCESS -> PaymentTxnState.PAYMENT_CONFIRMED
        DevStkOutcome.CANCELLED -> PaymentTxnState.CANCELLED
        DevStkOutcome.FAILED -> PaymentTxnState.PAYMENT_FAILED
        // A delayed prompt never reaches a terminal result within the polling
        // window; the honest outcome is "still checking" (Waiting to verify).
        DevStkOutcome.DELAYED -> PaymentTxnState.AWAITING_APPROVAL
    }

    override suspend fun executeMpesaStkPush(
        offer: OfferItem,
        recipientNumber: String,
        payerNumber: String,
        clientRequestId: String,
        isForSelf: Boolean
    ): PurchaseRecord {
        // Idempotency: a repeated clientRequestId (double-tap, retry) returns the
        // existing record instead of charging again (Plan.md §API idempotency).
        _purchases.value.firstOrNull { it.clientRequestId == clientRequestId }?.let { return it }

        val gatewayForRoute = if (isForSelf) selfGateway else anotherNumberGateway

        val payerMsisdn = KenyanPhone.toMsisdn(payerNumber)
        val recipientMsisdn = KenyanPhone.toMsisdn(recipientNumber)
        if (payerMsisdn == null || recipientMsisdn == null) {
            return settle(
                offer, recipientNumber, payerNumber, clientRequestId,
                PaymentStatus.FAILED, PaymentMethod.STK_PUSH, mpesaCode = "-", orderReference = ""
            )
        }

        _activeOrder.value = ActiveOrder(
            clientRequestId = clientRequestId,
            offerId = offer.id,
            offerName = offer.name,
            priceKsh = offer.priceKsh,
            recipientNumber = recipientNumber,
            payerNumber = payerNumber,
            isForSelf = isForSelf,
            state = PaymentTxnState.DRAFT
        )
        // Persist the in-flight order so a process death mid-payment can be restored.
        persist()

        val request = StkPushRequest(
            offerId = offer.id,
            amountKsh = offer.priceKsh,
            payerMsisdn = payerMsisdn,
            recipientMsisdn = recipientMsisdn,
            clientRequestId = clientRequestId,
            forSelf = isForSelf
        )

        var result = try {
            gatewayForRoute.initiateStkPush(request)
        } catch (e: PaymentTransportException) {
            _activeOrder.value = null
            return settle(
                offer, recipientNumber, payerNumber, clientRequestId,
                PaymentStatus.FAILED, PaymentMethod.STK_PUSH, mpesaCode = "-", orderReference = ""
            )
        }
        _activeOrder.update { it?.copy(state = result.state, orderReference = result.orderReference) }

        // Poll until a terminal result or the polling window is exhausted. Wait
        // between polls so the real backend/Daraja query isn't hammered while the
        // customer is entering their PIN (~POLL_INTERVAL_MILLIS x MAX_STATUS_POLLS
        // total). Under test the delay is virtual (runTest advances it instantly).
        var attempts = 0
        while (!result.state.isTerminal && attempts < MAX_STATUS_POLLS) {
            attempts++
            delay(POLL_INTERVAL_MILLIS)
            result = try {
                gatewayForRoute.queryStatus(StkStatusQuery(clientRequestId, result.orderReference))
            } catch (e: PaymentTransportException) {
                break
            }
            _activeOrder.update { it?.copy(state = result.state, orderReference = result.orderReference) }
        }

        val status = if (result.state.isTerminal) {
            result.state.toRecordStatus() ?: PaymentStatus.WAITING_VERIFY
        } else {
            // Non-terminal after polling → honest "still checking" (Waiting to verify).
            PaymentStatus.WAITING_VERIFY
        }
        _activeOrder.value = null

        return settle(
            offer, recipientNumber, payerNumber, clientRequestId,
            status, PaymentMethod.STK_PUSH,
            mpesaCode = result.mpesaReceipt?.takeIf { status == PaymentStatus.RECEIVED } ?: "-",
            orderReference = result.orderReference ?: ""
        )
    }

    override suspend fun executeOfflinePayment(
        offer: OfferItem,
        recipientNumber: String,
        payerNumber: String,
        isTill: Boolean,
        receipt: String?
    ): PurchaseRecord {
        delay(400)
        val trimmed = receipt?.trim().orEmpty()
        // With a receipt → Waiting to verify; without one → Payment not confirmed.
        val status = if (trimmed.isNotEmpty()) PaymentStatus.WAITING_VERIFY else PaymentStatus.NOT_CONFIRMED
        return settle(
            offer, recipientNumber, payerNumber,
            clientRequestId = "off_" + UUID.randomUUID().toString().take(8),
            status = status,
            method = if (isTill) PaymentMethod.TILL else PaymentMethod.PAYBILL,
            mpesaCode = trimmed.ifEmpty { "OFFLINE_PENDING" },
            orderReference = ""
        )
    }

    /** Builds, persists and cross-updates a settled record (bought-today, notifications, recents). */
    private fun settle(
        offer: OfferItem,
        recipientNumber: String,
        payerNumber: String,
        clientRequestId: String,
        status: PaymentStatus,
        method: PaymentMethod,
        mpesaCode: String,
        orderReference: String
    ): PurchaseRecord {
        val record = PurchaseRecord(
            id = "pur_" + UUID.randomUUID().toString().take(8),
            offerId = offer.id,
            offerName = offer.name,
            allowance = offer.allowance,
            priceKsh = offer.priceKsh,
            recipientNumber = recipientNumber,
            payerNumber = payerNumber,
            mpesaCode = mpesaCode,
            timestampMillis = System.currentTimeMillis(),
            status = status,
            paymentMethod = method,
            clientRequestId = clientRequestId,
            orderReference = orderReference
        )
        _purchases.update { listOf(record) + it }

        if (status == PaymentStatus.RECEIVED && offer.dailyRule == DailyRule.ONCE_PER_DAY) {
            _offers.update { list ->
                list.map { item -> if (item.id == offer.id) item.copy(isBoughtToday = true) else item }
            }
        }

        if (status == PaymentStatus.RECEIVED) {
            val newNotif = NotificationItem(
                id = "notif_" + UUID.randomUUID().toString().take(6),
                title = "Your payment was received",
                body = "The ${offer.allowance} offer for $recipientNumber was recorded.",
                timestampMillis = System.currentTimeMillis(),
                isRead = false
            )
            _notifications.update { listOf(newNotif) + it }
        }

        if (recipientNumber.isNotBlank() && !_recentRecipients.value.contains(recipientNumber)) {
            _recentRecipients.update { listOf(recipientNumber) + it }
        }
        persist()
        return record
    }

    override fun offlineEligibility(offer: OfferItem, isForSelf: Boolean): OfflineEligibility =
        OfflineEligibilityChecker.check(
            offer = offer,
            isForSelf = isForSelf,
            catalogue = _offers.value,
            config = configProvider.load(System.currentTimeMillis()),
            nowMillis = System.currentTimeMillis()
        )

    override fun offlineConfig(): OfflinePaymentConfig? {
        // Eligibility (expiry/ambiguity) still comes from the signed provider, but the
        // displayed Till/Paybill are the server-synced values (always available offline).
        if (configProvider.load(System.currentTimeMillis()) !is OfflineConfigResult.Valid) return null
        val cfg = _appConfig.value
        // The APK ships the seller's current Till/Paybill (AppConfig.DEFAULT), so an
        // install that has never reached the server still has a number to pay. This
        // guard remains for the one case that is genuinely unusable: a build with no
        // seed values configured AND no successful sync — rendering offline
        // instructions with an empty number to copy is worse than saying "connect to
        // refresh" (CLAUDE.md §7: ambiguous offline configuration disables payment).
        if (cfg.isBlankConfig()) return null
        return OfflinePaymentConfig(
            tillNumber = cfg.tillNumber,
            paybillNumber = cfg.paybillNumber,
            issuedAtMillis = 0L,
            expiresAtMillis = Long.MAX_VALUE,
            signatureValid = true
        )
    }

    override fun clearActiveOrder() {
        _activeOrder.value = null
        persist()
    }

    override fun deletePurchaseRecord(recordId: String) {
        _purchases.update { list -> list.filterNot { it.id == recordId } }
        persist()
    }

    override fun deletePurchaseRecords(recordIds: List<String>) {
        _purchases.update { list -> list.filterNot { recordIds.contains(it.id) } }
        persist()
    }

    override fun undoDeletePurchaseRecord(record: PurchaseRecord) {
        _purchases.update { listOf(record) + it }
        persist()
    }

    override fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
        // Real connectivity is now the source of truth for the offline flag: no
        // transport at all = offline. Any Wi-Fi/cellular link = online (Phase 6).
        _isOffline.value = (state == ConnectionState.NONE)
    }

    /**
     * Tell the seller's backend who this customer is, once per install.
     *
     * Called after onboarding completes and again on every app start, because the
     * first attempt is exactly the one most likely to fail: a customer finishing
     * onboarding on a weak connection. Each of the three guards below is a real case:
     *
     *  - already registered  → the whole point; the name/number are sent ONCE.
     *  - onboarding unfinished, or no name/number yet → nothing worth sending.
     *  - the backend refused → leave the flag false and try again next launch. The
     *    endpoint is idempotent on the number, so a retry that turns out to have
     *    already landed updates that customer rather than duplicating them.
     *
     * This is the only customer detail that ever leaves the device. Purchases,
     * favourites and behaviour stay on the phone (CLAUDE.md §10).
     */
    private val _fcmToken = MutableStateFlow<String?>(null)
    override val fcmToken: StateFlow<String?> = _fcmToken.asStateFlow()

    override fun setFcmToken(token: String) {
        if (_fcmToken.value == token) return
        _fcmToken.value = token
        CoroutineScope(Dispatchers.IO).launch {
            val source = customerSource ?: return@launch
            val profile = _userProfile.value
            if (!profile.isOnboardingCompleted) return@launch
            val name = profile.name.trim()
            val number = profile.primaryNumber.filter { it.isDigit() }
            if (name.isEmpty() || number.length < 9) return@launch
            source.register(name = name, msisdn = number, appVersion = BuildConfig.VERSION_NAME, fcmToken = token)
        }
    }

    override fun markReviewPrompted(nowMillis: Long) {
        _lastReviewPromptMillis.value = nowMillis
        persist()
    }

    override suspend fun registerCustomer() {
        val source = customerSource ?: return
        // Never race the restore: registering before the on-disk snapshot has loaded
        // would read customerRegistered = false on every single launch and re-send.
        restoreComplete.await()
        if (_customerRegistered.value) return

        val profile = _userProfile.value
        if (!profile.isOnboardingCompleted) return
        val name = profile.name.trim()
        val number = profile.primaryNumber.filter { it.isDigit() }
        if (name.isEmpty() || number.length < 9) return

        val ok = try {
            source.register(name = name, msisdn = number, appVersion = BuildConfig.VERSION_NAME, fcmToken = _fcmToken.value)
        } catch (t: Throwable) {
            false
        }
        if (ok) {
            _customerRegistered.value = true
            persist()
        }
    }

    override suspend fun syncRemoteConfig() {
        val source = configSource ?: return
        val fresh = try {
            source.fetch()
        } catch (t: Throwable) {
            null
        } ?: return // Failure/no response → keep the last good cached config.
        // An all-blank config is treated as an incomplete response: keep the last good
        // config rather than blanking Till/Paybill/support that are already known.
        if (fresh.isBlankConfig() && !_appConfig.value.isBlankConfig()) return
        _appConfig.value = fresh
    }

    override suspend fun syncCatalogue() {
        val source = catalogueSource ?: return
        // Never sync into un-restored state: wait for the on-disk restore so a
        // background sync merges into (and persists over) the real local data, never a
        // blank cold-start snapshot.
        restoreComplete.await()

        val fresh = try {
            source.fetch()
        } catch (t: Throwable) {
            null
        } ?: return // Failure/null → keep the existing locally-stored offers.

        // Validate the payload BEFORE committing anything. A single incomplete offer
        // (missing id/name/price/category/validity) makes the whole payload suspect, so
        // we reject it and keep the last good catalogue rather than commit partial data.
        if (fresh.isEmpty() || fresh.any { !isValidOffer(it) }) return

        // Complete + validated: replace offers, preserving the customer's local
        // favourite/bought-today state, and bump the stored catalogue version.
        _offers.value = fresh.map { server ->
            val current = _offers.value.find { it.id == server.id }
            if (current != null) {
                server.copy(isFavourite = current.isFavourite, isBoughtToday = current.isBoughtToday)
            } else {
                server
            }
        }
        _catalogueVersion.value = _catalogueVersion.value + 1
        persist()
    }

    override suspend fun syncBillboards() {
        val source = billboardSource ?: return
        // Never sync into un-restored state: wait for the on-disk restore so a background
        // sync replaces (and persists over) the real local promotions, never a blank
        // cold-start snapshot (same guard as syncCatalogue()).
        restoreComplete.await()

        val fresh = try {
            source.fetch()
        } catch (t: Throwable) {
            null
        } ?: return // Failure/null → keep the existing locally-stored billboards.

        // A successful response replaces the pool wholesale, INCLUDING an empty one:
        // no billboard is hardcoded, so removing them all in the admin must clear the
        // board on the device rather than leave stale adverts running forever. Only a
        // failure to reach the server (null above) preserves what is already there.
        // Promotions carry no per-user flags, so nothing local is lost.
        _promotions.value = fresh
        persist()
    }

    // --- Engine content syncs ---------------------------------------------------
    // Notification templates and admin-published notifications live in their own
    // stores, owned by the notification engine. The repository is only the
    // [SyncTargets] front door for them, so it delegates to [ContentSyncers] rather
    // than learning about those stores. With no syncers injected (no base URL
    // configured, or a bare unit-test construction) each call is a silent no-op and
    // the app keeps its in-APK seed — never an error, never an empty state.

    override suspend fun syncNotificationTemplates() {
        contentSyncers?.syncNotificationTemplates()
    }

    override suspend fun syncRemoteNotifications() {
        contentSyncers?.syncRemoteNotifications()
    }

    /**
     * An offer is complete only with the required fields present: a non-blank id,
     * name and validity, a positive price, and a real product category (not the
     * ALL/FAVOURITES filter pseudo-categories). Incomplete offers are rejected so a
     * malformed sync never overwrites good local data.
     */
    private fun isValidOffer(offer: OfferItem): Boolean =
        offer.id.isNotBlank() &&
            offer.name.isNotBlank() &&
            offer.priceKsh > 0 &&
            offer.validity.isNotBlank() &&
            offer.category != OfferCategory.ALL &&
            offer.category != OfferCategory.FAVOURITES

    private fun AppConfig.isBlankConfig(): Boolean =
        tillNumber.isBlank() && paybillNumber.isBlank() &&
            supportNumber.isBlank() && supportWhatsapp.isBlank()

    override fun addNotification(item: NotificationItem) {
        _notifications.update { current -> listOf(item) + current.filterNot { it.id == item.id } }
        persist()
    }

    override fun markNotificationRead(id: String) {
        _notifications.update { list ->
            list.map { if (it.id == id) it.copy(isRead = true) else it }
        }
        persist()
    }

    override fun markAllNotificationsRead() {
        _notifications.update { list -> list.map { it.copy(isRead = true) } }
        persist()
    }

    override fun deleteNotification(id: String) {
        _notifications.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    override fun clearAllNotifications() {
        _notifications.value = emptyList()
        persist()
    }

    override fun clearAllLocalData() {
        _userProfile.value = UserProfile()
        _purchases.value = emptyList()
        _offers.update { list -> list.map { it.copy(isFavourite = false, isBoughtToday = false) } }
        // Clearing local data resets the install, so the next onboarding registers again.
        _customerRegistered.value = false
        _lastReviewPromptMillis.value = 0L
        _notifications.value = emptyList()
        _recentRecipients.value = emptyList()
        _activeOrder.value = null
        persist()
    }

    private companion object {
        /** Max status polls before falling back to an honest "still checking" result. */
        const val MAX_STATUS_POLLS = 10

        /** Wait between STK status polls (real backend + Daraja query take seconds). */
        const val POLL_INTERVAL_MILLIS = 3000L
    }
}
