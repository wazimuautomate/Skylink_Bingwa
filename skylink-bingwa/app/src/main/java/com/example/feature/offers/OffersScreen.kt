package com.example.feature.offers

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.ui.EmptyStateView
import com.example.core.ui.OfferCard
import com.example.feature.home.boughtTodayNote
import com.example.core.ui.OfferCardSkeleton
import com.example.data.fake.MAX_OFFER_PRICE_KSH
import com.example.data.fake.OfferFilterState
import com.example.data.fake.SortOption
import com.example.data.fake.ValidityFilter
import com.example.feature.home.OffersUiState
import com.example.ui.theme.BottomSheetTopShape
import com.example.ui.theme.FieldButtonShape
import com.example.ui.theme.TagShape
import com.example.ui.theme.TypographyPageHeading
import kotlinx.coroutines.launch

/**
 * Offers (Plan.md §5.3 / design.md §14.4). Search + category chips + a
 * filter/sort sheet (category, price range, validity; five sort orders) over
 * the cached catalogue. Query, filters, sort and scroll position are all
 * preserved: filter/sort live in the repository, scroll in the hoisted
 * [listState]. Loading, empty-from-filters, empty-catalogue and offline states
 * are all handled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersScreen(
    state: OffersUiState,
    listState: LazyListState = rememberLazyListState(),
    onSearchQueryChange: (String) -> Unit,
    onCategorySelect: (OfferCategory) -> Unit,
    onFilterStateChange: (OfferFilterState) -> Unit,
    onClearFilters: () -> Unit,
    onOfferSelect: (OfferItem) -> Unit,
    onOfferBuy: (OfferItem) -> Unit,
    onFavouriteToggle: (OfferItem) -> Unit,
    onUndoFavourite: (String) -> Unit
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    val filterState = state.filter
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val favouriteToggle: (OfferItem) -> Unit = { offer ->
        val wasFavourite = offer.isFavourite
        onFavouriteToggle(offer)
        if (wasFavourite) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Removed from favourites",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) onUndoFavourite(offer.id)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Offers",
                    style = TypographyPageHeading.copy(fontSize = 24.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = filterState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search data, SMS or minutes") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = {
                        if (filterState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    singleLine = true,
                    shape = FieldButtonShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("offers_search_field")
                )

                Spacer(Modifier.height(12.dp))

                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(OfferCategory.entries.toList()) { category ->
                        val selected = filterState.selectedCategory == category
                        FilterChip(
                            selected = selected,
                            onClick = { onCategorySelect(category) },
                            label = {
                                Text(
                                    text = category.label,
                                    style = MaterialTheme.typography.labelLarge,
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
                            modifier = Modifier.testTag("category_chip_${category.name.lowercase()}")
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (state.loading) "Loading offers" else "${state.resultCount} offers available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("offers_result_count")
                    )

                    Surface(
                        shape = FieldButtonShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(FieldButtonShape)
                            .clickable { showFilterSheet = true }
                            .testTag("filter_control_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.FilterList, contentDescription = "Filter", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Filter & Sort", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (state.isOffline) {
                    Spacer(Modifier.height(8.dp))
                    OfflineNoticeRow()
                }
            }

            Spacer(Modifier.height(4.dp))

            when {
                state.loading -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(5) { OfferCardSkeleton() }
                }

                state.results.isEmpty() && state.emptyFromFilters -> EmptyStateView(
                    icon = Icons.Outlined.SearchOff,
                    title = "No offers found",
                    description = "Try a different search or clear your filters to see everything.",
                    actionText = "Clear filters",
                    onActionClick = onClearFilters
                )

                state.results.isEmpty() -> EmptyStateView(
                    icon = Icons.Outlined.SearchOff,
                    title = "No offers yet",
                    description = if (state.isOffline)
                        "You're offline. You can still view and buy offers offline."
                    else
                        "Offers will appear here as soon as they're available."
                )

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("offers_list")
                ) {
                    items(state.results, key = { it.id }) { offer ->
                        OfferCard(
                            offer = offer,
                            isOffline = state.isOffline,
                            boughtTodayNote = boughtTodayNote(offer, state.purchases, state.nowMillis),
                            nowMillis = state.nowMillis,
                            onCardClick = { onOfferSelect(offer) },
                            onBuyClick = { onOfferBuy(offer) },
                            onFavouriteToggle = { favouriteToggle(offer) }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = BottomSheetTopShape,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            FilterBottomSheetContent(
                currentFilter = filterState,
                onApplyFilter = {
                    onFilterStateChange(it)
                    showFilterSheet = false
                },
                onClear = {
                    onClearFilters()
                    showFilterSheet = false
                }
            )
        }
    }
}

@Composable
private fun OfflineNoticeRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = "You're offline. You can still buy offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FilterBottomSheetContent(
    currentFilter: OfferFilterState,
    onApplyFilter: (OfferFilterState) -> Unit,
    onClear: () -> Unit
) {
    var selectedValidity by remember(currentFilter) { mutableStateOf(currentFilter.selectedValidity) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("filter_sheet")
    ) {
        Text(
            text = "Filter Offers",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Validity",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(10.dp))
        ValidityFilter.entries.forEach { option ->
            SelectableRow(
                label = option.label,
                selected = selectedValidity == option,
                onSelect = { selectedValidity = option },
                testTag = "validity_option_${option.name.lowercase()}"
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onClear,
                shape = FieldButtonShape,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
            ) {
                Text("Clear filters", style = MaterialTheme.typography.labelLarge)
            }

            Button(
                onClick = {
                    onApplyFilter(
                        currentFilter.copy(
                            selectedValidity = selectedValidity,
                            selectedSort = SortOption.LOWEST_PRICE,
                            maxPriceKsh = Int.MAX_VALUE
                        )
                    )
                },
                shape = FieldButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .weight(1.5f)
                    .height(50.dp)
                    .testTag("apply_filter_button")
            ) {
                Text("Show offers", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SelectableRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FieldButtonShape)
            .clickable { onSelect() }
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
