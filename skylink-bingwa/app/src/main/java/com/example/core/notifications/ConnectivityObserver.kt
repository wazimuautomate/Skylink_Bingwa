package com.example.core.notifications

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The device's internet state, collapsed to the four cases the app cares about. */
enum class ConnectionState { NONE, WIFI, CELLULAR, BOTH }

/**
 * A richer view of connectivity than [ConnectionState] alone.
 *
 * @param state        the classified state. It is [ConnectionState.NONE] whenever
 *                     no network is actually carrying internet, even if a Wi-Fi
 *                     icon is showing.
 * @param hasTransport true when SOME internet-capable network exists. Combined
 *                     with `isValidated == false` this is the classic
 *                     "connected to Wi-Fi, but the Wi-Fi has no internet" case
 *                     (captive portal, half-associated hotspot, expired hotel
 *                     login) — the exact situation that must NOT read as online.
 * @param isValidated  the system has actually probed the network and confirmed
 *                     it reaches the internet.
 */
data class NetworkStatus(
    val state: ConnectionState,
    val hasTransport: Boolean,
    val isValidated: Boolean
)

/**
 * The facts about ONE network, lifted out of the Android framework so the
 * classification rules can be unit-tested without a device.
 */
data class NetworkFacts(
    val hasInternetCapability: Boolean = false,
    val isValidated: Boolean = false,
    val isWifi: Boolean = false,
    val isCellular: Boolean = false
)

/**
 * PURE classification: a list of [NetworkFacts] in, one [NetworkStatus] out.
 *
 * Extracted from [ConnectivityObserver] on purpose — this is where all the
 * judgement lives, and it is the part worth testing.
 */
object NetworkStateDeriver {

    /**
     * Rules:
     * - only networks with INTERNET capability count at all;
     * - a network is USABLE only when it is also VALIDATED. "A network exists" is
     *   not "internet works": a captive portal answers DHCP and looks perfectly
     *   connected while nothing can actually reach our backend;
     * - transports classify the state, they never decide whether we are online;
     * - an internet transport that is neither Wi-Fi nor cellular (Ethernet, VPN,
     *   USB tether) is reported as [ConnectionState.WIFI] because we model only
     *   four states and it behaves like the unmetered case.
     */
    fun derive(networks: List<NetworkFacts>): NetworkStatus {
        var wifi = false
        var cellular = false
        var other = false
        var anyTransport = false
        var anyValidated = false

        for (network in networks) {
            if (!network.hasInternetCapability) continue
            anyTransport = true
            if (!network.isValidated) continue
            anyValidated = true
            when {
                network.isWifi -> wifi = true
                network.isCellular -> cellular = true
                else -> other = true
            }
        }

        val state = when {
            wifi && cellular -> ConnectionState.BOTH
            wifi -> ConnectionState.WIFI
            cellular -> ConnectionState.CELLULAR
            other -> ConnectionState.WIFI
            else -> ConnectionState.NONE
        }
        return NetworkStatus(
            state = state,
            hasTransport = anyTransport,
            isValidated = anyValidated
        )
    }
}

/**
 * Observes connectivity and reports it IMMEDIATELY — no app restart, no pull to
 * refresh, no manual action.
 *
 * How it stays instant:
 * - [ConnectivityManager.registerDefaultNetworkCallback] (API 24+) catches the
 *   route the app actually uses flipping away;
 * - a second [ConnectivityManager.registerNetworkCallback] on an INTERNET request
 *   catches the *other* transports appearing/disappearing (Wi-Fi arriving while
 *   on mobile data, mobile data dying while on Wi-Fi), which the default callback
 *   alone can miss;
 * - `onAvailable`, `onLost`, `onUnavailable`, `onCapabilitiesChanged` and
 *   `onLinkPropertiesChanged` all re-read the full picture, so a change in
 *   VALIDATED (Wi-Fi that stops reaching the internet without disconnecting) is
 *   noticed too;
 * - the current state is emitted at subscription time, so a collector never waits
 *   for the first change.
 *
 * ASYMMETRIC DEBOUNCE (see [observeStatus] vs [observe]):
 * - going OFFLINE is emitted IMMEDIATELY. Telling the customer the truth late is
 *   the bug being fixed; there is no such thing as a false "you are offline" that
 *   costs them anything — the app just shows cached content and offline payment
 *   instructions;
 * - coming back ONLINE waits [ONLINE_DEBOUNCE_MILLIS]. Reconnection is noisy
 *   (associate → DHCP → validate can produce several callbacks in under a
 *   second), and announcing "online" a beat early means a payment retry fires
 *   into a half-open network. A short settle, plus requiring a VALIDATED
 *   capability, avoids the captive-portal false positive.
 *
 * Requires only ACCESS_NETWORK_STATE (no runtime prompt). Construct manually with
 * any [Context] — no Hilt. Cheap enough to collect for the whole app lifetime
 * (CLAUDE.md §11): two system callbacks, no polling, no timers while idle.
 */
class ConnectivityObserver(context: Context) {

    private val connectivityManager: ConnectivityManager? =
        try {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
        } catch (e: Exception) {
            null
        }

