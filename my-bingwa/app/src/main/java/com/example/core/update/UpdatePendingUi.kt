package com.example.core.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.core.model.Promotion
import com.example.core.model.PromotionAccent
import com.example.core.model.PromotionKind

/**
 * Builds the synthetic "Update available" Home billboard slide (Task 8.3). It is
 * prepended to the Home promotions in MainActivity so it always leads while an
 * update is pending, and its CTA is routed by id (never hidden by billboard
 * selection). Reuses the existing [Promotion] model — no new surface.
 */
object UpdatePromotion {
    /** Stable id used to recognise this slide in the promotion-action handler. */
    const val ID = "app_update_pending"

    fun forUpdate(update: UpdateResult.Available): Promotion {
        val name = update.versionName.takeIf { it.isNotBlank() }
        return Promotion(
            id = ID,
            kind = PromotionKind.UPDATE,
            tag = "UPDATE",
            headline = "Update available",
            subhead = if (name != null) {
                "Version $name is ready. Tap to update My Bingwa."
            } else {
                "A new version of My Bingwa is ready. Tap to update."
            },
            ctaLabel = "Update",
            accent = PromotionAccent.NAVY,
            // Always lead the billboard while an update is pending.
            priorityWeight = Int.MAX_VALUE,
        )
    }
}

/**
 * Full-screen, non-dismissible "Update required" gate (Task 8.1). Shown at app
 * start when the update is mandatory or this build is below the manifest's
 * minSupportedVersionCode; the customer cannot reach the app until they update.
 *
 * The Update action reuses [UpdateInstallControls]: in-app download+install for
 * the github source, or the Play listing for the play source. Back is swallowed
 * so there is no way around the gate.
 */
@Composable
fun UpdateRequiredScreen(update: UpdateResult.Available) {
    // Block the system back gesture/button — the gate cannot be dismissed.
    BackHandler(enabled = true) { /* intentionally no-op */ }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // Consume taps so the gated app underneath can never be interacted with.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { /* swallow */ }
            .testTag("update_required_screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Update required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = update.notes.takeIf { it.isNotBlank() }
                    ?: "A newer version of My Bingwa is required to keep buying bundles. Please update to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            UpdateInstallControls(
                update = update,
                modifier = Modifier.fillMaxWidth(),
                githubLabel = "Update now",
                testTag = "update_required_action",
            )
        }
    }
}
