package com.example.data.catalogue

import com.example.core.model.DailyRule
import com.example.core.model.OfferCategory
import com.example.core.model.OfferItem
import com.example.core.model.parseTimeOfDayMinutes
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
 * Retrofit implementation of [RemoteCatalogueSource] backed by `get_offers.php`.
 * Maps server rows into [OfferItem]. Any failure returns null so the caller keeps
 * the local catalogue. No secrets — only the non-secret base URL + app-key header.
 */
class AndroidRemoteCatalogueSource(
    baseUrl: String,
    appKey: String,
    enableLogging: Boolean = false
) : RemoteCatalogueSource {

    private val api: OffersApi? = if (baseUrl.startsWith("https://")) {
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
            .create(OffersApi::class.java)
    } else {
        null
    }

    override suspend fun fetch(): List<OfferItem>? {
        val response = try {
            api?.getOffers() ?: return null
        } catch (t: Throwable) {
            return null
        }
        val mapped = response.offers.mapNotNull { it.toOfferItem() }
        return mapped.ifEmpty { null }
    }
}

interface OffersApi {
    @GET("get_offers.php")
    suspend fun getOffers(): OffersResponseDto
}

data class OffersResponseDto(
    @Json(name = "offers") val offers: List<OfferDto> = emptyList()
)

data class OfferDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "category") val category: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "price") val price: Int? = null,
    @Json(name = "validity") val validity: String? = null,
    @Json(name = "band") val band: String? = null,
    @Json(name = "dailyRule") val dailyRule: String? = null,
    // Safaricom's time-of-day selling window for this offer, "HH:MM" in Nairobi
    // time. Absent/blank on an offer with no restriction, and on any server that
    // predates the field — both read as "buyable any time".
    @Json(name = "availableFrom") val availableFrom: String? = null,
    @Json(name = "availableTo") val availableTo: String? = null
) {
    fun toOfferItem(): OfferItem? {
        val safeId = id?.takeIf { it.isNotBlank() } ?: return null
        val safeName = name?.takeIf { it.isNotBlank() } ?: return null
        val cat = runCatching { OfferCategory.valueOf((category ?: "DATA").uppercase()) }
            .getOrDefault(OfferCategory.DATA)
        val rule = if ((dailyRule ?: "").uppercase() == "ONCE_PER_DAY") {
            DailyRule.ONCE_PER_DAY
        } else {
            DailyRule.BUY_AGAIN_TODAY
        }
        return OfferItem(
            id = safeId,
            name = safeName,
            allowance = safeName,
            priceKsh = price ?: 0,
            validity = validity ?: "",
            validityBand = band ?: "",
            category = cat,
            dailyRule = rule,
            availableFromMinutes = parseTimeOfDayMinutes(availableFrom),
            availableToMinutes = parseTimeOfDayMinutes(availableTo),
            description = "$safeName of ${cat.label.lowercase()} valid ${validity ?: ""}."
        )
    }
}
