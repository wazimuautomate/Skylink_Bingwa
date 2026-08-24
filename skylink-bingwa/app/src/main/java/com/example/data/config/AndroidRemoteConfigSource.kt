package com.example.data.config

import android.content.Context
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

/**
 * SharedPreferences + Retrofit implementation of [RemoteConfigSource].
 *
 * - `cached()` reads the last synced values from prefs, falling back to
 *   [AppConfig.DEFAULT] — so Till/Paybill/support are ALWAYS available offline.
 * - `fetch()` calls `get_config.php` on the server, persists the result, and
 *   returns it; any failure returns null and the cache stays intact.
 *
 * No secrets here — only the non-secret base URL + shared app-key header.
 */
class AndroidRemoteConfigSource(
    context: Context,
    baseUrl: String,
    appKey: String,
    enableLogging: Boolean = false
) : RemoteConfigSource {

    private val prefs = context.applicationContext
        .getSharedPreferences("mybingwa_remote_config", Context.MODE_PRIVATE)

    private val api: ConfigApi? = if (baseUrl.startsWith("https://")) {
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
            .create(ConfigApi::class.java)
    } else {
        null
    }

    override fun cached(): AppConfig {
        val d = AppConfig.DEFAULT
        return AppConfig(
            tillNumber = prefs.getString(KEY_TILL, d.tillNumber) ?: d.tillNumber,
            paybillNumber = prefs.getString(KEY_PAYBILL, d.paybillNumber) ?: d.paybillNumber,
            supportNumber = prefs.getString(KEY_SUPPORT, d.supportNumber) ?: d.supportNumber,
            supportWhatsapp = prefs.getString(KEY_WHATSAPP, d.supportWhatsapp) ?: d.supportWhatsapp
        )
    }

    override suspend fun fetch(): AppConfig? {
        val dto = try {
            api?.getConfig() ?: return null
        } catch (t: Throwable) {
            return null
        }
        val cached = cached()
        // Keep any field the server omits at its current cached value.
        val fresh = AppConfig(
            tillNumber = dto.tillNumber?.takeIf { it.isNotBlank() } ?: cached.tillNumber,
            paybillNumber = dto.paybillNumber?.takeIf { it.isNotBlank() } ?: cached.paybillNumber,
            supportNumber = dto.supportNumber?.takeIf { it.isNotBlank() } ?: cached.supportNumber,
            supportWhatsapp = dto.supportWhatsapp?.takeIf { it.isNotBlank() } ?: cached.supportWhatsapp
        )
        prefs.edit()
            .putString(KEY_TILL, fresh.tillNumber)
            .putString(KEY_PAYBILL, fresh.paybillNumber)
            .putString(KEY_SUPPORT, fresh.supportNumber)
            .putString(KEY_WHATSAPP, fresh.supportWhatsapp)
            .apply()
        return fresh
    }

    private companion object {
        const val KEY_TILL = "till_number"
        const val KEY_PAYBILL = "paybill_number"
        const val KEY_SUPPORT = "support_number"
        const val KEY_WHATSAPP = "support_whatsapp"
    }
}

/** Retrofit contract for the server config endpoint (`get_config.php`). */
interface ConfigApi {
    @GET("get_config.php")
    suspend fun getConfig(): ConfigDto
}

data class ConfigDto(
    @Json(name = "tillNumber") val tillNumber: String? = null,
    @Json(name = "paybillNumber") val paybillNumber: String? = null,
    @Json(name = "supportNumber") val supportNumber: String? = null,
    @Json(name = "supportWhatsapp") val supportWhatsapp: String? = null
)
