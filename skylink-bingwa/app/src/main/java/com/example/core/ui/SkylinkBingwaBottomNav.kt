package com.example.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Primary destinations. Per owner request (2026-08-29) Refer & Earn is a
// bottom-nav destination and Settings moved to the header (top-right, opened
// from its own icon); the notification centre still opens as an overlay from
// the Home header.
enum class BottomNavDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Outlined.Home),
    OFFERS("offers", "Offers", Icons.Outlined.LocalOffer),
    ACTIVITY("activity", "Activity", Icons.Outlined.History),
    HELP("help", "Help", Icons.Outlined.HelpOutline),
    REFERRALS("referrals", "Referrals", Icons.Outlined.Redeem)
}

@Composable
fun SkylinkBingwaBottomNav(
    currentRoute: String,
    onNavigate: (BottomNavDestination) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomNavDestination.entries.forEach { dest ->
            val selected = currentRoute == dest.route
            val activeColor = MaterialTheme.colorScheme.primary
            val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
            val indicatorBg = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(indicatorBg)
                    .clickable { onNavigate(dest) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("nav_item_${dest.route}")
            ) {
                Icon(
                    imageVector = dest.icon,
                    contentDescription = dest.label,
                    modifier = Modifier.size(24.dp),
                    tint = if (selected) activeColor else inactiveColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dest.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) activeColor else inactiveColor,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}
