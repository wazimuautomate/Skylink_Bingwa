package com.example.feature.referral

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.referral.LedgerEntryDto
import com.example.data.referral.RefereeDto
import com.example.data.referral.ReferralState
import com.example.data.referral.WithdrawStep
import com.example.data.referral.WithdrawUiState
import com.example.data.referral.WithdrawalDto
import kotlin.math.min

// ---------------------------------------------------------------------------
// Refer & Earn.
//
// The design job on this screen is motivation without dishonesty. A referrer with
// two friends may be months from their first withdrawal, so the page has to make
// slow progress feel visible — a progress ring that actually moves, per-friend
// contribution, a clearly separated "held" figure — while never implying money is
// available when it is not.
//
// Every figure rendered here comes from the server. Nothing is computed locally,
// because a number the client could compute is a number an attacker could edit.
// ---------------------------------------------------------------------------

private val BrandDeepGreen = Color(0xFF006B27)
private val BrandBrightGreen = Color(0xFF18C964)
private val AccentGold = Color(0xFFFFB45C)

private fun Long.asKsh(): String {
    val shillings = this / 100.0
    return "Ksh " + String.format("%,.2f", shillings)
}

@Composable
fun ReferralScreen(
    state: ReferralState,
    withdrawState: WithdrawUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onWithdraw: () -> Unit,
    onSubmitOtp: (String) -> Unit,
    onResendOtp: () -> Unit,
    onDismissWithdraw: () -> Unit
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding()
    ) {
        ReferralTopBar(onBack = onBack, onRefresh = onRefresh, loading = state.loading)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            if (!state.configured) {
                NoticeCard(
                    title = "Referrals are not set up yet",
                    body = "This app build has no server configured, so there is nothing to earn against yet.",
                    tone = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Spacer(Modifier.height(8.dp))

            CodeHeroCard(
                code = state.code,
                shareMessage = state.shareMessage,
                bonusCents = state.signupBonusCents,
                context = context
            )

            Spacer(Modifier.height(16.dp))

            EarningsCard(state = state)

            Spacer(Modifier.height(16.dp))

            WithdrawSection(
                state = state,
                withdrawState = withdrawState,
                onWithdraw = onWithdraw,
                onSubmitOtp = onSubmitOtp,
                onResendOtp = onResendOtp,
                onDismiss = onDismissWithdraw
            )

            Spacer(Modifier.height(20.dp))

            HowItWorks(bonusCents = state.signupBonusCents, bonusNeedsPurchase = state.bonusNeedsPurchase)

            Spacer(Modifier.height(20.dp))

            FriendsSection(referees = state.referees, bonusNeedsPurchase = state.bonusNeedsPurchase)

            if (state.withdrawals.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                WithdrawalHistory(state.withdrawals)
            }

            if (state.ledger.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                EarningsHistory(state.ledger)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/* ------------------------------------------------------------------ top bar */

@Composable
private fun ReferralTopBar(onBack: () -> Unit, onRefresh: () -> Unit, loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("referral_back")) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            text = "Refer & Earn",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).padding(end = 12.dp),
                strokeWidth = 2.dp,
                color = BrandBrightGreen
            )
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/* ---------------------------------------------------------------- hero card */

/**
 * The code, made unmissable.
 *
 * A slow sheen crosses the card rather than anything blinking: it draws the eye to
 * the one thing on the page the customer is meant to act on, without turning a
 * money screen into a game.
 */
@Composable
private fun CodeHeroCard(code: String, shareMessage: String, bonusCents: Long, context: Context) {
    var copied by remember { mutableStateOf(false) }
    val transition = rememberInfiniteTransition(label = "sheen")
    val sheen by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sheenX"
    )
    val copyScale by animateFloatAsState(
        targetValue = if (copied) 1.06f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "copyScale"
    )

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1600)
            copied = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(BrandDeepGreen, BrandBrightGreen)))
    ) {
        // The sheen itself — a soft diagonal highlight sweeping left to right.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent),
                        start = Offset(sheen * 900f, 0f),
                        end = Offset(sheen * 900f + 320f, 320f)
                    )
                )
        )

        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "YOUR REFERRAL CODE",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.82f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .scale(copyScale)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .clickable(enabled = code.isNotBlank()) {
                            copyToClipboard(context, code)
                            copied = true
                        }
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                        .testTag("referral_code_copy")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (code.isNotBlank()) code else "————",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = if (copied) "Copied" else "Copy code",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(visible = copied, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = "Copied — now paste it to a friend",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
            if (!copied) {
                Text(
                    text = "Tap the code to copy it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { shareCode(context, shareMessage.ifBlank { "Use my Skylink Bingwa code $code" }) },
                enabled = code.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = BrandDeepGreen
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("referral_share")
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share with friends", fontWeight = FontWeight.Bold)
            }

            if (bonusCents > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "You get ${bonusCents.asKsh()} when a friend joins with your code, " +
                        "then commission on everything they buy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.92f)
                )
            }
        }
    }
}

