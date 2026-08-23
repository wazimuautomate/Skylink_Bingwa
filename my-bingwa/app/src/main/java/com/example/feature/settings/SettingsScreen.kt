package com.example.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.core.model.AppThemeSetting
import com.example.core.model.UserProfile
import com.example.core.update.UpdateChecker
import com.example.core.update.UpdateInstallControls
import com.example.core.update.UpdateResult
import com.example.core.ui.LabelledPhoneField
import com.example.core.ui.LabelledTextField
import com.example.core.ui.PrimaryButton
import com.example.ui.theme.BottomSheetTopShape
import com.example.ui.theme.FieldButtonShape
import com.example.ui.theme.TypographyPageHeading
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profile: UserProfile,
    currentTheme: AppThemeSetting,
    onUpdateProfile: (String, String) -> Unit,
    onThemeSelect: (AppThemeSetting) -> Unit,
    onClearLocalData: () -> Unit,
    // An update the app already discovered at start (MainActivity's check). When
    // present the install action shows immediately, so a notification/billboard
    // deep-link into Settings lands on a usable "Update" button without the user
    // having to tap "Check for updates" first.
    knownUpdate: UpdateResult.Available? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showEditProfileSheet by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    // Set when a manual "Check for updates" finds a newer direct-channel build.
    // Combined with [knownUpdate] to drive the in-app install / Play redirect.
    var manualUpdate by remember { mutableStateOf<UpdateResult.Available?>(null) }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Settings",
            style = TypographyPageHeading.copy(fontSize = 24.sp),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Profile Section Card
        SettingsGroupTitle("Profile")
        Surface(
            shape = FieldButtonShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = "User Avatar",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name.ifEmpty { "Customer" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = profile.primaryNumber.ifEmpty { "No phone set" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { showEditProfileSheet = true },
                    modifier = Modifier.testTag("edit_profile_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit Profile",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Appearance Section (Icon-based theme selector)
        SettingsGroupTitle("Appearance")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ThemeIconCard(
                label = "System",
                icon = Icons.Outlined.SettingsSuggest,
                isSelected = currentTheme == AppThemeSetting.SYSTEM,
                onClick = { onThemeSelect(AppThemeSetting.SYSTEM) },
                modifier = Modifier.weight(1f)
            )
            ThemeIconCard(
                label = "Light",
                icon = Icons.Outlined.LightMode,
                isSelected = currentTheme == AppThemeSetting.LIGHT,
                onClick = { onThemeSelect(AppThemeSetting.LIGHT) },
                modifier = Modifier.weight(1f)
            )
            ThemeIconCard(
                label = "Dark",
                icon = Icons.Outlined.DarkMode,
                isSelected = currentTheme == AppThemeSetting.DARK,
                onClick = { onThemeSelect(AppThemeSetting.DARK) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Notifications and Safaricom bundle messages are no longer settings.
        // Both are required to use My Bingwa and are granted during onboarding
        // (feature/onboarding/OnboardingScreen.kt); if either is later revoked in
        // Android's own settings, MainActivity shows a blocking screen rather than
        // a toggle here. A switch that the app would immediately override is worse
        // than no switch at all.

        // About Section
        SettingsGroupTitle("About App")
        Surface(
            shape = FieldButtonShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("App Version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "My Bingwa allows you buy safaricom data, sms and minutes even if you have unpaid Okoa jahazi even if you are offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Self-update is a DEBUG-only development affordance. A shipped build is
                // distributed and updated by Google Play, so it neither fetches
                // update.json nor offers to install an APK itself — the whole control is
                // absent rather than present-but-useless.
                if (BuildConfig.GITHUB_UPDATER_ENABLED) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (checkingUpdates) return@Button
                            checkingUpdates = true
                            updateMessage = null
                            manualUpdate = null
                            scope.launch {
                                updateMessage = when (val result = UpdateChecker.check()) {
                                    is UpdateResult.Available -> {
                                        manualUpdate = result
                                        val name = result.versionName.takeIf { it.isNotBlank() }
                                        if (name != null) "Version $name is available." else "A new version is available."
                                    }
                                    UpdateResult.UpToDate ->
                                        "You are on the latest version of My Bingwa."
                                    is UpdateResult.Error -> result.message
                                }
                                checkingUpdates = false
                            }
                        },
                        enabled = !checkingUpdates,
                        shape = FieldButtonShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (checkingUpdates) "Checking…" else "Check for updates", fontWeight = FontWeight.Bold)
                    }

                    if (updateMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = updateMessage!!,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // In-app install of the signed update (no browser). Shown when either a
                    // manual check or the app-start check (knownUpdate) found a newer build.
                    // Downloads + verifies + launches the system installer (github), or opens
                    // the Play listing (play). An in-place update preserves all local data.
                    (manualUpdate ?: knownUpdate)?.let { update ->
                        Spacer(modifier = Modifier.height(12.dp))
                        UpdateInstallControls(update = update)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Local Data Section
        SettingsGroupTitle("Local Data")
        Surface(
            shape = FieldButtonShape,
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Your profile, favourites and Activity are saved on this phone. Clearing them cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showClearDataDialog = true },
                    shape = FieldButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("clear_local_data_button")
                ) {
                    Icon(imageVector = Icons.Outlined.DeleteForever, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear local data", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(36.dp))
    }

    // Edit Profile Sheet
    if (showEditProfileSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEditProfileSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = BottomSheetTopShape,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            var nameInput by remember { mutableStateOf(profile.name) }
            var phoneInput by remember { mutableStateOf(profile.primaryNumber) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Edit Profile",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(20.dp))

                LabelledTextField(
                    label = "Your name",
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    testTag = "edit_profile_name"
                )

                Spacer(modifier = Modifier.height(16.dp))

                LabelledPhoneField(
                    label = "Primary Safaricom number",
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    testTag = "edit_profile_phone"
                )

                Spacer(modifier = Modifier.height(28.dp))

                PrimaryButton(
                    text = "Save changes",
                    onClick = {
                        onUpdateProfile(nameInput.trim(), phoneInput.trim())
                        showEditProfileSheet = false
                    },
                    testTag = "save_profile_button"
                )
            }
        }
    }

    // Clear Data Confirmation Dialog
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Clear all local data?", fontWeight = FontWeight.Bold) },
            text = { Text("This will reset your profile, purchase activity, favourites and settings on this device. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearLocalData()
                        showClearDataDialog = false
                    }
                ) {
                    Text("Clear local data", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Keep my data")
                }
            }
        )
    }
}

@Composable
private fun ThemeIconCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = FieldButtonShape,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = modifier
            .clip(FieldButtonShape)
            .border(
                width = 1.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = FieldButtonShape
            )
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
