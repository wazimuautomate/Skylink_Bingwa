package com.example.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.ui.PrimaryButton
import kotlinx.coroutines.launch

/**
 * The Update call-to-action, shared by the Settings "About" section and the
 * blocking "Update required" screen so both drive the exact same install flow.
 *
 * - github source: downloads the signed APK (with progress + SHA-256 check) and
 *   launches the system installer. On Android 8+ without the "install unknown
 *   apps" grant it first routes the user to that settings screen, then they tap
 *   Update again to continue.
 * - play source: opens the Play Store listing (store performs the update).
 *
 * All state is local and honest — a failed download/verify/install surfaces a
 * clear message and leaves the button ready to retry. Nothing here clears app
 * storage; an in-place update preserves profile, favourites and Activity.
 */
@Composable
fun UpdateInstallControls(
    update: UpdateResult.Available,
    modifier: Modifier = Modifier,
    githubLabel: String = "Download & install update",
    testTag: String = "update_install_button",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { AppUpdateInstaller(context) }

    var downloading by remember { mutableStateOf(false) }
    var percent by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val label = when {
        update.source == UpdateSource.PLAY -> "Update on Google Play"
        downloading -> percent?.let { "Downloading… $it%" } ?: "Downloading…"
        else -> githubLabel
    }

    // Plain-return local handler (no labeled-lambda returns) for the Update tap.
    fun onUpdateClick() {
        if (downloading) return
        status = null
        if (update.source == UpdateSource.PLAY) {
            openPlayStoreListing(context)
            return
        }
        // github: ensure we may install first (Android 8+ "install unknown apps").
        if (!installer.canInstallPackages()) {
            installer.openInstallPermissionSettings()
            status = "Allow installing apps from Skylink Bingwa, then tap Update again."
            return
        }
        downloading = true
        percent = null
        status = "Starting download…"
        scope.launch {
            val result = installer.download(
                apkUrl = update.apkUrl,
                versionCode = update.versionCode,
                expectedSha256 = update.apkSha256,
                onProgress = { percent = it },
            )
            downloading = false
            when (result) {
                is AppUpdateInstaller.DownloadResult.Success -> {
                    status = "Opening the installer…"
                    installer.installApk(result.apk)
                }
                is AppUpdateInstaller.DownloadResult.Error -> {
                    status = result.message
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        PrimaryButton(
            text = label,
            isLoading = false,
            enabled = !downloading,
            testTag = testTag,
            onClick = { onUpdateClick() },
        )

        if (downloading) {
            Spacer(Modifier.height(10.dp))
            // Indeterminate bar for motion; the exact percent shows in the button label.
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("update_progress"),
            )
        }

        status?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("update_status"),
            )
        }
    }
}

/**
 * Open the Google Play listing for this app. Tries the Play app first
 * (`market://`) and falls back to the browser. The `.debug` suffix is stripped
 * so a dev build still resolves the production listing.
 */
fun openPlayStoreListing(context: Context) {
    val pkg = context.packageName.removeSuffix(".debug")
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }
        .recoverCatching { context.startActivity(web) }
}