/* ------------------------------------------------------------ earnings card */

@Composable
private fun EarningsCard(state: ReferralState) {
    val target = state.minWithdrawCents.coerceAtLeast(1)
    val rawProgress = state.availableCents.toFloat() / target.toFloat()
    val progress by animateFloatAsState(
        targetValue = min(1f, rawProgress.coerceAtLeast(0f)),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "progress"
    )
    val reached = state.availableCents >= state.minWithdrawCents

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Text(
            text = "READY TO WITHDRAW",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.availableCents.asKsh(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = if (reached) BrandBrightGreen else MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(16.dp))

        // The progress bar is the motivation engine: with a slow-filling balance it
        // is the only thing that shows movement between one purchase and the next.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(BrandDeepGreen, BrandBrightGreen)))
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (reached) {
                "You have reached the ${state.minWithdrawCents.asKsh()} minimum."
            } else {
                "${(state.minWithdrawCents - state.availableCents).asKsh()} to go until you can withdraw " +
                    "(minimum ${state.minWithdrawCents.asKsh()})."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.pendingCents > 0) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentGold.copy(alpha = 0.14f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = AccentGold, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "${state.pendingCents.asKsh()} on the way",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.bonusNeedsPurchase) {
                            "Joining bonuses unlock once that friend makes their first purchase. " +
                                "Commission unlocks a day after it is earned."
                        } else {
                            "Commission unlocks a day after it is earned."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniStat("Friends", state.referees.size.toString())
            MiniStat("Earned all time", state.lifetimeEarnedCents.asKsh())
            MiniStat("Paid out", state.lifetimePaidCents.asKsh())
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ------------------------------------------------------------- withdrawal */

@Composable
private fun WithdrawSection(
    state: ReferralState,
    withdrawState: WithdrawUiState,
    onWithdraw: () -> Unit,
    onSubmitOtp: (String) -> Unit,
    onResendOtp: () -> Unit,
    onDismiss: () -> Unit
) {
    var otp by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        when (withdrawState.step) {
            WithdrawStep.ENTERING_CODE -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, BrandBrightGreen.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Text(
                        "Confirm it's you",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        withdrawState.info ?: "Enter the 6-digit code we sent you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { if (it.length <= 6) otp = it.filter { ch -> ch.isDigit() } },
                        label = { Text("6-digit code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().testTag("referral_otp_field")
                    )
                    withdrawState.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { onSubmitOtp(otp) },
                        enabled = otp.length == 6 && !withdrawState.busy,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBrightGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (withdrawState.busy) "Checking…" else "Verify and withdraw", fontWeight = FontWeight.Bold) }
                    Row {
                        TextButton(onClick = onResendOtp, enabled = !withdrawState.busy) { Text("Send a new code") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { otp = ""; onDismiss() }) { Text("Cancel") }
                    }
                }
            }

            WithdrawStep.SUBMITTING -> {
                NoticeCard(
                    title = "Sending your money",
                    body = "Talking to M-Pesa. Do not close the app.",
                    tone = BrandBrightGreen,
                    showSpinner = true
                )
            }

            WithdrawStep.SENT -> {
                NoticeCard(
                    title = "On its way",
                    body = withdrawState.info
                        ?: "We are sending your money to M-Pesa. You will get an SMS when it lands.",
                    tone = BrandBrightGreen,
                    action = "Done" to { otp = ""; onDismiss() }
                )
            }

            else -> {
                withdrawState.error?.let {
                    NoticeCard(title = "Could not withdraw", body = it, tone = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                }
                withdrawState.info?.let {
                    NoticeCard(title = "Heads up", body = it, tone = AccentGold)
                    Spacer(Modifier.height(12.dp))
                }
                WithdrawButton(state = state, onWithdraw = onWithdraw)
            }
        }
    }
}

/**
 * One button, and copy that tells the truth about why it is disabled.
 *
 * "Withdraw" greyed out with no explanation is the fastest way to generate a
 * support call, so every blocked state names its own reason.
 */
@Composable
private fun WithdrawButton(state: ReferralState, onWithdraw: () -> Unit) {
    val reason: String? = when {
        state.accountStatus != "ACTIVE" -> "This account is under review. Please contact support."
        !state.payoutsEnabled -> "Withdrawals are paused right now. Your balance is safe and still growing."
        state.frozen -> "Withdrawals are paused for 48 hours after a payout number change."
        state.hasInFlightWithdrawal -> "You already have a withdrawal on the way."
        state.availableCents < state.minWithdrawCents ->
            "Reach ${state.minWithdrawCents.asKsh()} to withdraw to M-Pesa."
        else -> null
    }

    Button(
        onClick = onWithdraw,
        enabled = reason == null,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandBrightGreen,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .testTag("referral_withdraw")
    ) {
        Icon(Icons.Rounded.Payments, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (reason == null) "Withdraw ${state.availableCents.asKsh()} to M-Pesa" else "Withdraw to M-Pesa",
            fontWeight = FontWeight.Bold
        )
    }

    if (reason != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    } else if (state.payoutMsisdn != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Goes to ${state.payoutMsisdn}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/* -------------------------------------------------------------- explainers */

@Composable
private fun HowItWorks(bonusCents: Long, bonusNeedsPurchase: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "How it works",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        StepRow(1, "Share your code", "Send it to friends and family on WhatsApp or SMS.")
        StepRow(
            2,
            if (bonusCents > 0) "They join and you get ${bonusCents.asKsh()}" else "They join with your code",
            if (bonusCents > 0 && bonusNeedsPurchase) {
                "The bonus unlocks once they make their first purchase."
            } else {
                "They enter your code when they set up the app."
            }
        )
        StepRow(3, "Earn on every bundle", "You get commission each time they buy — for as long as they keep buying.")
        StepRow(4, "Cash out to M-Pesa", "Once you pass the minimum, withdraw straight to your own number.")
    }
}

@Composable
private fun StepRow(number: Int, title: String, body: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(BrandBrightGreen.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = BrandDeepGreen
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ----------------------------------------------------------------- friends */

@Composable
private fun FriendsSection(referees: List<RefereeDto>, bonusNeedsPurchase: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Group, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Friends you referred (${referees.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(10.dp))

        if (referees.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Nobody has used your code yet", style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Share it with one person today — you earn every time they buy a bundle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Column
        }

        referees.forEach { friend ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (friend.hasPurchased) BrandBrightGreen.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = friend.name.take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (friend.hasPurchased) BrandDeepGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(friend.name.ifBlank { "Friend" }, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = if (friend.hasPurchased) {
                            "${friend.purchasesCount} purchase${if (friend.purchasesCount == 1) "" else "s"}"
                        } else if (bonusNeedsPurchase) {
                            "Bonus unlocks on their first purchase"
                        } else {
                            "Not bought yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = friend.earnedCents.asKsh(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (friend.earnedCents > 0) BrandDeepGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/* ----------------------------------------------------------------- history */

@Composable
private fun WithdrawalHistory(withdrawals: List<WithdrawalDto>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Withdrawals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        withdrawals.forEach { w ->
            // UNKNOWN is shown as "checking with M-Pesa", never as success and never
            // as failure. Claiming either while the server genuinely does not know
            // would be a lie the customer acts on.
            val (label, tone) = when (w.status) {
                "PAID" -> "Sent" to BrandBrightGreen
                "FAILED" -> "Failed — money returned" to MaterialTheme.colorScheme.error
                "UNKNOWN" -> "Checking with M-Pesa" to AccentGold
                else -> "In progress" to AccentGold
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(w.amountCents.asKsh(), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = w.receipt?.takeIf { it.isNotBlank() } ?: (w.requestedAt ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = tone)
            }
        }
    }
}

@Composable
private fun EarningsHistory(ledger: List<LedgerEntryDto>) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) ledger else ledger.take(5)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Earnings history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))

        shown.forEach { entry ->
            val label = when (entry.type) {
                "SIGNUP_BONUS" -> "Friend joined"
                "EARN" -> "Commission"
                "WITHDRAW_HOLD" -> "Withdrawal"
                "WITHDRAW_REFUND" -> "Withdrawal returned"
                "WITHDRAW_SETTLE" -> "Withdrawal completed"
                "REVERSAL" -> "Reversed"
                "ADJUST" -> "Adjustment"
                else -> entry.type
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    entry.createdAt?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (entry.amountCents != 0L) {
                    Text(
                        text = (if (entry.amountCents > 0) "+" else "") + entry.amountCents.asKsh(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (entry.amountCents > 0) BrandDeepGreen else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (ledger.size > 5) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show all ${ledger.size}")
            }
        }
    }
}

/* ------------------------------------------------------------------ shared */

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    tone: Color,
    showSpinner: Boolean = false,
    action: Pair<String, () -> Unit>? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tone.copy(alpha = 0.10f))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = tone)
            Spacer(Modifier.width(12.dp))
        } else {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.let { (label, onClick) ->
            TextButton(onClick = onClick) { Text(label) }
        }
    }
}

private fun copyToClipboard(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Skylink Bingwa referral code", code))
}

private fun shareCode(context: Context, message: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    context.startActivity(Intent.createChooser(intent, "Share your referral code"))
}
