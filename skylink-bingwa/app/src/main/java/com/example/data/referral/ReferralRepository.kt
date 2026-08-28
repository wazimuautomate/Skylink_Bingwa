package com.example.data.referral

import android.content.Context
import com.example.BuildConfig
import com.example.core.device.DeviceIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Earn screen renders. Everything money-related comes from the server. */
data class ReferralState(
    val loading: Boolean = false,
    val configured: Boolean = true,
    val loadedOnce: Boolean = false,
    val offline: Boolean = false,
    val code: String = "",
    val shareMessage: String = "",
    val balanceCents: Long = 0,
    val availableCents: Long = 0,
    val pendingCents: Long = 0,
    val lifetimeEarnedCents: Long = 0,
    val lifetimePaidCents: Long = 0,
    val minWithdrawCents: Long = 20_000,
    val signupBonusCents: Long = 0,
    val bonusNeedsPurchase: Boolean = true,
    val verified: Boolean = false,
    val payoutMsisdn: String? = null,
    val accountStatus: String = "ACTIVE",
    val payoutsEnabled: Boolean = false,
    val frozen: Boolean = false,
    val hasInFlightWithdrawal: Boolean = false,
    val canWithdraw: Boolean = false,
    val referees: List<RefereeDto> = emptyList(),
    val ledger: List<LedgerEntryDto> = emptyList(),
    val withdrawals: List<WithdrawalDto> = emptyList(),
    val message: String? = null
)

/** Where the verify-and-withdraw flow currently is. */
enum class WithdrawStep { IDLE, NEEDS_CODE, ENTERING_CODE, SUBMITTING, SENT }

data class WithdrawUiState(
    val step: WithdrawStep = WithdrawStep.IDLE,
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null
)

/**
 * The Earn screen's single source of truth.
 *
 * The app NEVER computes a balance, a threshold or an eligibility rule — it renders
 * what the server says. That is not laziness: any figure the client could compute
 * is a figure an attacker could edit, and the whole point of the withdrawal design
 * is that the phone supplies no amount and no destination.
 */
class ReferralRepository(context: Context) {

    private val appContext = context.applicationContext
    private val store = ReferralStore(appContext)

    private val api: ReferralApi? = ReferralApi.create(
        baseUrl = BuildConfig.PAYMENTS_BASE_URL,
        appKey = BuildConfig.PAYMENTS_APP_KEY,
        enableLogging = BuildConfig.DEBUG
    )

    private val _state = MutableStateFlow(ReferralState(configured = api != null))
    val state: StateFlow<ReferralState> = _state.asStateFlow()

    private val _withdraw = MutableStateFlow(WithdrawUiState())
    val withdraw: StateFlow<WithdrawUiState> = _withdraw.asStateFlow()

    /* ------------------------------------------------------------- onboarding */

    /** Hold the code the customer typed until registration actually reaches the server. */
    suspend fun rememberPendingCode(code: String?) = store.setPendingReferralCode(code?.uppercase())

    suspend fun pendingCode(): String? = store.pendingReferralCode()

    /** Live validation while typing. Null when we could not check (offline, or not configured). */
    suspend fun checkCode(code: String): String? {
        val client = api ?: return null
        return try {
            val res = client.checkCode(code.uppercase())
            if (res.valid) res.referrerName ?: "a Skylink Bingwa customer" else null
        } catch (t: Throwable) {
            null
        }
    }

    /* ---------------------------------------------------------------- summary */

