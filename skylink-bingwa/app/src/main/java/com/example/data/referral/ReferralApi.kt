package com.example.data.referral

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * The Earn screen's network layer.
 *
 * Same OkHttp configuration as the rest of the app (X-App-Key, 15s/30s timeouts,
 * BASIC logging in debug only) with one addition: endpoints that move money take
 * an `Authorization: Bearer` header per call.
 *
 * The token is passed per-call rather than pinned in an interceptor on purpose —
 * `summary` works with or without it, and it must be impossible to accidentally
 * attach a stale credential to a call that never needed one.
 */
interface ReferralApi {

    @GET("check_referral_code.php")
    suspend fun checkCode(@Query("code") code: String): CheckCodeDto

    @POST("referral_summary.php")
    suspend fun summary(
        @Header("Authorization") authorization: String?,
        @Body body: SummaryRequestDto
    ): SummaryDto

    @POST("otp_request.php")
    suspend fun requestOtp(@Body body: OtpRequestDto): OtpRequestResponseDto

    @POST("otp_verify.php")
    suspend fun verifyOtp(@Body body: OtpVerifyDto): OtpVerifyResponseDto

    @POST("withdraw.php")
    suspend fun withdraw(
        @Header("Authorization") authorization: String,
        @Body body: Map<String, String>
    ): WithdrawResponseDto

    companion object {
        fun create(baseUrl: String, appKey: String, enableLogging: Boolean): ReferralApi? {
            if (!baseUrl.startsWith("https://")) return null
            return runCatching {
                val logging = HttpLoggingInterceptor().apply {
                    level = if (enableLogging) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
                }
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(Interceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("X-App-Key", appKey).build())
                    })
                    .addInterceptor(logging)
                    .build()
                val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                Retrofit.Builder()
                    .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(ReferralApi::class.java)
            }.getOrNull()
        }
    }
}

data class CheckCodeDto(
    @Json(name = "valid") val valid: Boolean = false,
    @Json(name = "referrerName") val referrerName: String? = null
)

data class SummaryRequestDto(
    @Json(name = "msisdn") val msisdn: String
)

data class OtpRequestDto(
    @Json(name = "msisdn") val msisdn: String,
    @Json(name = "purpose") val purpose: String = "VERIFY_PAYOUT"
)

data class OtpRequestResponseDto(
    @Json(name = "status") val status: String = "",
    @Json(name = "errorCode") val errorCode: String? = null,
    @Json(name = "expiresInSeconds") val expiresInSeconds: Int = 600
)

data class OtpVerifyDto(
    @Json(name = "msisdn") val msisdn: String,
    @Json(name = "code") val code: String,
    @Json(name = "deviceId") val deviceId: String? = null
)

data class OtpVerifyResponseDto(
    @Json(name = "status") val status: String = "",
    @Json(name = "token") val token: String? = null,
    @Json(name = "payoutMsisdn") val payoutMsisdn: String? = null,
    @Json(name = "frozenUntil") val frozenUntil: String? = null,
    @Json(name = "errorCode") val errorCode: String? = null,
    @Json(name = "attemptsRemaining") val attemptsRemaining: Int? = null
)

data class WithdrawResponseDto(
    @Json(name = "status") val status: String = "",
    @Json(name = "withdrawalId") val withdrawalId: Long? = null,
    @Json(name = "amountCents") val amountCents: Long? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "errorCode") val errorCode: String? = null
)

data class RefereeDto(
    @Json(name = "name") val name: String = "",
    @Json(name = "joinedAt") val joinedAt: String? = null,
    @Json(name = "hasPurchased") val hasPurchased: Boolean = false,
    @Json(name = "purchasesCount") val purchasesCount: Int = 0,
    @Json(name = "earnedCents") val earnedCents: Long = 0
)

data class LedgerEntryDto(
    @Json(name = "type") val type: String = "",
    @Json(name = "amountCents") val amountCents: Long = 0,
    @Json(name = "maturesAt") val maturesAt: String? = null,
    @Json(name = "note") val note: String? = null,
    @Json(name = "createdAt") val createdAt: String? = null
)

data class WithdrawalDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "amountCents") val amountCents: Long = 0,
    @Json(name = "status") val status: String = "",
    @Json(name = "receipt") val receipt: String? = null,
    @Json(name = "detail") val detail: String? = null,
    @Json(name = "requestedAt") val requestedAt: String? = null,
    @Json(name = "resolvedAt") val resolvedAt: String? = null
)

data class SummaryDto(
    @Json(name = "status") val status: String = "",
    @Json(name = "code") val code: String = "",
    @Json(name = "shareMessage") val shareMessage: String = "",
    @Json(name = "balanceCents") val balanceCents: Long = 0,
    @Json(name = "availableCents") val availableCents: Long = 0,
    @Json(name = "pendingCents") val pendingCents: Long = 0,
    @Json(name = "lifetimeEarnedCents") val lifetimeEarnedCents: Long = 0,
    @Json(name = "lifetimePaidCents") val lifetimePaidCents: Long = 0,
    @Json(name = "minWithdrawCents") val minWithdrawCents: Long = 20000,
    @Json(name = "signupBonusCents") val signupBonusCents: Long = 0,
    @Json(name = "bonusNeedsPurchase") val bonusNeedsPurchase: Boolean = true,
    @Json(name = "referralsCount") val referralsCount: Int = 0,
    @Json(name = "verified") val verified: Boolean = false,
    @Json(name = "payoutMsisdn") val payoutMsisdn: String? = null,
    @Json(name = "accountStatus") val accountStatus: String = "ACTIVE",
    @Json(name = "accountStatusReason") val accountStatusReason: String = "",
    @Json(name = "payoutsEnabled") val payoutsEnabled: Boolean = false,
    @Json(name = "frozen") val frozen: Boolean = false,
    @Json(name = "hasInFlightWithdrawal") val hasInFlightWithdrawal: Boolean = false,
    @Json(name = "canWithdraw") val canWithdraw: Boolean = false,
    @Json(name = "referees") val referees: List<RefereeDto> = emptyList(),
    @Json(name = "ledger") val ledger: List<LedgerEntryDto> = emptyList(),
    @Json(name = "withdrawals") val withdrawals: List<WithdrawalDto> = emptyList()
)
