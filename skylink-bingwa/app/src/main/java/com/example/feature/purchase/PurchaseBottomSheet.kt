package com.example.feature.purchase

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.core.model.OfferItem
import com.example.core.model.offerAvailabilityAt
import com.example.core.model.PaymentStatus
import com.example.core.model.PurchaseRecord
import com.example.core.payment.KenyanPhone
import com.example.core.ui.CopyableValueBlock
import com.example.core.ui.LabelledPhoneField
import com.example.core.ui.PrimaryButton
import com.example.core.ui.SecondaryButton
import com.example.data.payment.OfflineEligibility
import com.example.data.payment.OfflinePaymentConfig
import com.example.ui.theme.BottomSheetTopShape
import com.example.ui.theme.FieldButtonShape
import com.example.ui.theme.TypographyPageHeading
import com.example.ui.theme.TypographyReviewTotal
import com.example.ui.theme.TypographySheetHeading
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STEP_RECIPIENT = 1
private const val STEP_REVIEW = 2
private const val STEP_PROCESSING = 3
private const val STEP_RESULT = 4
private const val STEP_OFFLINE = 5

/** Reveal "Resend prompt" only after this controlled delay (Plan.md §5.7). */
private const val RESEND_DELAY_MILLIS = 12_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseBottomSheet(
    offer: OfferItem,
    userPrimaryNumber: String,
    recentRecipients: List<String>,
    // The M-Pesa number this customer actually pays with, learned on-device from
    // their own history. Customers rarely change it, so pre-filling it saves the
    // most-retyped field in checkout. Blank (no history yet, or a fresh install)
    // falls back to the primary number exactly as before.
    preferredPayerNumber: String = "",
    isOffline: Boolean,
    /**
     * Why the bundle cannot be bought for this recipient number right now, or null
     * when it can. Today this is the once-per-day-per-number rule
     * ([com.example.feature.home.repeatPurchaseBlockMessage]) — the sheet only
     * renders what it is told, so the rule stays testable outside Compose.
     *
     * It is re-evaluated as the customer types, which is the whole point: they can
     * see the block, change to another number, and continue without leaving.
     */
    recipientBlockMessage: (String) -> String? = { null },
    onExecuteStkPush: suspend (OfferItem, String, String, String, Boolean) -> PurchaseRecord,
    onExecuteOfflinePayment: suspend (OfferItem, String, String, Boolean, String?) -> PurchaseRecord,
    offlineEligibility: (OfferItem, Boolean) -> OfflineEligibility,
    offlineConfig: () -> OfflinePaymentConfig?,
    onDismiss: () -> Unit,
    onViewActivity: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    // rememberSaveable so a rotation mid-checkout keeps the step and typed numbers.
    var purchaseStep by rememberSaveable { mutableStateOf(STEP_RECIPIENT) }
    var isForSelf by rememberSaveable { mutableStateOf(true) }
    var recipientNumber by rememberSaveable { mutableStateOf(userPrimaryNumber) }
    var payerNumber by rememberSaveable {
        mutableStateOf(preferredPayerNumber.ifBlank { userPrimaryNumber })
    }

    var lastRecord by remember { mutableStateOf<PurchaseRecord?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    // Airtight double-tap guard: a second Pay tap while a request is in flight (or
    // already launched this attempt) is ignored; the repository is also idempotent
    // on clientRequestId so no second charge can occur (CLAUDE.md §7).
    var payInFlight by remember { mutableStateOf(false) }

    val runStkPush: () -> Unit = {
        if (!payInFlight) {
            payInFlight = true
            isLoading = true
            purchaseStep = STEP_PROCESSING
            coroutineScope.launch {
                val clientRequestId = UUID.randomUUID().toString()
                val record = onExecuteStkPush(offer, recipientNumber, payerNumber, clientRequestId, isForSelf)
                lastRecord = record
                isLoading = false
                payInFlight = false
                purchaseStep = STEP_RESULT
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = BottomSheetTopShape,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AnimatedContent(
                targetState = purchaseStep,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "purchase_flow_step"
            ) { step ->
                when (step) {
                    STEP_RECIPIENT -> RecipientSelectionStep(
                        isForSelf = isForSelf,
                        recipientNumber = recipientNumber,
                        payerNumber = payerNumber,
                        userPrimaryNumber = userPrimaryNumber,
                        recentRecipients = recentRecipients,
                        blockMessage = recipientBlockMessage(recipientNumber),
                        onOptionSelect = { selfSelected ->
                            isForSelf = selfSelected
                            if (selfSelected) {
                                recipientNumber = userPrimaryNumber
                                payerNumber = userPrimaryNumber
                            } else {
                                if (recipientNumber == userPrimaryNumber) recipientNumber = ""
                                payerNumber = userPrimaryNumber
                            }
                        },
                        onRecipientChange = { recipientNumber = it },
                        onPayerChange = { payerNumber = it },
                        // Offline goes straight to the manual M-Pesa steps (no online
                        // review); online goes through the price/total review first.
                        onNext = { purchaseStep = if (isOffline) STEP_OFFLINE else STEP_REVIEW }
                    )

                    STEP_REVIEW -> ReviewPurchaseStep(
                        offer = offer,
                        recipientNumber = recipientNumber,
                        payerNumber = payerNumber,
                        isOffline = isOffline,
                        offlineEligibility = if (isOffline) offlineEligibility(offer, isForSelf) else null,
                        onPayOnline = runStkPush,
                        onViewOfflineSteps = { purchaseStep = STEP_OFFLINE },
                        onChangeDetails = { purchaseStep = STEP_RECIPIENT }
                    )

                    STEP_PROCESSING -> StkProcessingStep(
                        payerNumber = payerNumber,
                        onResend = runStkPush,
                        onChangeNumber = {
                            payInFlight = false
                            isLoading = false
                            purchaseStep = STEP_RECIPIENT
                        }
                    )

                    STEP_RESULT -> PaymentResultStep(
                        record = lastRecord,
                        onDone = onDismiss,
                        onViewActivity = {
                            onDismiss()
                            onViewActivity()
                        },
                        onTryAgain = { purchaseStep = STEP_REVIEW }
                    )

                    STEP_OFFLINE -> OfflinePaymentInstructionsStep(
                        offer = offer,
                        isTill = isForSelf,
                        recipientNumber = recipientNumber,
                        payerNumber = payerNumber,
                        config = offlineConfig(),
                        onSubmitOffline = { receipt ->
                            coroutineScope.launch {
                                onExecuteOfflinePayment(offer, recipientNumber, payerNumber, isForSelf, receipt)
                                onDismiss()
                                onViewActivity()
                            }
                        },
                        onCancel = { purchaseStep = STEP_REVIEW }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecipientSelectionStep(
    isForSelf: Boolean,
    recipientNumber: String,
    payerNumber: String,
    userPrimaryNumber: String,
    recentRecipients: List<String>,
    blockMessage: String?,
    onOptionSelect: (Boolean) -> Unit,
    onRecipientChange: (String) -> Unit,
    onPayerChange: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Who is the bundle for?",
            style = TypographySheetHeading,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        SelectableRecipientCard(
            title = "For my number",
            subtitle = KenyanPhone.toDisplay(recipientNumber),
            isSelected = isForSelf,
            onClick = { onOptionSelect(true) },
            testTag = "recipient_for_my_number"
        )

        Spacer(modifier = Modifier.height(12.dp))

        SelectableRecipientCard(
            title = "For another number",
            subtitle = "Buy data or SMS for family and friends",
            isSelected = !isForSelf,
            onClick = { onOptionSelect(false) },
            testTag = "recipient_for_another_number"
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (isForSelf) {
            // Buying for yourself: recipient and M-Pesa payer are the same number,
            // so a single editable field drives both — tapping it lets the customer
            // fix a wrong digit instead of being stuck with whatever is on the
            // profile (design.md §13.3: the field label is explicit).
            LabelledPhoneField(
                label = "Your number",
                value = recipientNumber,
                onValueChange = { number ->
                    onRecipientChange(number)
                    onPayerChange(number)
                },
                placeholder = "0712 345 678",
                testTag = "recipient_number_field"
            )
        } else {
            // design.md §13.3: the recipient field label is explicit.
            LabelledPhoneField(
                label = "Bundle recipient",
                value = recipientNumber,
                onValueChange = onRecipientChange,
                placeholder = "0712 345 678",
                testTag = "recipient_number_field"
            )

            if (recentRecipients.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Recent recipients",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentRecipients.take(3).forEach { rec ->
                        Surface(
                            shape = FieldButtonShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { onRecipientChange(rec) }
                        ) {
                            Text(
                                text = KenyanPhone.toDisplay(rec),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LabelledPhoneField(
                label = "M-Pesa payment number",
                value = payerNumber,
                onValueChange = onPayerChange,
                placeholder = KenyanPhone.toDisplay(userPrimaryNumber),
                testTag = "payer_number_field"
            )
        }

        // The once-per-day-per-number block. It appears the moment a blocked number
        // is typed, names that number, and leaves the field editable so another
        // number can be used straight away — never a dead end (design.md §14.9).
        if (!blockMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(FieldButtonShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(14.dp)
                    .testTag("recipient_blocked_notice"),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Already bought today",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = blockMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        val recipientValid = KenyanPhone.isValid(recipientNumber)
        val payerValid = KenyanPhone.isValid(payerNumber)
        PrimaryButton(
            text = "Confirm",
            onClick = onNext,
            enabled = recipientValid && payerValid && blockMessage.isNullOrBlank(),
            testTag = "review_purchase_button"
        )
    }
}

@Composable
private fun SelectableRecipientCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FieldButtonShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                1.5.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                FieldButtonShape
            )
            .clickable { onClick() }
            .padding(16.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReviewPurchaseStep(
    offer: OfferItem,
    recipientNumber: String,
    payerNumber: String,
    isOffline: Boolean,
    offlineEligibility: OfflineEligibility?,
    onPayOnline: () -> Unit,
    onViewOfflineSteps: () -> Unit,
    onChangeDetails: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Review purchase",
            style = TypographySheetHeading,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = FieldButtonShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = offer.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Validity: ${offer.validity}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Payer and recipient are always distinct, fully readable and clearly
        // labelled (Plan.md §3.2, design.md §14.7). Start-aligned values.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(FieldButtonShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Both numbers are tappable: seeing a wrong digit at the moment of
            // paying is exactly when it needs fixing, and hunting for "Change
            // details" below the fold is a step too many. Tapping either one goes
            // straight back to the field that owns it.
            SummaryRow(
                label = "Bundle recipient",
                value = KenyanPhone.toDisplay(recipientNumber),
                onEdit = onChangeDetails,
                testTag = "review_recipient_row"
            )
            SummaryRow(
                label = "M-Pesa payment number",
                value = KenyanPhone.toDisplay(payerNumber),
                onEdit = onChangeDetails,
                testTag = "review_payer_row"
            )
            SummaryRow(label = "Daily rule", value = offer.dailyRule.displayText)
            // Restate the selling window at the point of paying, so a customer who
            // opened the sheet just before it closed is not surprised.
            val availability = offerAvailabilityAt(offer, System.currentTimeMillis())
            if (availability.restricted) {
                SummaryRow(label = "Sold between", value = availability.windowLabel)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "KSh ${offer.priceKsh}",
            style = TypographyReviewTotal,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isOffline) {
            val eligible = offlineEligibility is OfflineEligibility.Eligible
            if (!eligible && offlineEligibility != null) {
                OfflineNotice(offlineEligibility)
                Spacer(modifier = Modifier.height(16.dp))
            }
            PrimaryButton(
                text = "View offline payment steps",
                onClick = onViewOfflineSteps,
                enabled = eligible,
                testTag = "pay_now_button"
            )
        } else {
            PrimaryButton(
                text = "Pay KSh ${offer.priceKsh}",
                onClick = onPayOnline,
                testTag = "pay_now_button"
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        SecondaryButton(
            text = "Change details",
            onClick = onChangeDetails,
            testTag = "change_details_button"
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Tap a number above to correct it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Explains why an offer cannot be paid offline right now (Plan.md §5.8/§5.12). */
@Composable
private fun OfflineNotice(eligibility: OfflineEligibility) {
    val message = when (eligibility) {
        is OfflineEligibility.Expired ->
            "These offline instructions have expired. Connect to the internet to refresh them before paying."
        is OfflineEligibility.ConfigUnavailable ->
            "Offline payment details are not available right now. Connect to the internet to refresh them."
        is OfflineEligibility.HardLimitBlocked ->
            "This offer is limited to once per day and cannot be paid offline. Connect to the internet to buy it."
        is OfflineEligibility.AmbiguousAmount ->
            "This offer shares its price with another, so it cannot be safely identified offline. Connect to the internet to buy it."
        is OfflineEligibility.OutsideSellingWindow ->
            "Safaricom only sells this offer between ${eligibility.windowLabel}. Come back inside that window — paying now would not deliver the bundle."
        is OfflineEligibility.Eligible -> return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FieldButtonShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

/**
 * One label/value line in a summary block. Passing [onEdit] makes the value
 * tappable and marks it with a pencil, so a number that is wrong can be corrected
 * from the place where the customer notices it.
 */
@Composable
private fun SummaryRow(
    label: String,
    value: String,
    onEdit: (() -> Unit)? = null,
    testTag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onEdit != null) Modifier.clickable { onEdit() } else Modifier)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(vertical = if (onEdit != null) 2.dp else 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (onEdit != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            if (onEdit != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit $label",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun StkProcessingStep(
    payerNumber: String,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit
) {
    var showResend by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showResend = false
        delay(RESEND_DELAY_MILLIS)
        showResend = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Indeterminate only — never a fake percentage (Plan.md §5.7, design.md §11).
        CircularProgressIndicator(
            modifier = Modifier.size(56.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Check your phone",
            style = TypographyPageHeading.copy(fontSize = 24.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "We sent an M-Pesa request to ${KenyanPhone.toDisplay(payerNumber)}. Enter your M-Pesa PIN on that phone to approve it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        if (showResend) {
            PrimaryButton(
                text = "Resend prompt",
                onClick = onResend,
                testTag = "resend_prompt_button"
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        SecondaryButton(
            text = "Change payment number",
            onClick = onChangeNumber,
            testTag = "change_payment_number_button"
        )
    }
}

@Composable
private fun PaymentResultStep(
    record: PurchaseRecord?,
    onDone: () -> Unit,
    onViewActivity: () -> Unit,
    onTryAgain: () -> Unit
) {
    if (record == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (record.status) {
            PaymentStatus.RECEIVED -> ReceivedResult(record, onDone, onViewActivity)

            PaymentStatus.CANCELLED -> SimpleResult(
                icon = Icons.Outlined.ErrorOutline,
                tint = MaterialTheme.colorScheme.error,
                heading = "Payment cancelled",
                body = "No payment was completed. Your details are still here.",
                primaryText = "Try again",
                onPrimary = onTryAgain
            )

            PaymentStatus.FAILED -> SimpleResult(
                icon = Icons.Outlined.ErrorOutline,
                tint = MaterialTheme.colorScheme.error,
                heading = "Payment failed",
                body = "No money was deducted. Check your network and try again.",
                primaryText = "Try again",
                onPrimary = onTryAgain
            )

            PaymentStatus.EXPIRED -> SimpleResult(
                icon = Icons.Outlined.HourglassEmpty,
                tint = MaterialTheme.colorScheme.error,
                heading = "Request expired",
                body = "The request expired before it was approved. You can safely send it again.",
                primaryText = "Try again",
                onPrimary = onTryAgain
            )

            PaymentStatus.WAITING_VERIFY -> SimpleResult(
                icon = Icons.Outlined.HourglassEmpty,
                tint = MaterialTheme.colorScheme.tertiary,
                heading = "Still checking payment",
                body = "This is taking longer than usual. You can check Activity or contact support.",
                primaryText = "View activity",
                onPrimary = onViewActivity
            )

            PaymentStatus.NOT_CONFIRMED -> SimpleResult(
                icon = Icons.Outlined.HourglassEmpty,
                tint = MaterialTheme.colorScheme.tertiary,
                heading = "Payment not confirmed",
                body = "Enter the M-Pesa receipt when you have it, or check again online.",
                primaryText = "View activity",
                onPrimary = onViewActivity
            )

            PaymentStatus.COULD_NOT_VERIFY -> SimpleResult(
                icon = Icons.Outlined.ErrorOutline,
                tint = MaterialTheme.colorScheme.error,
                heading = "We could not verify this payment",
                body = "Check the receipt and payment details, or contact support with your reference.",
                primaryText = "View activity",
                onPrimary = onViewActivity
            )
        }
    }
}

@Composable
private fun ReceivedResult(
    record: PurchaseRecord,
    onDone: () -> Unit,
    onViewActivity: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = "Purchase Successful",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Purchase Successful",
        style = TypographyPageHeading.copy(fontSize = 24.sp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Honest fulfilment language: confirm payment only, never delivery
    // (CLAUDE.md §7, Plan.md §3.3). No delivery timeframe.
    Text(
        text = "Your purchase was received. Please wait for the bundle on ${KenyanPhone.toDisplay(record.recipientNumber)}.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FieldButtonShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryRow(label = "Offer", value = record.offerName)
        SummaryRow(label = "Bundle recipient", value = KenyanPhone.toDisplay(record.recipientNumber))
        SummaryRow(label = "Amount", value = "KSh ${record.priceKsh}")
        if (record.mpesaCode.isNotBlank() && record.mpesaCode != "-") {
            SummaryRow(label = "M-Pesa receipt", value = record.mpesaCode)
        }
        if (record.orderReference.isNotBlank()) {
            SummaryRow(label = "Reference", value = record.orderReference)
        }
    }

    Spacer(modifier = Modifier.height(28.dp))

    PrimaryButton(
        text = "Done",
        onClick = onDone,
        testTag = "payment_done_button"
    )

    Spacer(modifier = Modifier.height(10.dp))

    SecondaryButton(
        text = "View activity",
        onClick = onViewActivity,
        testTag = "view_activity_button"
    )
}

@Composable
private fun SimpleResult(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    heading: String,
    body: String,
    primaryText: String,
    onPrimary: () -> Unit
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(56.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = heading,
        style = TypographyPageHeading.copy(fontSize = 22.sp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(24.dp))
    PrimaryButton(text = primaryText, onClick = onPrimary, testTag = "result_primary_button")
}

@Composable
private fun OfflinePaymentInstructionsStep(
    offer: OfferItem,
    isTill: Boolean,
    recipientNumber: String,
    payerNumber: String,
    config: OfflinePaymentConfig?,
    onSubmitOffline: (String?) -> Unit,
    onCancel: () -> Unit
) {
    // Guard: if config is missing/expired at this point, do not show payable numbers —
    // explain that internet is needed instead (Plan.md §5.8). The route-specific number
    // is checked too: a config that carries a Till but no Paybill (or vice versa) must
    // not render a "copy the Paybill" button with nothing behind it.
    val routeNumber = config?.let { if (isTill) it.tillNumber else it.paybillNumber }
    if (config == null || routeNumber.isNullOrBlank()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Offline steps unavailable",
                style = TypographySheetHeading,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "We could not load valid payment details. Connect to the internet to refresh them and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            SecondaryButton(text = "Back", onClick = onCancel)
        }
        return
    }

    val context = LocalContext.current
    val till = config.tillNumber
    val paybill = config.paybillNumber
    // The value copied for one-tap paste: Till for own number, Paybill for another.
    val copyLabel = if (isTill) "Till number" else "Paybill number"
    val copyValue = if (isTill) till else paybill

    // Opening M-Pesa on the SIM whose number the user declared as theirs needs
    // READ_PHONE_STATE (best-effort; falls back to the default SIM if unavailable).
    val phonePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { copyAndOpenMpesa(context, copyLabel, copyValue, payerNumber) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isTill) "Pay using M-Pesa Till" else "Pay using M-Pesa Paybill",
            style = TypographySheetHeading,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Calm offline context banner (design.md §14.9), fully centred.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(FieldButtonShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No internet needed",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap below to copy the ${if (isTill) "Till" else "Paybill"} and open M-Pesa. Use the exact amount of the offer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Primary offline action: copy the number and open the SIM Toolkit / M-Pesa
        // menu so the customer can pay manually (Phase 6 offline behaviour).
        PrimaryButton(
            text = "Copy $copyLabel & open M-Pesa",
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    copyAndOpenMpesa(context, copyLabel, copyValue, payerNumber)
                } else {
                    phonePermLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
            },
            testTag = "open_mpesa_button"
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Copyable values from the signed config — single source of truth.
        if (isTill) {
            CopyableValueBlock(label = "M-Pesa Till number", value = till)
        } else {
            CopyableValueBlock(label = "Paybill business number", value = paybill)
            Spacer(modifier = Modifier.height(10.dp))
            CopyableValueBlock(label = "Account number (recipient)", value = KenyanPhone.toDisplay(recipientNumber))
        }

        Spacer(modifier = Modifier.height(10.dp))
        CopyableValueBlock(label = "Exact amount", value = "KSh ${offer.priceKsh}")

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Steps",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isTill) {
                InstructionLine("1", "Go to M-Pesa")
                InstructionLine("2", "Choose Lipa na M-Pesa")
                InstructionLine("3", "Choose Buy Goods and Services")
                InstructionLine("4", "Enter Till number $till")
                InstructionLine("5", "Enter the exact amount KSh ${offer.priceKsh}")
                InstructionLine("6", "Enter your M-pesa pin to complete payment.")
            } else {
                InstructionLine("1", "Go to M-Pesa")
                InstructionLine("2", "Choose Lipa na M-Pesa")
                InstructionLine("3", "Choose Pay Bill")
                InstructionLine("4", "Enter business number $paybill")
                InstructionLine("5", "Enter ${KenyanPhone.toDisplay(recipientNumber)} as the account number")
                InstructionLine("6", "Enter the exact amount KSh ${offer.priceKsh}")
                InstructionLine("7", "Complete payment from the buyer's M-Pesa number")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        SecondaryButton(
            text = "Cancel offline purchase",
            onClick = onCancel,
            testTag = "cancel_offline_button"
        )
    }
}

/**
 * Offline manual payment: copy the Till/Paybill to the clipboard, confirm with a
 * toast, then open the SIM Toolkit (M-Pesa menu). Falls back to dialling the STK
 * USSD if the SIM Toolkit app is unavailable. Mirrors HelpScreen's opener.
 */
private fun copyAndOpenMpesa(context: Context, label: String, value: String, payerNumber: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "$label $value copied — opening M-Pesa", Toast.LENGTH_SHORT).show()

    // Best-effort: run the STK USSD on the SIM whose number the user declared as
    // theirs (the payer number), not always SIM 1.
    val handle = phoneAccountForNumber(context, payerNumber)
    try {
        if (handle != null) {
            val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:*234%23"))
            dial.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            context.startActivity(dial)
            return
        }
        val stkIntent = context.packageManager.getLaunchIntentForPackage("com.android.stk")
            ?: Intent(Intent.ACTION_DIAL, Uri.parse("tel:*234%23"))
        context.startActivity(stkIntent)
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:*234%23")))
        } catch (_: Exception) {
        }
    }
}

/**
 * Best-effort PhoneAccountHandle for the SIM whose MSISDN matches [number] (the
 * payer's declared M-Pesa number), so the USSD runs on that SIM. Returns null when
 * it cannot be resolved (no READ_PHONE_STATE, the carrier does not expose the SIM
 * number, or a single-SIM device), and the caller then uses the default SIM.
 */
private fun phoneAccountForNumber(context: Context, number: String): PhoneAccountHandle? {
    return try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val target = number.filter { it.isDigit() }.takeLast(9)
        if (target.isEmpty()) return null
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val sub = sm.activeSubscriptionInfoList?.firstOrNull {
            (it.number ?: "").filter { c -> c.isDigit() }.takeLast(9) == target
        } ?: return null
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        tm.callCapablePhoneAccounts.firstOrNull { it.id == sub.subscriptionId.toString() }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun InstructionLine(step: String, text: String) {
    val stepColor = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = stepColor, fontWeight = FontWeight.Bold)) {
                append("$step. ")
            }
            append(text)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
