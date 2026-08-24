package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.model.AppThemeSetting
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.offerAvailabilityAt
import com.example.core.model.Promotion
import com.example.core.model.PromotionClickAction
import com.example.core.model.PromotionKind
import com.example.core.notifications.AppNotifier
import com.example.core.notifications.ConnectionState
import com.example.core.notifications.ConnectivityObserver
import com.example.core.notifications.NotificationChannels
import com.example.core.notifications.engine.NotificationCategory
import com.example.core.notifications.engine.NotificationPersonalization
import com.example.core.personalization.BehaviourProfile
import com.example.core.personalization.PersonalizationEngine
import com.example.core.review.AppReviewLauncher
import com.example.core.review.ReviewPolicy
import com.example.core.personalization.suggestedPayerNumber
import com.example.data.sync.ForceSyncWatcher
import com.example.data.sync.SyncTrigger
import com.example.core.update.PlayUpdateManager
import com.example.core.update.UpdateChecker
import com.example.core.update.UpdatePromotion
import com.example.core.update.UpdateRequiredScreen
import com.example.core.update.UpdateResult
import com.example.core.update.UpdateSource
import com.example.core.update.openPlayStoreListing
import com.example.core.ui.BrandSplashOverlay
import com.example.core.ui.SkylinkBingwaBottomNav
import com.example.core.ui.OfferAvailabilityDialog
import com.example.core.ui.PermissionRequiredScreen
import com.example.core.ui.openAppSettings
import com.example.data.fake.BingwaRepository
import com.example.feature.activity.ActivityScreen
import com.example.feature.help.HelpScreen
import com.example.feature.home.CatalogueViewModel
import com.example.feature.home.repeatPurchaseBlockMessage
import com.example.feature.home.HomeScreen
import com.example.feature.notifications.NotificationsSheet
import com.example.feature.offers.OffersScreen
import com.example.feature.onboarding.OnboardingScreen
import com.example.feature.purchase.PurchaseBottomSheet
import com.example.feature.settings.SettingsScreen
import com.example.ui.theme.SkylinkBingwaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // The single process-wide repository, owned by SkylinkBingwaApplication so the UI and
    // the background CatalogueSyncWorker share one instance (same StateFlows, same
    // on-device LocalStore). `by lazy` because `application` is attached before
    // onCreate runs, where this is first read.
    private val repository: BingwaRepository by lazy {
        (application as SkylinkBingwaApplication).repository
    }

    // Pending notification-tap deep-link route (AppNotifier.EXTRA_DEEP_LINK_ROUTE).
    // Set from the launch intent and from onNewIntent (singleTop re-use); the
    // composable observes it, navigates, then clears it.
    private val deepLinkRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the system splash before super/setContent so the branded
        // launch mark shows on cold start, then hands off to the app theme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register the notification channels early (guards API < 26 internally).
        NotificationChannels.createChannels(this)

        // A cold launch from a notification tap carries the route here.
        deepLinkRoute.value = intent?.getStringExtra(AppNotifier.EXTRA_DEEP_LINK_ROUTE)

        setContent {
            val appTheme by repository.appTheme.collectAsState()
            val isOffline by repository.isOffline.collectAsState()
            val userProfile by repository.userProfile.collectAsState()

            val darkTheme = when (appTheme) {
                AppThemeSetting.SYSTEM -> isSystemInDarkTheme()
                AppThemeSetting.LIGHT -> false
                AppThemeSetting.DARK -> true
            }

            SkylinkBingwaTheme(darkTheme = darkTheme) {
                SkylinkBingwaApp(
                    repository = repository,
                    startOnboarding = !userProfile.isOnboardingCompleted,
                    deepLinkRoute = deepLinkRoute.asStateFlow(),
                    onConsumeDeepLink = { deepLinkRoute.value = null }
                )
            }
        }

        // Animated brand splash, layered over the Compose content AFTER setContent so
        // it is the top-most child of android.R.id.content. Cold start only
        // (savedInstanceState == null), so a rotation or activity re-creation never
        // replays it. It animates on top while the first composition, the cached
        // catalogue read and the initial sync all proceed underneath — it never gates
        // startup work (CLAUDE.md §11). The overlay removes itself from the view
        // hierarchy when it finishes.
        if (savedInstanceState == null) {
            BrandSplashOverlay.play(this, reducedMotion = isReducedMotion())
        }
    }

    /**
     * Reduced-motion proxy: the device animator-duration-scale = 0 setting
     * (design.md §11 "Respect Android reduced-motion settings"). Read defensively —
     * the setting is absent on some OEM builds, and a splash must never be the thing
     * that crashes a cold start.
     */
    private fun isReducedMotion(): Boolean = runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    // singleTop re-uses this activity for a notification tap while it is running;
    // capture the fresh route so the composable can deep-link.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkRoute.value = intent.getStringExtra(AppNotifier.EXTRA_DEEP_LINK_ROUTE)
    }
}

