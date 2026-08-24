package com.example.core.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Host-JVM coverage for the update-decision logic and the APK hash used to verify
 * a download. No Android framework calls here — [AppUpdateInstaller.sha256Hex] is
 * pure java.io/java.security, and [UpdateSource]/[UpdateResult.Available.isRequired]
 * are pure Kotlin.
 */
class UpdateLogicTest {

    private fun available(
        mandatory: Boolean = false,
        minSupported: Int = 0,
        source: UpdateSource = UpdateSource.GITHUB,
    ) = UpdateResult.Available(
        versionName = "1.0.2",
        versionCode = 3,
        apkUrl = "https://example.com/app.apk",
        apkSha256 = "",
        notes = "notes",
        mandatory = mandatory,
        minSupportedVersionCode = minSupported,
        source = source,
    )

    // --- UpdateSource.from --------------------------------------------------

    @Test
    fun `updateSource defaults to github and only play means play`() {
        assertEquals(UpdateSource.PLAY, UpdateSource.from("play"))
        assertEquals(UpdateSource.PLAY, UpdateSource.from("PLAY"))
        assertEquals(UpdateSource.PLAY, UpdateSource.from("  Play  "))
        assertEquals(UpdateSource.GITHUB, UpdateSource.from("github"))
        assertEquals(UpdateSource.GITHUB, UpdateSource.from(null))
        assertEquals(UpdateSource.GITHUB, UpdateSource.from(""))
        assertEquals(UpdateSource.GITHUB, UpdateSource.from("something-else"))
    }

    // --- isRequired ---------------------------------------------------------

    @Test
    fun `isRequired true when mandatory regardless of version`() {
        assertTrue(available(mandatory = true).isRequired(currentVersionCode = 999))
    }

    @Test
    fun `isRequired true when current build is below minSupported`() {
        assertTrue(available(minSupported = 5).isRequired(currentVersionCode = 4))
    }

    @Test
    fun `isRequired false for a normal optional update`() {
        assertFalse(available(mandatory = false, minSupported = 2).isRequired(currentVersionCode = 2))
        assertFalse(available(mandatory = false, minSupported = 2).isRequired(currentVersionCode = 10))
    }

    // --- updater gating (Feature 7) -----------------------------------------

    /**
     * With the GitHub updater compiled out (the `play` release flavour) the check
     * must be a cheap, silent no-op: no network call at all, and the ordinary
     * "nothing to do" result every caller already handles. The flag is injected
     * because `BuildConfig.GITHUB_UPDATER_ENABLED` is fixed for the test variant.
     */
    @Test
    fun `a disabled updater reports no update and never calls the network`() = runTest {
        val result = UpdateChecker.check(
            currentVersionCode = 1,
            // Would be a certain failure if it were ever fetched.
            manifestUrl = "https://127.0.0.1:9/never-requested.json",
            updaterEnabled = false,
        )
        assertEquals(UpdateResult.UpToDate, result)
    }

    /**
     * The GitHub implementation is only gated, never deleted: with the flag on,
     * the check really runs (here it fails on an unusable url, which proves the
     * network path was entered rather than short-circuited).
     */
    @Test
    fun `an enabled updater still runs the github check`() = runTest {
        val result = UpdateChecker.check(
            currentVersionCode = 1,
            manifestUrl = "not-a-usable-url",
            updaterEnabled = true,
        )
        assertTrue(result is UpdateResult.Error)
    }

    // --- sha256Hex ----------------------------------------------------------

    @Test
    fun `sha256Hex matches the known vector for abc`() {
        val file = File.createTempFile("sha", ".bin")
        try {
            file.writeBytes("abc".toByteArray(Charsets.US_ASCII))
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AppUpdateInstaller.sha256Hex(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `sha256Hex is 64 lower-case hex chars`() {
        val file = File.createTempFile("sha", ".bin")
        try {
            file.writeBytes(ByteArray(4096) { it.toByte() })
            val hex = AppUpdateInstaller.sha256Hex(file)
            assertEquals(64, hex.length)
            assertTrue(hex.all { it in "0123456789abcdef" })
        } finally {
            file.delete()
        }
    }
}