    suspend fun refresh(msisdn: String) {
        val client = api
        if (client == null) {
            _state.value = _state.value.copy(configured = false, loading = false, loadedOnce = true)
            return
        }
        // Show a real, previously-fetched code the instant this screen opens, before
        // the network round-trip even starts — and it is what is left on screen if
        // that round-trip then fails.
        if (_state.value.code.isBlank()) {
            store.myCode()?.let { cached -> _state.value = _state.value.copy(code = cached) }
        }
        _state.value = _state.value.copy(loading = true, offline = false)

        val token = store.authToken()
        val dto = try {
            client.summary(
                authorization = token?.let { "Bearer $it" },
                body = SummaryRequestDto(msisdn = normalise(msisdn))
            )
        } catch (t: Throwable) {
            // Keep whatever was last shown rather than blanking the screen — the
            // cached code is still shareable with no network at all.
            _state.value = _state.value.copy(loading = false, offline = true, loadedOnce = true)
            return
        }

        if (!dto.status.equals("OK", ignoreCase = true)) {
            _state.value = _state.value.copy(loading = false, loadedOnce = true, offline = false)
            return
        }

        store.setMyCode(dto.code)
        dto.payoutMsisdn?.let { store.setPayoutMsisdn(it) }

        _state.value = ReferralState(
            loading = false,
            configured = true,
            loadedOnce = true,
            offline = false,
            code = dto.code,
            shareMessage = dto.shareMessage,
            balanceCents = dto.balanceCents,
            availableCents = dto.availableCents,
            pendingCents = dto.pendingCents,
            lifetimeEarnedCents = dto.lifetimeEarnedCents,
            lifetimePaidCents = dto.lifetimePaidCents,
            minWithdrawCents = dto.minWithdrawCents,
            signupBonusCents = dto.signupBonusCents,
            bonusNeedsPurchase = dto.bonusNeedsPurchase,
            verified = dto.verified,
            payoutMsisdn = dto.payoutMsisdn,
            accountStatus = dto.accountStatus,
            payoutsEnabled = dto.payoutsEnabled,
            frozen = dto.frozen,
            hasInFlightWithdrawal = dto.hasInFlightWithdrawal,
            canWithdraw = dto.canWithdraw,
            referees = dto.referees,
            ledger = dto.ledger,
            withdrawals = dto.withdrawals
        )
    }

    /** The cached code, so the screen has something real to show before the first fetch. */
    suspend fun cachedCode(): String? = store.myCode()

    /* -------------------------------------------------------------- withdrawal */

    /**
     * Start a withdrawal.
     *
     * If there is no bearer token yet this asks for an SMS code first. Verification
     * is lazy by design: a customer who only wants to see and share their code is
     * never asked for it, and the business is never charged for the SMS.
     */
    suspend fun beginWithdrawal(msisdn: String) {
        val client = api ?: return
        if (store.authToken() == null) {
            requestOtp(msisdn)
            return
        }
        submitWithdrawal()
    }

    suspend fun requestOtp(msisdn: String) {
        val client = api ?: return
        _withdraw.value = WithdrawUiState(step = WithdrawStep.NEEDS_CODE, busy = true)
        val res = try {
            client.requestOtp(OtpRequestDto(msisdn = normalise(msisdn)))
        } catch (t: Throwable) {
            _withdraw.value = WithdrawUiState(
                step = WithdrawStep.IDLE,
                error = "Could not send the code. Check your connection and try again."
            )
            return
        }

        if (res.status.equals("SENT", ignoreCase = true)) {
            _withdraw.value = WithdrawUiState(
                step = WithdrawStep.ENTERING_CODE,
                info = "We sent a 6-digit code to your number. It expires in 10 minutes."
            )
        } else {
            _withdraw.value = WithdrawUiState(step = WithdrawStep.IDLE, error = otpError(res.errorCode))
        }
    }

    suspend fun verifyOtp(msisdn: String, code: String) {
        val client = api ?: return
        _withdraw.value = _withdraw.value.copy(busy = true, error = null)

        val res = try {
            client.verifyOtp(
                OtpVerifyDto(
                    msisdn = normalise(msisdn),
                    code = code.filter { it.isDigit() },
                    deviceId = DeviceIdentity.stableId(appContext)
                )
            )
        } catch (t: Throwable) {
            _withdraw.value = _withdraw.value.copy(busy = false, error = "Could not verify the code. Try again.")
            return
        }

        if (res.status.equals("VERIFIED", ignoreCase = true) && !res.token.isNullOrBlank()) {
            store.setAuthToken(res.token)
            res.payoutMsisdn?.let { store.setPayoutMsisdn(it) }
            if (res.frozenUntil != null) {
                // Honest, not hidden: a number change deliberately pauses payouts.
                _withdraw.value = WithdrawUiState(
                    step = WithdrawStep.IDLE,
                    info = "Number verified. For your security, withdrawals are paused for 48 hours " +
                        "after changing your payout number."
                )
            } else {
                submitWithdrawal()
            }
            return
        }

        val remaining = res.attemptsRemaining
        _withdraw.value = _withdraw.value.copy(
            busy = false,
            error = when (res.errorCode) {
                "WRONG_CODE" -> if (remaining != null && remaining > 0) {
                    "That code is not right. $remaining attempt${if (remaining == 1) "" else "s"} left."
                } else {
                    "That code is not right."
                }
                "TOO_MANY_ATTEMPTS" -> "Too many attempts. Request a new code."
                "NO_ACTIVE_CODE" -> "That code has expired. Request a new one."
                else -> "Could not verify the code. Try again."
            }
        )
    }

