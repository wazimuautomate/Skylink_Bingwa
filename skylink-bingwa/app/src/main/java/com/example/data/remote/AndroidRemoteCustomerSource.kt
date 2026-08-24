package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * Announces a customer to the seller's own backend, ONCE per install.
 *
 * The name and Safaricom number the customer types during onboarding live on their
 * phone and drive the whole app from there; this sends one copy to the seller so
 * they know who their customers are. It is deliberately the only thing about a
 * customer that ever leaves the device — no purchases, no favourites, no behaviour
 * (CLAUDE.md §10).
 *
 * The caller ([com.example.data.fake.FakeBingwaRepositoryImpl.registerCustomer])
 * remembers a success so this never fires twice; a failure simply leaves that flag
 * unset and is retried on a later launch, which is why the endpoint is idempotent
 * on the number.
 */
interface RemoteCustomerSource {
    /** True only when the backend confirmed the registration. Never throws. */
    suspend fun register(name: String, msisdn: String, appVersion: String, fcmToken: String? = null): Boolean
}

class AndroidRemoteCustomerSource(
    baseUrl: String,
    appKey: String,
    enableLogging: Boolean = false
) : RemoteCustomerSource {

    private val api: CustomerApi? = if (baseUrl.startsWith("https://")) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-App-Key", appKey).build())
            })
            .build()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CustomerApi::class.java)
    } else {
        null
    }

    override suspend fun register(name: String, msisdn: String, appVersion: String, fcmToken: String?): Boolean {
        val response = try {
            api?.register(RegisterCustomerDto(name = name, msisdn = msisdn, appVersion = appVersion, fcmToken = fcmToken))
                ?: return false
        } catch (t: Throwable) {
            // Offline, a timeout, a 4xx/5xx — all the same to the caller: not yet
            // registered, try again on a later launch.
            return false
        }
        return response.status.equals("REGISTERED", ignoreCase = true)
    }
}

interface CustomerApi {
    @POST("register_user.php")
    suspend fun register(@Body body: RegisterCustomerDto): RegisterCustomerResponseDto
}

data class RegisterCustomerDto(
    @Json(name = "name") val name: String,
    @Json(name = "msisdn") val msisdn: String,
    @Json(name = "appVersion") val appVersion: String,
    @Json(name = "fcm_token") val fcmToken: String? = null
)

data class RegisterCustomerResponseDto(
    @Json(name = "status") val status: String = "",
    @Json(name = "errorCode") val errorCode: String? = null
)
