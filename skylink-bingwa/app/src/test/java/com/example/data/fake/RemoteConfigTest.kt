package com.example.data.fake

import com.example.core.notifications.ConnectionState
import com.example.data.config.AppConfig
import com.example.data.config.RemoteConfigSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigTest {

    private class FakeConfigSource(
        var cachedValue: AppConfig = AppConfig.DEFAULT,
        private val remote: AppConfig? = null
    ) : RemoteConfigSource {
        override fun cached(): AppConfig = cachedValue
        override suspend fun fetch(): AppConfig? = remote
    }

    @Test
    fun appConfig_seedsFromCache() {
        val cached = AppConfig("111", "222", "0700000000", "254700000000")
        val repo = FakeBingwaRepositoryImpl(configSource = FakeConfigSource(cachedValue = cached))
        assertEquals(cached, repo.appConfig.value)
    }

    @Test
    fun offlineConfig_usesSyncedTillAndPaybill() {
        val cached = AppConfig(tillNumber = "9999", paybillNumber = "8888", supportNumber = "0700", supportWhatsapp = "254700")
        val repo = FakeBingwaRepositoryImpl(configSource = FakeConfigSource(cachedValue = cached))
        val cfg = repo.offlineConfig()
        assertEquals("9999", cfg?.tillNumber)
        assertEquals("8888", cfg?.paybillNumber)
    }

    @Test
    fun syncRemoteConfig_updatesAppConfig() = runTest {
        val remote = AppConfig("R-TILL", "R-PAYBILL", "0711111111", "254711111111")
        val repo = FakeBingwaRepositoryImpl(configSource = FakeConfigSource(remote = remote))
        repo.syncRemoteConfig()
        assertEquals(remote, repo.appConfig.value)
        assertEquals("R-TILL", repo.offlineConfig()?.tillNumber)
    }

    /**
     * An install that has never reached the server still has the seller numbers the
     * APK was built with ([AppConfig.DEFAULT], from the SEED_* build fields), so the
     * Support page and the offline instructions are usable from the very first
     * launch. This is the fix for testers seeing a blank Till/Paybill/support number
     * on a weak connection.
     *
     * The one case that stays unusable is a build with no seed values configured at
     * all: rendering offline instructions with an empty number to copy is worse than
     * asking the customer to connect and refresh (CLAUDE.md §7 — ambiguous offline
     * configuration disables payment).
     */
    @Test
    fun noConfigSource_stillYieldsTheBundledSellerNumbers() {
        val repo = FakeBingwaRepositoryImpl()
        assertEquals(AppConfig.DEFAULT, repo.appConfig.value)

        if (AppConfig.DEFAULT.tillNumber.isBlank() && AppConfig.DEFAULT.paybillNumber.isBlank() &&
            AppConfig.DEFAULT.supportNumber.isBlank() && AppConfig.DEFAULT.supportWhatsapp.isBlank()
        ) {
            // Unconfigured build: no numbers to show, so no offline instructions.
            assertNull(repo.offlineConfig())
        } else {
            val cfg = repo.offlineConfig()
            assertEquals(AppConfig.DEFAULT.tillNumber, cfg?.tillNumber)
            assertEquals(AppConfig.DEFAULT.paybillNumber, cfg?.paybillNumber)
        }
    }

    /** A single synced number is enough to offer instructions; the sheet then guards the route. */
    @Test
    fun offlineConfig_isOffered_whenOnlyOneNumberIsSynced() {
        val tillOnly = AppConfig(tillNumber = "9999", paybillNumber = "", supportNumber = "", supportWhatsapp = "")
        val repo = FakeBingwaRepositoryImpl(configSource = FakeConfigSource(cachedValue = tillOnly))
        val cfg = repo.offlineConfig()
        assertEquals("9999", cfg?.tillNumber)
        assertEquals("", cfg?.paybillNumber)
    }

    @Test
    fun connectivity_drivesOfflineFlag() {
        val repo = FakeBingwaRepositoryImpl()
        repo.setConnectionState(ConnectionState.NONE)
        assertTrue(repo.isOffline.value)
        repo.setConnectionState(ConnectionState.WIFI)
        assertFalse(repo.isOffline.value)
        repo.setConnectionState(ConnectionState.CELLULAR)
        assertFalse(repo.isOffline.value)
        repo.setConnectionState(ConnectionState.NONE)
        assertTrue(repo.isOffline.value)
    }
}
