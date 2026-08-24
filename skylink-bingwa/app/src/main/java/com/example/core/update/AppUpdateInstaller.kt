package com.example.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads a published, signed Skylink Bingwa APK and hands it to the system package
 * installer for an in-place update — no browser, no manual "find the file" step.
 *
 * Why this is safe for the customer's data (CLAUDE.md §2):
 * a direct update keeps the SAME [android.content.pm.PackageInfo.packageName]
 * (applicationId `com.bingwasokoni`) + the SAME permanent signing key + a higher
 * versionCode, so Android performs an *in-place* update. DataStore, SharedPrefs
 * and app files (profile, favourites, Activity) all survive and onboarding does
 * NOT reappear. Nothing in this class touches app storage; it only writes the
 * downloaded APK into app-private external files and starts the installer intent.
 *
 * Construct with any [Context] (application context is fine — the launched
 * intents carry `FLAG_ACTIVITY_NEW_TASK`). No Hilt.
 */
class AppUpdateInstaller(context: Context) {

    private val context = context.applicationContext

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /** Outcome of a download (+ optional verification). */
    sealed interface DownloadResult {
        data class Success(val apk: File) : DownloadResult
        data class Error(val message: String) : DownloadResult
    }

    /**
     * Whether this app may request installing packages. Always true below Android 8;
     * on 8+ it reflects the per-app "install unknown apps" grant.
     */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * Send the user to the system "install unknown apps" screen for THIS package so
     * they can allow installs, then return and continue. No-op below Android 8.
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Download the APK at [apkUrl] into app-private external files
     * (`.../files/updates/skylinkbingwa-<versionCode>.apk`), reporting progress as a
     * 0..100 percentage (or null when the server sends no content length).
     *
     * When [expectedSha256] is non-blank the finished file's SHA-256 is verified
     * against it; a mismatch deletes the file and returns [DownloadResult.Error]
     * (never install an APK we can't vouch for). Runs on the IO dispatcher.
     */
    suspend fun download(
        apkUrl: String,
        versionCode: Int,
        expectedSha256: String?,
        onProgress: (Int?) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (apkUrl.isBlank()) {
            return@withContext DownloadResult.Error("No download link is available for this update.")
        }
        val outFile = File(updatesDir(), "skylinkbingwa-$versionCode.apk")
        try {
            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DownloadResult.Error("Download failed (HTTP ${response.code}).")
                }
                val body = response.body
                    ?: return@withContext DownloadResult.Error("The update download was empty.")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(if (total > 0) ((downloaded * 100) / total).toInt() else null)
                        }
                        output.flush()
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { outFile.delete() }
            return@withContext DownloadResult.Error(
                "Could not download the update. Check your connection and try again.",
            )
        }

        if (!expectedSha256.isNullOrBlank()) {
            val actual = runCatching { sha256Hex(outFile) }.getOrNull()
            if (actual == null || !actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                runCatching { outFile.delete() }
                return@withContext DownloadResult.Error(
                    "Update verification failed, so the download was discarded. Please try again.",
                )
            }
        }
        DownloadResult.Success(outFile)
    }

    /**
     * Launch the system package installer for [apk] via a [FileProvider] content
     * URI. The OS then shows its standard "update this app?" prompt; the caller's
     * process may be replaced when the update is applied.
     */
    fun installApk(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun updatesDir(): File {
        // App-private external files when available (bigger, less pressure on
        // internal storage), else internal files. Both roots are declared in
        // res/xml/file_paths.xml so FileProvider can grant either one.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "updates").apply { mkdirs() }
    }

    companion object {
        /** Lower-case hex SHA-256 of a file's bytes (streamed, so large APKs are fine). */
        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
