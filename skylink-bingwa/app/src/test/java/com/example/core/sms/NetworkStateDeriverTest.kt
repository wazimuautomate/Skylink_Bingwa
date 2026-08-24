package com.example.core.sms

import com.example.core.notifications.ConnectionState
import com.example.core.notifications.NetworkFacts
import com.example.core.notifications.NetworkStateDeriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure part of instant internet detection: capabilities in, state out.
 *
 * (Lives in this source directory purely for file-ownership reasons during
 * parallel development; it targets `com.example.core.notifications`.)
 *
 * The rule under test is the important one: **a network existing is not the same
 * as internet working.** Connected-but-not-validated Wi-Fi — a captive portal, a
 * hotel login that expired, a hotspot with no upstream — must read as OFFLINE, so
 * the app shows cached content and offline payment instructions instead of
 * failing a live request.
 */
class NetworkStateDeriverTest {

    private fun wifi(validated: Boolean) = NetworkFacts(
        hasInternetCapability = true,
        isValidated = validated,
        isWifi = true,
        isCellular = false
    )

    private fun cellular(validated: Boolean) = NetworkFacts(
        hasInternetCapability = true,
        isValidated = validated,
        isWifi = false,
        isCellular = true
    )

    @Test
    fun noNetworks_isNone() {
        val status = NetworkStateDeriver.derive(emptyList())
        assertEquals(ConnectionState.NONE, status.state)
        assertFalse(status.hasTransport)
        assertFalse(status.isValidated)
    }

    @Test
    fun validatedWifi_isWifi() {
        val status = NetworkStateDeriver.derive(listOf(wifi(validated = true)))
        assertEquals(ConnectionState.WIFI, status.state)
        assertTrue(status.hasTransport)
        assertTrue(status.isValidated)
    }

    @Test
    fun validatedCellular_isCellular() {
        assertEquals(
            ConnectionState.CELLULAR,
            NetworkStateDeriver.derive(listOf(cellular(validated = true))).state
        )
    }

    @Test
    fun validatedWifiAndCellular_isBoth() {
        val status = NetworkStateDeriver.derive(
            listOf(wifi(validated = true), cellular(validated = true))
        )
        assertEquals(ConnectionState.BOTH, status.state)
    }

    @Test
    fun wifiWithoutValidation_isOffline_butReportsTransport() {
        // The captive-portal case: full signal bars, zero internet.
        val status = NetworkStateDeriver.derive(listOf(wifi(validated = false)))
        assertEquals(ConnectionState.NONE, status.state)
        assertTrue(status.hasTransport)
        assertFalse(status.isValidated)
    }

    @Test
    fun unvalidatedWifiAlongsideValidatedCellular_isCellularOnly() {
        val status = NetworkStateDeriver.derive(
            listOf(wifi(validated = false), cellular(validated = true))
        )
        assertEquals(ConnectionState.CELLULAR, status.state)
        assertTrue(status.isValidated)
    }

    @Test
    fun networkWithoutInternetCapability_isIgnoredEntirely() {
        val status = NetworkStateDeriver.derive(
            listOf(
                NetworkFacts(
                    hasInternetCapability = false,
                    isValidated = true,
                    isWifi = true,
                    isCellular = false
                )
            )
        )
        assertEquals(ConnectionState.NONE, status.state)
        assertFalse(status.hasTransport)
    }

    @Test
    fun validatedOtherTransport_reportsAsWifi() {
        // Ethernet / VPN / USB tether: unmetered-ish, and we model only four states.
        val status = NetworkStateDeriver.derive(
            listOf(
                NetworkFacts(
                    hasInternetCapability = true,
                    isValidated = true,
                    isWifi = false,
                    isCellular = false
                )
            )
        )
        assertEquals(ConnectionState.WIFI, status.state)
    }

    @Test
    fun defaultNetworkFacts_areAllFalse() {
        val status = NetworkStateDeriver.derive(listOf(NetworkFacts()))
        assertEquals(ConnectionState.NONE, status.state)
        assertFalse(status.hasTransport)
    }
}