@Composable
fun SkylinkBingwaApp(
    repository: BingwaRepository,
    startOnboarding: Boolean,
    deepLinkRoute: StateFlow<String?> = MutableStateFlow(null),
    onConsumeDeepLink: () -> Unit = {}
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appContext = context.applicationContext

    // Notification + connectivity framework (constructed manually — no Hilt).
    val appNotifier = remember { AppNotifier(appContext) }
    val connectivityObserver = remember { ConnectivityObserver(appContext) }

    // The app-scoped engines. Null in a Robolectric/preview context where the
    // Application is not SkylinkBingwaApplication — every call site degrades gracefully
    // rather than crashing a test or a preview.
    val app = appContext as? SkylinkBingwaApplication
    val syncOrchestrator = remember(app) { app?.syncOrchestrator }
    val notificationEngine = remember(app) { app?.notificationEngine }

    /**
     * The customer's learned purchase behaviour. Loaded from its own on-device store
     * for an instant cold start, then recomputed whenever Activity changes. It NEVER
     * leaves the device (CLAUDE.md §10) and disappears with app data.
     */
    val behaviourProfile = remember { MutableStateFlow(BehaviourProfile.EMPTY) }

    /**
     * Facts the notification engine substitutes into its templates. Reads the flows'
     * current values rather than composed state so it can be called from any
     * coroutine without depending on where it sits in the composition.
     */
    fun personalizationFacts(
        balanceText: String = "",
        categoryLabel: String = ""
    ): NotificationPersonalization {
        val profile = behaviourProfile.value
        val usual = repository.offers.value.firstOrNull { it.id == profile.mostPurchasedOfferId }
        return NotificationPersonalization(
            userName = repository.userProfile.value.name,
            nowMillis = System.currentTimeMillis(),
            usualBundleLabel = usual?.name.orEmpty(),
            usualAmountKsh = profile.mostPurchasedAmountKsh,
            balanceText = balanceText,
            daysSinceLastPurchase = daysSinceNairobi(profile.lastPurchaseAtMillis),
            categoryLabel = categoryLabel.ifEmpty {
                profile.favouriteCategory?.label?.lowercase().orEmpty()
            }
        )
    }

    // Feed observed connectivity into the repository (sets the offline flag) and drive
    // an INCREMENTAL sync on the offline→online edge. The orchestrator decides which
    // resources actually changed, so returning to coverage no longer re-downloads the
    // whole catalogue. Losing connectivity is reported instantly (the observer only
    // debounces the online direction), so Offline Mode appears without a restart.
    //
    // The connectivity notifications are one of the most-liked behaviours in testing:
    // the app noticing you dropped offline. The engine's cooldown (hours, not minutes)
    // is what keeps that from becoming spam on a flaky connection.
    LaunchedEffect(connectivityObserver) {
        var seenFirstState = false
        var wasOffline = false
        connectivityObserver.observe().collect { state ->
            repository.setConnectionState(state)
            val offline = state == ConnectionState.NONE

            if (!offline) {
                syncOrchestrator?.sync(SyncTrigger.CONNECTIVITY_RESTORED)
                    ?: run {
                        // No orchestrator (unconfigured build/tests): fall back to the
                        // direct calls so an unwired build still refreshes.
                        repository.syncRemoteConfig()
                        repository.syncCatalogue()
                        repository.syncBillboards()
                    }
            }

            // Only announce a real TRANSITION — never on the first emission, which is
            // just "here is the current state" at launch and would fire on every start.
            if (seenFirstState && offline != wasOffline) {
                notificationEngine?.notify(
                    category = if (offline) NotificationCategory.OFFLINE else NotificationCategory.ONLINE,
                    personalization = personalizationFacts()
                )
            }
            seenFirstState = true
            wasOffline = offline
        }
    }

    // Real OS permission state → profile, so the Settings toggles reflect the actual
    // grant (not an optimistic "on"). Checked on start and refreshed on every result.
    fun notificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }

    // Re-read on every foreground pass, not just at start: the customer may have
    // revoked the grant in Android settings (or granted it there after the OS
    // stopped showing its dialog) while the app was in the background.
    var permissionEpoch by remember { mutableStateOf(0) }
    val notificationsAllowed = remember(permissionEpoch) { notificationsGranted() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                permissionEpoch++
            } else if (event == Lifecycle.Event.ON_RESUME) {
                (context as? ComponentActivity)?.let { PlayUpdateManager.checkAndResume(it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(notificationsAllowed) {
        repository.setNotificationsEnabled(notificationsAllowed)
    }

    // --- On-device personalization ------------------------------------------------
    // Load the cached profile first so Home can rank immediately on a cold start
    // (CLAUDE.md §11), then recompute from real Activity whenever it changes and
    // save the result back. Everything here stays on the device: nothing is uploaded,
    // and clearing app data erases it (CLAUDE.md §10).
    LaunchedEffect(app) {
        val store = app?.personalizationStore ?: return@LaunchedEffect
        runCatching { store.load() }.getOrNull()?.let { behaviourProfile.value = it }

        repository.purchases.collect { purchases ->
            val rebuilt = PersonalizationEngine.buildProfile(
                purchases = purchases,
                favouriteIds = repository.offers.value.filter { it.isFavourite }.map { it.id }.toSet(),
                recentRecipients = repository.recentRecipients.value,
                nowMillis = System.currentTimeMillis(),
                offers = repository.offers.value
            )
            behaviourProfile.value = rebuilt
            runCatching { store.save(rebuilt) }
        }
    }

    // --- Force sync ---------------------------------------------------------------
    // A publish in the admin console (e.g. a dead Paybill replaced) must reach every
    // online customer without a store update or a reinstall. This polls only the tiny
    // manifest, and only while this screen is composed — the effect is cancelled when
    // the app leaves the foreground, so it never costs battery in the background.
    LaunchedEffect(app) {
        val orchestrator = app?.syncOrchestrator ?: return@LaunchedEffect
        ForceSyncWatcher(
            orchestrator = orchestrator,
            manifestSource = app.manifestSource,
            metadataStore = app.syncMetadataStore
        ).watch()
    }

    // One incremental sync at start. The planner throttles it, so returning to the app
    // repeatedly does not hammer the backend.
    LaunchedEffect(app) {
        app?.syncOrchestrator?.sync(SyncTrigger.APP_START)
    }

    // Tell the seller who this customer is — once per install. Re-attempted on every
    // start and whenever onboarding completes, because the first attempt is exactly
    // the one most likely to fail (a customer finishing setup on a weak connection).
    // The repository returns immediately once it has succeeded.
    LaunchedEffect(app) {
        repository.userProfile.collect { profile ->
            if (profile.isOnboardingCompleted) repository.registerCustomer()
        }
    }

    // Runtime permission launcher (Android 13+ POST_NOTIFICATIONS). Settings shows
    // the in-app rationale first, then invokes this; the granted result is written
    // back so the toggle can never show "on" while the OS denied it.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        repository.setNotificationsEnabled(granted)
        permissionEpoch++
    }

    val requestNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Below API 33 the permission is granted at install time; nothing to ask.
    }

    // Reduced-motion proxy: honour the device animator-duration-scale = 0 setting
    // (design.md §11 "Respect Android reduced-motion settings").
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    // --- Update awareness (DEBUG BUILDS ONLY) ------------------------------------
    // One check at start drives all three surfaces: the force-update gate, the system
    // notification and the Home "Update available" billboard.
    //
    // BuildConfig.GITHUB_UPDATER_ENABLED is true only for debug builds. Shipped builds
    // are distributed by Google Play, which updates them natively, so they never fetch
    // update.json and never offer to install an APK themselves. With the flag false
    // `pendingUpdate` stays null, which leaves every update surface (gate, billboard,
    // notification) inert without any further branching below.
    var pendingUpdate by remember { mutableStateOf<UpdateResult.Available?>(null) }
    LaunchedEffect(Unit) {
        if (!BuildConfig.GITHUB_UPDATER_ENABLED) return@LaunchedEffect
        val result = UpdateChecker.check()
        if (result is UpdateResult.Available) {
            pendingUpdate = result
            // Reuse the existing notification infrastructure (UPDATES channel). No-ops
            // silently when POST_NOTIFICATIONS is not granted; a tap deep-links to
            // Settings → update section (AppNotifier.postAppUpdate uses route "settings").
            appNotifier.postAppUpdate(result.versionName.ifBlank { "update" })
        }
    }
    // Non-dismissible gate required when mandatory OR this build is below the
    // manifest's minSupportedVersionCode. A normal update stays a gentle prompt.
    val updateRequired = pendingUpdate?.isRequired() == true

    val catalogueViewModel: CatalogueViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                // The learned profile re-ranks Home and adds the "Buy again" /
                // "Your usual bundle" labels. An EMPTY profile (fresh install) leaves
                // ordering and labelling exactly as they were.
                CatalogueViewModel(
                    repository = repository,
                    behaviourProfileFlow = behaviourProfile.asStateFlow()
                ) as T
        }
    )

    val homeUiState by catalogueViewModel.homeUiState.collectAsState()
    val offersUiState by catalogueViewModel.offersUiState.collectAsState()

    // Surface an "Update available" billboard on Home while an update is pending
    // (Task 8.3). Prepended (not run through billboard selection) so it always
    // leads and is never filtered out — and it does not disturb synced billboards.
    val homeState = pendingUpdate?.let { update ->
        homeUiState.copy(promotions = listOf(UpdatePromotion.forUpdate(update)) + homeUiState.promotions)
    } ?: homeUiState

    val userProfile by repository.userProfile.collectAsState()
    val appTheme by repository.appTheme.collectAsState()
    val isOffline by repository.isOffline.collectAsState()
    val offers by repository.offers.collectAsState()
    val purchases by repository.purchases.collectAsState()
    val notifications by repository.notifications.collectAsState()
    val recentRecipients by repository.recentRecipients.collectAsState()
    val appConfig by repository.appConfig.collectAsState()

    // Hoisted so list position and filters survive tab switches (design.md §14.4).
    val homeListState = rememberLazyListState()
    val offersListState = rememberLazyListState()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: if (startOnboarding) "onboarding" else "home"

    // Whether the required notification permission is missing right now, and how
    // many times this session has already asked.
    val missingNotifications = !notificationsAllowed &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var permissionAsks by remember { mutableStateOf(0) }

    // Closing the app is the stated consequence of refusing a required permission.
    val activity = context as? android.app.Activity
    val exitApp: () -> Unit = { activity?.finishAndRemoveTask() }

    var activeOfferForPurchase by remember { mutableStateOf<OfferItem?>(null) }
    // An offer tapped outside its Safaricom selling window: explain the window
    // instead of opening checkout, so no one pays for a bundle that cannot be sold
    // to them yet.
    var offerOutsideWindow by remember { mutableStateOf<OfferItem?>(null) }
    var prefilledReportRef by remember { mutableStateOf<String?>(null) }
    // Notification centre is an in-app slide-up overlay, not a standalone route,
    // so the bottom navigation stays visible behind it.
    var showNotifications by remember { mutableStateOf(false) }

    // Notification-tap deep-link: navigate to a real tab, open the notification
    // centre for "notifications", ignore anything unknown, then consume it so it
    // does not re-fire on recomposition.
    val pendingDeepLink by deepLinkRoute.collectAsState()
    LaunchedEffect(pendingDeepLink) {
        val route = pendingDeepLink ?: return@LaunchedEffect
        when (route) {
            "home", "offers", "activity", "help", "settings" -> {
                navController.navigate(route) {
                    popUpTo("home") { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            "notifications" -> showNotifications = true
            else -> { /* unknown route — ignore */ }
        }
        onConsumeDeepLink()
    }

    // Hide navigation behind the non-dismissible "Update required" gate.
    val showBottomBar = !updateRequired &&
        currentRoute in listOf("home", "offers", "activity", "help", "settings")

    // Resolve details/purchase offers against the live catalogue so favourite
    // toggles inside a sheet stay reflected.
    fun liveOffer(offer: OfferItem): OfferItem = offers.find { it.id == offer.id } ?: offer

    /**
     * The single entry point into checkout. An offer whose selling window is shut
     * opens the explanation dialog instead; everything else opens the purchase
     * sheet exactly as before. Every "buy"/"select" call site goes through here so
     * the window can never be bypassed by one forgotten surface (billboard CTA
     * included).
     */
    val openOffer: (OfferItem) -> Unit = { offer ->
        if (offerAvailabilityAt(liveOffer(offer), System.currentTimeMillis()).purchasable) {
            activeOfferForPurchase = offer
        } else {
            offerOutsideWindow = offer
        }
    }

    val onUndoFavourite: (String) -> Unit = { id -> repository.setFavourite(id, true) }

    // Browse the Offers tab, optionally pre-filtered to a category. Shared by
    // announcement slides and by offer slides whose linked offer can't be resolved.
    val browseOffers: (com.example.core.model.OfferCategory?) -> Unit = { category ->
        category?.let { repository.setCategoryFilter(it) }
        navController.navigate("offers") {
            popUpTo("home") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val onPromotionAction: (Promotion) -> Unit = { promo ->
        when {
            // The synthetic "Update available" slide (Task 8.3): its CTA opens the
            // Settings update section (github) or the Play listing — handled the same
            // way the Settings deep-link is, via the pending-update state below.
            promo.id == UpdatePromotion.ID -> {
                if (pendingUpdate?.source == UpdateSource.PLAY) {
                    openPlayStoreListing(context)
                } else {
                    navController.navigate("settings") {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
            // An explicit click action published by the admin wins over the slide's
            // kind. NONE falls through to the original kind-based behaviour below, so
            // every billboard published before media support still behaves as it did.
            promo.clickActionOrNone == PromotionClickAction.EXTERNAL_LINK -> {
                // The mapper only ever emits an http(s) target, but re-check here: this
                // is the one place the app hands control to another app.
                val uri = runCatching { Uri.parse(promo.clickTarget) }.getOrNull()
                if (uri != null && (uri.scheme == "https" || uri.scheme == "http")) {
                    // No browser installed → ignore quietly. A promotion must never
                    // crash the app or raise an error dialog.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
            promo.clickActionOrNone == PromotionClickAction.INTERNAL_ROUTE -> {
                val route = promo.clickTarget
                if (route in listOf("home", "offers", "activity", "help", "settings")) {
                    navController.navigate(route) {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                } else {
                    browseOffers(null)
                }
            }
            promo.clickActionOrNone == PromotionClickAction.CATEGORY -> browseOffers(
                OfferCategory.entries.firstOrNull { it.name.equals(promo.clickTarget, ignoreCase = true) }
            )
            promo.clickActionOrNone == PromotionClickAction.OFFER -> {
                val target = offers.find { it.id == promo.clickTarget || it.id == promo.linkedOfferId }
                if (target != null) openOffer(target) else browseOffers(promo.linkedCategory)
            }
            promo.kind == PromotionKind.OFFER -> {
                val target = promo.linkedOfferId?.let { id -> offers.find { it.id == id } }
                if (target != null) {
                    openOffer(target)
                } else {
                    // Synced billboard whose linked offer isn't in this catalogue: don't
                    // dead-end the CTA — browse offers (optionally by linked category).
                    browseOffers(promo.linkedCategory)
                }
            }
            promo.kind == PromotionKind.ANNOUNCEMENT -> browseOffers(promo.linkedCategory)
            else -> showNotifications = true // PromotionKind.UPDATE (informational)
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar && activeOfferForPurchase == null) {
                SkylinkBingwaBottomNav(
                    currentRoute = currentRoute,
                    onNavigate = { dest ->
                        if (currentRoute != dest.route) {
                            navController.navigate(dest.route) {
                                // Pop back to the real Home root (not graph.startDestination,
                                // which may still be "onboarding" this session and would
                                // silently break Home navigation). Save/restore each tab's
                                // state so filters and scroll are preserved.
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        } else {
                            // Reselecting the active tab returns it to the top (design.md §12.2).
                            scope.launch {
                                when (dest.route) {
                                    "home" -> homeListState.animateScrollToItem(0)
                                    "offers" -> offersListState.animateScrollToItem(0)
                                }
                            }
                        }
                    }
                )
            }
        },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = if (startOnboarding) "onboarding" else "home"
            ) {
                composable("onboarding") {
                    OnboardingScreen(
                        onCompleteOnboarding = { name, phone ->
                            repository.updateProfile(name, phone)
                            repository.setOnboardingCompleted(true)
                            navController.navigate("home") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        },
                        // Permissions are asked here (with the reason shown first) rather
                        // than buried in Settings. The launchers live in this file; the
                        // screen only asks. Declining never blocks onboarding — it just
                        // switches off the feature that needed the grant.
                        onRequestNotificationPermission = requestNotificationPermission,
                        // Below API 33 notifications are granted at install time, so show
                        // the step as already satisfied rather than asking for nothing.
                        // Read from the live OS grant, not the stored profile: the
                        // required steps must never let someone through on a stale
                        // "enabled" flag. Below API 33 POST_NOTIFICATIONS is granted
                        // at install, so that step passes straight through.
                        notificationsGranted = notificationsAllowed ||
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
                        onOpenAppSettings = { openAppSettings(context) },
                        onExitApp = exitApp
                    )
                }

                composable("home") {
                    HomeScreen(
                        state = homeState,
                        unreadNotifCount = notifications.count { !it.isRead },
                        reducedMotion = reducedMotion,
                        listState = homeListState,
                        onCategoryClick = { cat ->
                            repository.setCategoryFilter(cat)
                            navController.navigate("offers") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOfferSelect = openOffer,
                        onOfferBuy = openOffer,
                        onFavouriteToggle = { offer -> repository.setFavourite(offer.id, !offer.isFavourite) },
                        onUndoFavourite = onUndoFavourite,
                        onPromotionAction = onPromotionAction,
                        onNotifClick = { showNotifications = true },
                        onOfflineClick = {
                            navController.navigate("offers") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }

                composable("offers") {
                    OffersScreen(
                        state = offersUiState,
                        listState = offersListState,
                        onSearchQueryChange = { repository.setSearchQuery(it) },
                        onCategorySelect = { repository.setCategoryFilter(it) },
                        onFilterStateChange = { repository.setFilterState(it) },
                        onClearFilters = { repository.clearFilters() },
                        onOfferSelect = openOffer,
                        onOfferBuy = openOffer,
                        onFavouriteToggle = { offer -> repository.setFavourite(offer.id, !offer.isFavourite) },
                        onUndoFavourite = onUndoFavourite
                    )
                }

                composable("activity") {
                    ActivityScreen(
                        purchases = purchases,
                        userPrimaryNumber = userProfile.primaryNumber,
                        onDeleteRecord = { id -> repository.deletePurchaseRecord(id) },
                        onDeleteRecords = { ids -> repository.deletePurchaseRecords(ids) },
                        onUndoDelete = { rec -> repository.undoDeletePurchaseRecord(rec) },
                        onBrowseOffers = {
                            navController.navigate("offers") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onReportProblem = { rec ->
                            prefilledReportRef = rec.mpesaCode
                            navController.navigate("help")
                        }
                    )
                }

                composable("help") {
                    HelpScreen(
                        prefilledRef = prefilledReportRef,
                        appConfig = appConfig,
                        onOpenSettings = { navController.navigate("settings") }
                    )
                }

                composable("settings") {
                    SettingsScreen(
                        profile = userProfile,
                        currentTheme = appTheme,
                        onUpdateProfile = { name, phone -> repository.updateProfile(name, phone) },
                        onThemeSelect = { theme -> repository.setAppTheme(theme) },
                        onClearLocalData = {
                            repository.clearAllLocalData()
                            navController.navigate("onboarding") {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        knownUpdate = pendingUpdate
                    )
                }
            }

            // --- Ask for a Play rating, after a purchase that actually worked ------------
    //
    // Deliberately not immediate. The seconds right after paying are the noisiest on
    // the phone: M-Pesa's own SMS lands, a caller-ID app summarises the payment,
    // Safaricom confirms the bundle. A rating card in that pile-up is dismissed
    // without being read, so this waits for the noise to pass — a few seconds, not
    // minutes, while the customer is still on the screen where the good thing
    // happened — and waits for the purchase sheet to be closed so the card is never
    // stacked on top of a modal.
    //
    // ReviewPolicy decides IF: two received purchases and at most once every 60
    // days. Google quotas the card too, but invisibly — the API never reports
    // whether it appeared — so the attempt is recorded either way rather than
    // re-asking on the assumption it did not show.
    val lastReviewPrompt by repository.lastReviewPromptMillis.collectAsState()
    val newestReceivedId = purchases
        .firstOrNull { it.status == com.example.core.model.PaymentStatus.RECEIVED }?.id

    LaunchedEffect(newestReceivedId) {
        val activity = activity ?: return@LaunchedEffect
        if (newestReceivedId == null) return@LaunchedEffect
        // Wait while checkout sheet is still open (up to 15 seconds)
        var waitCount = 0
        while (activeOfferForPurchase != null && waitCount < 30) {
            kotlinx.coroutines.delay(500)
            waitCount++
        }
        if (activeOfferForPurchase != null) return@LaunchedEffect
        if (!ReviewPolicy.shouldPrompt(purchases, lastReviewPrompt, System.currentTimeMillis())) {
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(ReviewPolicy.SETTLE_DELAY_MILLIS)
        // Re-check after the wait: the customer may have started another purchase.
        if (activeOfferForPurchase != null) return@LaunchedEffect
        repository.markReviewPrompted(System.currentTimeMillis())
        AppReviewLauncher.launch(activity)
    }

    // Purchase flow bottom sheet overlay (Phase 4 owns the state machine).
            activeOfferForPurchase?.let { snapshot ->
                val offer = liveOffer(snapshot)
                PurchaseBottomSheet(
                    offer = offer,
                    userPrimaryNumber = userProfile.primaryNumber,
                    recentRecipients = recentRecipients,
                    preferredPayerNumber = behaviourProfile.value.suggestedPayerNumber(),
                    isOffline = isOffline,
                    // Once-per-day-per-number awareness, recomputed from Activity as
                    // the customer types. The Nairobi day boundary lives in
                    // repeatPurchaseBlockMessage, so a purchase at 23:58 stops
                    // blocking by itself at 00:00 — nothing is scheduled or reset.
                    recipientBlockMessage = { recipient ->
                        repeatPurchaseBlockMessage(offer, purchases, recipient, System.currentTimeMillis())
                    },
                    onExecuteStkPush = { off, rec, pay, crid, self -> repository.executeMpesaStkPush(off, rec, pay, crid, self) },
                    onExecuteOfflinePayment = { off, rec, pay, till, receipt -> repository.executeOfflinePayment(off, rec, pay, till, receipt) },
                    offlineEligibility = { off, self -> repository.offlineEligibility(off, self) },
                    offlineConfig = { repository.offlineConfig() },
                    onDismiss = {
                        repository.clearActiveOrder()
                        activeOfferForPurchase = null
                    },
                    onViewActivity = {
                        activeOfferForPurchase = null
                        navController.navigate("activity") {
                            popUpTo("home") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            // "Not on sale right now" explanation for an offer tapped outside its
            // Safaricom selling window. Purely informational — it starts nothing.
            offerOutsideWindow?.let { snapshot ->
                val offer = liveOffer(snapshot)
                OfferAvailabilityDialog(
                    offer = offer,
                    availability = offerAvailabilityAt(offer, System.currentTimeMillis()),
                    onDismiss = { offerOutsideWindow = null }
                )
            }

            // Notification-centre overlay (slide-up modal). Rendered above the
            // scaffold body so the bottom navigation remains visible/accessible.
            if (showNotifications) {
                NotificationsSheet(
                    notifications = notifications,
                    notificationsEnabled = userProfile.notificationsEnabled,
                    onMarkRead = { id -> repository.markNotificationRead(id) },
                    onMarkAllRead = { repository.markAllNotificationsRead() },
                    onDeleteNotification = { id -> repository.deleteNotification(id) },
                    onClearAll = { repository.clearAllNotifications() },
                    onEnableNotifications = { repository.updateProfile(userProfile.name, userProfile.primaryNumber) },
                    onDeepLink = { route ->
                        navController.navigate(route) {
                            popUpTo("home") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onDismiss = { showNotifications = false }
                )
            }

            // Blocking gate for the REQUIRED notification permission when it is no
            // longer granted — normally revoked in Android settings after onboarding.
            // Notifications are mandatory (owner decision), so the app does not carry
            // on without them: the only ways out are granting the permission or
            // closing the app. Never shown during onboarding, which asks for it itself.
            if (!startOnboarding && missingNotifications) {
                PermissionRequiredScreen(
                    missingNotifications = missingNotifications,
                    // Two refusals and Android stops showing its dialog; from then on
                    // only the app's settings page can grant it.
                    canAskAgain = permissionAsks < 2,
                    onAllow = {
                        permissionAsks++
                        requestNotificationPermission()
                    },
                    onOpenAppSettings = { openAppSettings(context) },
                    onExitApp = exitApp
                )
            }

            // Blocking "Update required" gate (Task 8.1). Drawn last so it overlays
            // everything; the bottom bar is already hidden (showBottomBar). The user
            // cannot proceed until they update (github install or Play), and back is
            // swallowed inside the screen.
            pendingUpdate?.let { update ->
                if (updateRequired) {
                    UpdateRequiredScreen(update = update)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small integration helpers. Top-level and pure so they stay cheap and testable.
// ---------------------------------------------------------------------------

/**
 * Whole days between [thenMillis] and now, on the Africa/Nairobi day boundary
 * (CLAUDE.md §8). Returns 0 when there is no recorded purchase, which the
 * notification templates render as "a while" rather than "0 days".
 */
private fun daysSinceNairobi(thenMillis: Long): Int {
    if (thenMillis <= 0L) return 0
    val elapsed = System.currentTimeMillis() - thenMillis
    if (elapsed <= 0L) return 0
    return (elapsed / 86_400_000L).toInt()
}