    /**
     * Raw status stream: emits at subscription and on every network change,
     * de-duplicated. NOT debounced — use this when you want the truth the instant
     * the system reports it.
     */
    fun observeStatus(): Flow<NetworkStatus> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            // Emulator / stripped image / test double: report offline honestly
            // rather than crashing or pretending to be connected.
            trySend(NetworkStatus(ConnectionState.NONE, hasTransport = false, isValidated = false))
            awaitClose { }
            return@callbackFlow
        }

        // Both callbacks answer the same question — "what does the whole picture
        // look like now?" — so they share one implementation.
        val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentStatus())
            }

            override fun onLost(network: Network) {
                trySend(currentStatus())
            }

            override fun onUnavailable() {
                trySend(currentStatus())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(currentStatus())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(currentStatus())
            }
        }
        val internetCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentStatus())
            }

            override fun onLost(network: Network) {
                trySend(currentStatus())
            }

            override fun onUnavailable() {
                trySend(currentStatus())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(currentStatus())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(currentStatus())
            }
        }

        // Emit the state at subscription time so collectors never wait for a change.
        trySend(currentStatus())

        var defaultRegistered = false
        var requestRegistered = false
        try {
            manager.registerDefaultNetworkCallback(defaultCallback)
            defaultRegistered = true
        } catch (e: Exception) {
            // A denied/absent ACCESS_NETWORK_STATE or a vendor quirk must not crash
            // the app; the other callback (or the initial emission) still applies.
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            manager.registerNetworkCallback(request, internetCallback)
            requestRegistered = true
        } catch (e: Exception) {
            // Same reasoning as above.
        }

        awaitClose {
            if (defaultRegistered) {
                try {
                    manager.unregisterNetworkCallback(defaultCallback)
                } catch (e: Exception) {
                    // Already unregistered — Android throws instead of no-op'ing.
                }
            }
            if (requestRegistered) {
                try {
                    manager.unregisterNetworkCallback(internetCallback)
                } catch (e: Exception) {
                    // As above.
                }
            }
        }
    }.distinctUntilChanged()

    /**
     * The app-facing stream. Same source as [observeStatus] with the asymmetric
     * debounce applied and collapsed to [ConnectionState], because that is what
     * MainActivity and the repository already consume.
     */
    fun observe(): Flow<ConnectionState> = observeStatus()
        .asymmetricDebounce()
        .map { it.state }
        .distinctUntilChanged()

    /**
     * OFFLINE passes straight through; ONLINE waits [ONLINE_DEBOUNCE_MILLIS] and
     * is cancelled if anything changes in the meantime, so only a state that has
     * actually settled is announced as "back online".
     */
    private fun Flow<NetworkStatus>.asymmetricDebounce(): Flow<NetworkStatus> {
        val upstream = this
        return channelFlow {
            var pendingOnline: Job? = null
            upstream.collect { status ->
                // Any new fact invalidates a not-yet-announced "online".
                pendingOnline?.cancel()
                pendingOnline = null
                if (status.state == ConnectionState.NONE) {
                    trySend(status)
                } else {
                    pendingOnline = launch {
                        delay(ONLINE_DEBOUNCE_MILLIS)
                        trySend(status)
                    }
                }
            }
        }
    }

    /** A one-shot read of the current [NetworkStatus]. Never throws. */
    @Suppress("DEPRECATION") // allNetworks: still the accurate way to see BOTH on minSdk 24.
    fun currentStatus(): NetworkStatus {
        val manager = connectivityManager
            ?: return NetworkStatus(ConnectionState.NONE, hasTransport = false, isValidated = false)
        val facts: List<NetworkFacts> = try {
            manager.allNetworks.map { network ->
                val caps = manager.getNetworkCapabilities(network)
                if (caps == null) {
                    NetworkFacts()
                } else {
                    NetworkFacts(
                        hasInternetCapability =
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        isValidated =
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    )
                }
            }
        } catch (e: Exception) {
            emptyList<NetworkFacts>()
        }
        return NetworkStateDeriver.derive(facts)
    }

    /** A one-shot read of the current state. */
    fun current(): ConnectionState = currentStatus().state

    /**
     * OPTIONAL confirmation probe: one tiny HEAD request to a generic,
     * non-secret connectivity endpoint.
     *
     * Nothing in this class DEPENDS on it — the VALIDATED capability is the
     * primary signal and every state emission happens without waiting for any
     * network call. Use this only when a caller wants extra confidence before an
     * expensive retry. It sends no app data, no phone number, no order and no
     * business URL (CLAUDE.md §10), and it can never block a state emission
     * because it is not part of the flow at all.
     */
    suspend fun verifyInternet(timeoutMillis: Long = 3000L): Boolean = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(timeoutMillis) {
            try {
                val client = probeClient.newBuilder()
                    .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .build()
                val request = Request.Builder().url(PROBE_URL).head().build()
                client.newCall(request).execute().use { response ->
                    response.isSuccessful || response.code == PROBE_NO_CONTENT
                }
            } catch (e: Exception) {
                false
            }
        }
        result ?: false
    }

    private companion object {
        /**
         * Long enough to swallow the associate → DHCP → validate burst, short
         * enough that the customer never notices a delay coming back online.
         */
        const val ONLINE_DEBOUNCE_MILLIS = 400L

        /**
         * The standard Android connectivity-check endpoint. Generic and public:
         * it returns an empty 204 and carries no app, customer or seller data.
         */
        const val PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"

        const val PROBE_NO_CONTENT = 204

        /** Shared so the optional probe reuses one connection pool. */
        val probeClient: OkHttpClient = OkHttpClient()
    }
}
