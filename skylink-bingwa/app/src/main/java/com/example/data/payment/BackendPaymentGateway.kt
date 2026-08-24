package com.example.data.payment

import com.example.core.payment.PaymentTxnState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The real online gateway: it talks to the Skylink Bingwa backend proxy over HTTPS. The
 * backend holds the Daraja credentials and the STK passkey and owns the callback,
 * so this class carries no secrets (CLAUDE.md §2/§10).
 *
 * Retries are safe because every call reuses the same `clientRequestId`
 * (idempotency, Plan.md §API). Transport failures surface as
 * [PaymentTransportException] which the checkout maps to a human-safe message.
 */
class BackendPaymentGateway(
    private val api: PaymentApi
) : PaymentGateway {

    override val name: String = "backend-proxy"

    override suspend fun initiateStkPush(request: StkPushRequest): StkPushResult {
        val dto = try {
            api.initiateStk(
                StkRequestDto(
                    offerId = request.offerId,
                    amountKsh = request.amountKsh,
                    payerMsisdn = request.payerMsisdn,
                    recipientMsisdn = request.recipientMsisdn,
                    clientRequestId = request.clientRequestId,
                    forSelf = request.forSelf
                )
            )
        } catch (io: IOException) {
            throw PaymentTransportException("Could not reach the payment service", io)
        } catch (t: Throwable) {
            throw PaymentTransportException("The payment service returned an unexpected error", t)
        }
        return dto.toResult()
    }

    override suspend fun queryStatus(query: StkStatusQuery): StkPushResult {
        val dto = try {
            api.status(query.clientRequestId, query.orderReference)
        } catch (io: IOException) {
            throw PaymentTransportException("Could not reach the payment service", io)
        } catch (t: Throwable) {
            throw PaymentTransportException("The payment service returned an unexpected error", t)
        }
        return dto.toResult()
    }

    private fun StkResponseDto.toResult(): StkPushResult {
        val state: PaymentTxnState = toTxnState()
        return StkPushResult(
            state = state,
            orderReference = orderReference,
            mpesaReceipt = mpesaReceipt.takeIf { state == PaymentTxnState.PAYMENT_CONFIRMED },
            customerMessage = customerMessage,
            errorCode = errorCode
        )
    }

    companion object {
        /**
         * Builds a gateway for [baseUrl] (which must end with `/`). The base URL and
         * [appKey] are non-secret configuration injected via BuildConfig — the app-key
         * is only a shared token that lets the app call our own endpoint; it is NOT a
         * Daraja credential (those never leave the server).
         */
        fun create(baseUrl: String, appKey: String, enableLogging: Boolean): BackendPaymentGateway {
            val logging = HttpLoggingInterceptor().apply {
                // Body logging is fine in debug only; never log payloads in release
                // builds (CLAUDE.md §10 "do not log complete payment payloads").
                level = if (enableLogging) HttpLoggingInterceptor.Level.BASIC
                else HttpLoggingInterceptor.Level.NONE
            }
            // Attach the shared app-key header so only our app can trigger an STK push.
            val appKeyInterceptor = okhttp3.Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("X-App-Key", appKey)
                        .build()
                )
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(appKeyInterceptor)
                .addInterceptor(logging)
                .build()
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            return BackendPaymentGateway(retrofit.create(PaymentApi::class.java))
        }
    }
}
