package com.example.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Schedule
import com.example.core.model.AvailabilityKind
import com.example.core.model.DailyRule
import com.example.core.model.OfferItem
import com.example.core.model.offerAvailabilityAt
import com.example.ui.theme.CardShape
import com.example.ui.theme.FieldButtonShape
import com.example.ui.theme.PrimaryActionGreen
import com.example.ui.theme.TagShape
import com.example.ui.theme.categoryColors
import com.example.ui.theme.TypographyOfferPrice

/**
 * One offer in a list.
 *
 * Two purchase-awareness facts are shown on every card, because both decide
 * whether a tap can succeed at all:
 *  - the offer's **time-of-day window** (Safaricom restricts some offers to a
 *    slot). Outside it the Buy button is replaced by an "Opens 5:00 PM" chip, and
 *    tapping the card explains the window instead of opening checkout.
 *  - [boughtTodayNote], the "Already bought today for 0712 345 678" line for a
 *    once-per-day offer, so the customer sees which number is already served
 *    before they type it at checkout.
 *
 * [nowMillis] is passed in rather than read here so the card is deterministic
 * under test.
 */
@Composable
fun OfferCard(
    offer: OfferItem,
    isOffline: Boolean = false,
    boughtTodayNote: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
    onCardClick: () -> Unit,
    onBuyClick: () -> Unit,
    onFavouriteToggle: () -> Unit
) {
    val availability = offerAvailabilityAt(offer, nowMillis)
    val closed = availability.kind == AvailabilityKind.CLOSED
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .clickable { onCardClick() }
            .testTag("offer_card_${offer.id}"),
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (offer.isBoughtToday) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (offer.isBoughtToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Top Row: Category tag on left, Favourite icon on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val categoryColors = categoryColors(offer.category)
                Surface(
                    color = categoryColors.container,
                    shape = TagShape
                ) {
                    Text(
                        text = offer.category.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = categoryColors.onContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                IconButton(
                    onClick = onFavouriteToggle,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("favourite_button_${offer.id}")
                ) {
                    Icon(
                        imageVector = if (offer.isFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favourite",
                        tint = if (offer.isFavourite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Middle Row: Bundle Name + Validity on left, Price on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${offer.name} ${offer.validity}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "KSh ${offer.priceKsh}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // Availability line — shown on EVERY offer, restricted or not, so the
            // customer never has to guess which offers have a selling window.
            if (availability.restricted) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("availability_${offer.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = if (closed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = availability.listLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (closed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // "Already bought today for 0712 345 678" — a once-per-day offer names
            // the number it already went to (Plan.md §5.12).
            if (!boughtTodayNote.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = boughtTodayNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("bought_today_note_${offer.id}")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Row: Tag on bottom left, Buy Button on bottom right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = offer.dailyRule.displayText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                if (closed) {
                    // Outside its selling window there is nothing to buy yet, so the
                    // Buy button is replaced by the opening time. Tapping it routes to
                    // the same explanation the card tap gives.
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = FieldButtonShape,
                        modifier = Modifier
                            .clickable { onCardClick() }
                            .testTag("closed_chip_${offer.id}")
                    ) {
                        Text(
                            text = availability.chipLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                } else if (offer.isBoughtToday) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = FieldButtonShape
                    ) {
                        Text(
                            text = "Bought today",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    Button(
                        onClick = onBuyClick,
                        shape = FieldButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("buy_button_${offer.id}")
                    ) {
                        Text(
                            text = "Buy",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
