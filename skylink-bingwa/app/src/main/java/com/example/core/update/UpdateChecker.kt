package com.example.core.update

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Where an available update is delivered from.
 *
 * - [GITHUB] the direct/sideload channel: the app downloads the signed APK named
 *   in the manifest and launches the system package installer (see
 *   [AppUpdateInstaller]).
 * - [PLAY]   the Google Play channel: update actions open the Play listing so the
 *   store performs the update natively.
 *
 * Read defensively from the manifest's optional `updateSource` string — anything
 * other than "play" (missing, blank, unknown) means [GITHUB].
 */
enum class UpdateSource {
    GITHUB,
    PLAY;

    companion object {
        fun from(raw: String?): UpdateSource =
            if (raw?.trim()?.lowercase() == "play") PLAY else GITHUB
    }
}

/**
 * In-app update check for the DIRECT (GitHub / sideload) distribution channel.
 *
 * Google Play distribution updates itself natively, so this check only matters
 * for users who installed the direct APK. It fetches a small, non-secret JSON
 * manifest ([BuildConfig.UPDATE_MANIFEST_URL], the repo's `update.json`) and
 * compares its `latestVersionCode` against this build's [BuildConfig.VERSION_CODE].
 *
 * On an available update the UI either downloads + installs the signed APK in-app
 * (github source — [AppUpdateInstaller], same permanent signing identity so the
 * OS performs an in-place update that preserves all local data) or opens the Play
 * listing (play source).
 */
sealed interface UpdateResult {
    /** A newer direct-channel build is published. */
    data class Available(
        val versionName: String,
        /** The published build's versionCode; names the downloaded APK file. */
        val versionCode: Int,
        val apkUrl: String,
        /** Lower-case hex SHA-256 of the published APK, or blank when not provided. */
        val apkSha256: String,
        val notes: String,
        val mandatory: Boolean,
        /** Builds below this versionCode must update before continuing. */
        val minSupportedVersionCode: Int,
        val source: UpdateSource,
    ) : UpdateResult {
        /**
         * True when the customer cannot keep using this build: the manifest marked
         * the update [mandatory], or this build is older than [minSupportedVersionCode].
         */
        fun isRequired(currentVersionCode: Int = BuildConfig.VERSION_CODE): Boolean =
            mandatory || currentVersionCode < minSupportedVersionCode
    }

    /** This build is the latest published direct-channel build. */
    data object UpToDate : UpdateResult

    /** The check could not complete (offline, bad response, malformed manifest). */
    data class Error(val message: String) : UpdateResult
}

object UpdateChecker {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Check the direct-channel manifest for a newer build.
     *
     * ## Why the gate is a flavour flag and NOT `!BuildConfig.DEBUG`
     * Skylink Bingwa ships two release flavours from one codebase:
     *
     * - **direct** — the sideloaded GitHub channel. There is no store behind it,
     *   so this in-app updater is the user's ONLY upgrade path. It MUST stay on
     *   in release builds of this flavour.
     * - **play** — Google Play updates the app natively. A second in-app update
     *   channel there is redundant and violates Play policy, so it is compiled
     *   out.
     *
     * Gating on `!BuildConfig.DEBUG` would therefore silently strand every
     * sideloaded customer on the version they first installed. Please do not
     * "fix" this back to a debug check. The GitHub implementation below is kept
     * intact (never deleted) so the channel can be re-enabled by flipping the
     * flavour's `GITHUB_UPDATER_ENABLED` field.
     *
     * When [updaterEnabled] is false this performs NO network call and returns
     * [UpdateResult.UpToDate] immediately and silently — the same "nothing to do"
     * result every caller already handles.
     *
     * @param updaterEnabled injectable for tests; defaults to the build's
     *   `GITHUB_UPDATER_ENABLED` flavour flag, which is fixed per test variant.
     */
    suspend fun check(
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL,
        updaterEnabled: Boolean = BuildConfig.GITHUB_UPDATER_ENABLED,
    ): UpdateResult {
        // Returned before any dispatcher hop: no thread, no socket, no logging.
        if (!updaterEnabled) return UpdateResult.UpToDate
        return checkRemote(currentVersionCode, manifestUrl)
    }

    private suspend fun checkRemote(
        currentVersionCode: Int,
        manifestUrl: String,
    ): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(manifestUrl)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateResult.Error(
                        "Could not check for updates right now. Please try again later.",
                    )
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val latestCode = json.optInt("latestVersionCode", 0)
                if (latestCode > currentVersionCode) {
                    UpdateResult.Available(
                        versionName = json.optString("latestVersionName", ""),
                        versionCode = latestCode,
                        apkUrl = json.optString("apkUrl", ""),
                        apkSha256 = json.optString("apkSha256", ""),
                        notes = json.optString("releaseNotes", ""),
                        mandatory = json.optBoolean("mandatory", false),
                        minSupportedVersionCode = json.optInt("minSupportedVersionCode", 0),
                        source = UpdateSource.from(json.optString("updateSource", "github")),
                    )
                } else {
                    UpdateResult.UpToDate
                }
            }
        } catch (e: Exception) {
            UpdateResult.Error(
                "Could not check for updates. Check your connection and try again.",
            )
        }
    }
}
