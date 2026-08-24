package com.example.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.PaymentStatus
import com.example.core.model.PurchaseRecord
import com.example.core.ui.EmptyStateView
import com.example.core.ui.PrimaryButton
import com.example.core.ui.SecondaryButton
import com.example.ui.theme.BottomSheetTopShape
import com.example.ui.theme.FieldButtonShape
import com.example.ui.theme.TagShape
import com.example.ui.theme.TypographyPageHeading
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ActivityFilter(val label: String) {
    ALL("All"),
    SUCCESSFUL("Successful"),
    FAILED("Failed"),
    DATA("Data"),
    SMS("SMS"),
    MINUTES("Minutes")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    purchases: List<PurchaseRecord>,
    userPrimaryNumber: String,
    onDeleteRecord: (String) -> Unit,
    onDeleteRecords: (List<String>) -> Unit,
    onUndoDelete: (PurchaseRecord) -> Unit,
    onBrowseOffers: () -> Unit,
    onReportProblem: (PurchaseRecord) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedRecordForDetail by remember { mutableStateOf<PurchaseRecord?>(null) }
    var selectedFilter by remember { mutableStateOf(ActivityFilter.ALL) }

    // Multi-selection mode
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    // Filtered purchases
    val filteredPurchases = remember(purchases, selectedFilter) {
        purchases.filter { record ->
            when (selectedFilter) {
                ActivityFilter.ALL -> true
                ActivityFilter.SUCCESSFUL -> record.status == PaymentStatus.RECEIVED || record.status == PaymentStatus.WAITING_VERIFY || record.status == PaymentStatus.NOT_CONFIRMED
                ActivityFilter.FAILED -> record.status == PaymentStatus.FAILED || record.status == PaymentStatus.CANCELLED || record.status == PaymentStatus.EXPIRED || record.status == PaymentStatus.COULD_NOT_VERIFY
                ActivityFilter.DATA -> record.offerName.contains("data", ignoreCase = true) || record.allowance.contains("gb", ignoreCase = true) || record.allowance.contains("mb", ignoreCase = true)
                ActivityFilter.SMS -> record.offerName.contains("sms", ignoreCase = true) || record.allowance.contains("sms", ignoreCase = true)
                ActivityFilter.MINUTES -> record.offerName.contains("minute", ignoreCase = true) || record.offerName.contains("voice", ignoreCase = true) || record.offerName.contains("call", ignoreCase = true) || record.allowance.contains("min", ignoreCase = true)
            }
        }
    }

    // Grouping by date
    val now = System.currentTimeMillis()
    val dayMillis = 24 * 60 * 60 * 1000L

    val todayPurchases = filteredPurchases.filter { now - it.timestampMillis < dayMillis }
    val yesterdayPurchases = filteredPurchases.filter { now - it.timestampMillis in dayMillis until (2 * dayMillis) }
    val earlierPurchases = filteredPurchases.filter { now - it.timestampMillis >= 2 * dayMillis }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Activity",
                    style = TypographyPageHeading.copy(fontSize = 24.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )

                if (isSelectionMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = {
                            onDeleteRecords(selectedIds.toList())
                            selectedIds.clear()
                            isSelectionMode = false
                        }) {
                            Icon(imageVector = Icons.Outlined.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Filter Chips Bar
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items(ActivityFilter.entries.toTypedArray()) { filter ->
                    val selected = selectedFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                text = filter.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        shape = TagShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.testTag("activity_filter_chip_${filter.name.lowercase()}")
                    )
                }
            }

            if (purchases.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Outlined.History,
                    title = "No purchases yet",
                    description = "Bundles you pay for in Skylink Bingwa will appear here.",
                    actionText = "Browse offers",
                    onActionClick = onBrowseOffers
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (todayPurchases.isNotEmpty()) {
                        item { GroupHeader("Today") }
                        items(todayPurchases, key = { it.id }) { record ->
                            ActivityRowItem(
                                record = record,
                                userPrimaryNumber = userPrimaryNumber,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedIds.contains(record.id),
                                onToggleSelect = {
                                    if (selectedIds.contains(record.id)) selectedIds.remove(record.id)
                                    else selectedIds.add(record.id)
                                },
                                onClick = {
                                    if (isSelectionMode) {
                                        if (selectedIds.contains(record.id)) selectedIds.remove(record.id)
                                        else selectedIds.add(record.id)
                                    } else {
                                        selectedRecordForDetail = record
                                    }
                                },
                                onDelete = {
                                    onDeleteRecord(record.id)
                                    coroutineScope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Purchase record deleted",
                                            actionLabel = "Undo",
                                            duration = SnackbarDuration.Short
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            onUndoDelete(record)
                                        }
                                    }
                                },
                                onEnterSelectionMode = {
                                    isSelectionMode = true
                                    selectedIds.add(record.id)
                                }
                            )
                        }
                    }

                    if (yesterdayPurchases.isNotEmpty()) {
                        item { GroupHeader("Yesterday") }
                        items(yesterdayPurchases, key = { it.id }) { record ->
                            ActivityRowItem(
                                record = record,
                                userPrimaryNumber = userPrimaryNumber,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedIds.contains(record.id),
                                onToggleSelect = {
                                    if (selectedIds.contains(record.id)) selectedIds.remove(record.id)
                                    else selectedIds.add(record.id)
                                },
                                onClick = {
                                    if (isSelectionMode) {
                                        if (selectedIds.contains(record.id)) selectedIds.remove(record.id)
                                        else selectedIds.add(record.id)
                                    } else {
                                        selectedRecordForDetail = record
                                    }
                                },
                                onDelete = {
                                    onDeleteRecord(record.id)
                                    coroutineScope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Purchase record deleted",
                                            actionLabel = "Undo"
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            onUndoDelete(record)
                                        }
                                    }
                                },
                                onEnterSelectionMode = {
                                    isSelectionMode = true
                                    selectedIds.add(record.id)
                                }
                            )
                        }
                    }

                    if (earlierPurchases.isNotEmpty()) {
                        item { GroupHeader("Earlier") }
                        items(earlierPurchases, key = { it.id }) { record ->
                            ActivityRowItem(
                                record = record,
                                userPrimaryNumber = userPrimaryNumber,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedIds.contains(record.id),
                                onToggleSelect = {
                                    if (selectedIds.contains(record.id)) selectedIds.remove(record.id)
                                    else selectedIds.add(record.id)
                                },
                                onClick = {
                                    if (isSelectionMode) {
                                        if (selectedIds.contains(record.id)) selectedIds.remove(record.id)
                                        else selectedIds.add(record.id)
                                    } else {
                                        selectedRecordForDetail = record
                                    }
                                },
                                onDelete = {
                                    onDeleteRecord(record.id)
                                    coroutineScope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Purchase record deleted",
                                            actionLabel = "Undo"
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            onUndoDelete(record)
                                        }
                                    }
                                },
                                onEnterSelectionMode = {
                                    isSelectionMode = true
                                    selectedIds.add(record.id)
                                }
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // Detail Bottom Sheet
    selectedRecordForDetail?.let { record ->
        ModalBottomSheet(
            onDismissRequest = { selectedRecordForDetail = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = BottomSheetTopShape,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            ActivityDetailSheetContent(
                record = record,
                userPrimaryNumber = userPrimaryNumber,
                onClose = { selectedRecordForDetail = null }
            )
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun ActivityRowItem(
    record: PurchaseRecord,
    userPrimaryNumber: String,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEnterSelectionMode: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val formattedDate = remember(record.timestampMillis) {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        sdf.format(Date(record.timestampMillis))
    }

    val recipientText = if (record.recipientNumber == userPrimaryNumber) "Myself / ${record.recipientNumber}" else record.recipientNumber

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FieldButtonShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Status Icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    when (record.status) {
                        PaymentStatus.RECEIVED -> MaterialTheme.colorScheme.primaryContainer
                        PaymentStatus.WAITING_VERIFY, PaymentStatus.NOT_CONFIRMED -> MaterialTheme.colorScheme.tertiaryContainer
                        PaymentStatus.CANCELLED, PaymentStatus.FAILED,
                        PaymentStatus.EXPIRED, PaymentStatus.COULD_NOT_VERIFY -> MaterialTheme.colorScheme.errorContainer
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (record.status) {
                    PaymentStatus.RECEIVED -> Icons.Outlined.CheckCircle
                    PaymentStatus.WAITING_VERIFY, PaymentStatus.NOT_CONFIRMED -> Icons.Outlined.HourglassEmpty
                    PaymentStatus.CANCELLED, PaymentStatus.FAILED,
                    PaymentStatus.EXPIRED, PaymentStatus.COULD_NOT_VERIFY -> Icons.Outlined.ErrorOutline
                },
                contentDescription = null,
                tint = when (record.status) {
                    PaymentStatus.RECEIVED -> MaterialTheme.colorScheme.primary
                    PaymentStatus.WAITING_VERIFY, PaymentStatus.NOT_CONFIRMED -> MaterialTheme.colorScheme.tertiary
                    PaymentStatus.CANCELLED, PaymentStatus.FAILED,
                    PaymentStatus.EXPIRED, PaymentStatus.COULD_NOT_VERIFY -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.offerName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$recipientText • Ref: ${record.mpesaCode}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "KSh ${record.priceKsh}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formattedDate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.testTag("activity_row_menu_${record.id}")
            ) {
                Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        showMenu = false
                        val fullLog = "Offer: ${record.offerName}\nRecipient: $recipientText\nPrice: KSh ${record.priceKsh}\nRef: ${record.mpesaCode}\nDate: $formattedDate"
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(fullLog))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy M-Pesa code") },
                    onClick = {
                        showMenu = false
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(record.mpesaCode))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun ActivityDetailSheetContent(
    record: PurchaseRecord,
    userPrimaryNumber: String,
    onClose: () -> Unit
) {
    val fullDate = remember(record.timestampMillis) {
        val sdf = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
        sdf.format(Date(record.timestampMillis))
    }

    val recipientDisplay = if (record.recipientNumber == userPrimaryNumber) "Myself / ${record.recipientNumber}" else record.recipientNumber

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = "Purchase Summary",
            style = TypographyPageHeading.copy(fontSize = 22.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(FieldButtonShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailRow(label = "Offer", value = record.offerName)
            DetailRow(label = "Recipient", value = recipientDisplay)
            DetailRow(label = "Payment Method", value = record.paymentMethod.label)
            DetailRow(label = "M-Pesa Code", value = record.mpesaCode)
            DetailRow(label = "Date & Time", value = fullDate)
        }

        Spacer(modifier = Modifier.height(24.dp))

        PrimaryButton(
            text = "Close",
            onClick = onClose
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}