    private suspend fun submitWithdrawal() {
        val client = api ?: return
        val token = store.authToken()
        if (token == null) {
            _withdraw.value = WithdrawUiState(step = WithdrawStep.IDLE, error = "Verify your number first.")
            return
        }

        _withdraw.value = WithdrawUiState(step = WithdrawStep.SUBMITTING, busy = true)

        // No amount and no destination are sent. The server pays the whole
        // available balance to the number it verified, which is the single most
        // important reason a forged request cannot redirect anyone's money.
        val res = try {
            client.withdraw(authorization = "Bearer $token", body = emptyMap())
        } catch (t: Throwable) {
            _withdraw.value = WithdrawUiState(
                step = WithdrawStep.IDLE,
                error = "We could not reach M-Pesa just now. Your balance is untouched — try again shortly."
            )
            return
        }

        if (res.status.equals("REQUESTED", ignoreCase = true)) {
            _withdraw.value = WithdrawUiState(
                step = WithdrawStep.SENT,
                info = res.message ?: "Sending your money to M-Pesa. This usually takes under a minute."
            )
            return
        }

        if (res.errorCode == "UNAUTHENTICATED") {
            // The token was revoked (they re-verified elsewhere). Start over cleanly.
            store.setAuthToken(null)
            _withdraw.value = WithdrawUiState(step = WithdrawStep.IDLE, error = "Please verify your number again.")
            return
        }

        _withdraw.value = WithdrawUiState(step = WithdrawStep.IDLE, error = withdrawError(res.errorCode))
    }

    fun dismissWithdrawal() {
        _withdraw.value = WithdrawUiState()
    }

    /* ------------------------------------------------------------------ text */

    private fun otpError(code: String?): String = when (code) {
        "TOO_MANY_REQUESTS" -> "Too many code requests. Please wait a while and try again."
        "SMS_UNAVAILABLE" -> "We could not send the SMS just now. Please try again in a few minutes."
        "UNKNOWN_CUSTOMER" -> "We do not have this number registered yet."
        else -> "Could not send the code. Please try again."
    }

    private fun withdrawError(code: String?): String = when (code) {
        "BELOW_MINIMUM" -> "Your withdrawable balance has not reached the minimum yet."
        "PAYOUTS_DISABLED" -> "Withdrawals are temporarily paused. Your balance is safe."
        "WITHDRAWAL_IN_PROGRESS" -> "You already have a withdrawal on the way. Wait for it to finish."
        "COOLDOWN_ACTIVE" -> "You have already withdrawn recently. Try again later."
        "PAYOUT_FROZEN" -> "Withdrawals are paused for 48 hours after a payout number change."
        "NOT_VERIFIED" -> "Verify your number before withdrawing."
        "DAILY_CAP_REACHED" -> "Withdrawals are busy right now. Please try again later today."
        "ACCOUNT_PAYOUT_BLOCKED", "ACCOUNT_BANNED", "ACCOUNT_EARN_BLOCKED" ->
            "This account is under review. Please contact support."
        else -> "We could not start the withdrawal. Your balance is untouched."
    }

    private fun normalise(msisdn: String): String {
        val digits = msisdn.filter { it.isDigit() }
        val tail = digits.takeLast(9)
        return "254$tail"
    }
}
