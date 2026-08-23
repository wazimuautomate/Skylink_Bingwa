package com.example.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FieldButtonShape

/**
 * The blocking screen shown when a permission My Bingwa requires has been taken
 * away after onboarding — normally because the customer revoked it in Android's
 * own settings.
 *
 * Notifications are required to use the app (owner decision, enforced in onboarding
 * too), so this is deliberately not dismissible: the only ways out are granting the
 * permission or closing the app. It re-uses the shape of the "Update required" gate
 * so the two blocking states feel like one thing rather than two different apps.
 */
@Composable
fun PermissionRequiredScreen(
    missingNotifications: Boolean,
    canAskAgain: Boolean,
    onAllow: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onExitApp: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("permission_required_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "A permission is switched off",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "My Bingwa needs this to let you know about your payments. Turn it " +
                    "back on to carry on using the app.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            Surface(
                shape = FieldButtonShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), FieldButtonShape)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (missingNotifications) {
                        MissingRow(
                            icon = Icons.Outlined.NotificationsActive,
                            title = "Notifications",
                            body = "Payment and delivery updates for what you buy."
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            PrimaryButton(
                text = if (canAskAgain) "Allow permission" else "Open settings and allow",
                onClick = if (canAskAgain) onAllow else onOpenAppSettings,
                testTag = "permission_required_allow"
            )

            Spacer(Modifier.height(10.dp))

            SecondaryButton(
                text = "Close My Bingwa",
                onClick = onExitApp,
                testTag = "permission_required_exit"
            )
        }
    }
}

/**
 * Opens this app's own page in Android settings — the only place a permission can
 * still be granted once the OS has stopped showing its dialog. Shared by the
 * blocking screen above and by onboarding.
 */
fun openAppSettings(context: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null)
    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // No settings activity available; nothing else this app can do.
    }
}

@Composable
private fun MissingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
