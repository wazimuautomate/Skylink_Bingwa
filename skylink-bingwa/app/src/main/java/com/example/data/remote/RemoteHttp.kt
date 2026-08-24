package com.example.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * One place that builds the Retrofit/OkHttp stack every sync source uses, so the four
 * sources in this package cannot drift apart in timeouts, headers or logging policy.
 *
 * It is byte-for-byte the same configuration as
 * [com.example.data.catalogue.AndroidRemoteBillboardSource] and
 * [com.example.data.payment.BackendPaymentGateway]:
 *  - `X-App-Key` header on every request (a non-secret shared token that lets only our
 *    app call our own endpoints; it is NOT a Daraja credential — those never leave the
 *    server, CLAUDE.md §10).
 *  - 15 s connect / 30 s read: generous enough for a slow Kenyan mobile connection,
 *    short enough that a background sync cannot hold a wakelock for minutes.
 *  - Moshi REFLECTION (no codegen/KSP configured in this module).
 *  - Logging at BASIC only, and only in debug builds — never response bodies, so no
 *    phone number, receipt or payload can reach logcat in release.
 *
 * The base URL and app key are always injected by the caller from BuildConfig. Nothing
 * in this package hardcodes a URL, a key, a phone number, a Till or a Paybill.
 */
internal object RemoteHttp {

    /**
     * Build an API of type [T], or null when [baseUrl] is not a usable https endpoint.
     * A null API is the "not configured" state: every source below then returns null
     * from `fetch()` and the app keeps its cached/seeded content.
     */
    fun <T> createApi(
        baseUrl: String,
        appKey: String,
        enableLogging: Boolean,
        service: Class<T>
    ): T? {
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
                .create(service)
        }.getOrNull()
    }
}
