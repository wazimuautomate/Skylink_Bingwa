package com.example.data.remote

import com.example.core.notifications.engine.RemoteNotification
import com.example.core.notifications.engine.RemoteNotificationSource
import com.squareup.moshi.Json
import retrofit2.http.GET

/**
 * Retrofit implementation of [RemoteNotificationSource] backed by
 * `get_app_notifications.php` — the notifications the owner published in the admin
 * console.
 *
 * These are CONTENT, not sends. Nothing here bypasses the on-device rules: the
 * notification engine still applies the permission state, quiet hours, frequency caps,
 * recent-purchase suppression and deduplication required by CLAUDE.md §9 before any of
 * this is ever shown.
 *
 * Any failure returns null and the app keeps its cached list — an empty list from a
 * successful call means "the owner has published nothing", which is different from a
 * failure and is returned as an empty list, not null.
 */
class AndroidRemoteNotificationSource(
    baseUrl: String,
    appKey: String,
    enableLogging: Boolean = false
) : RemoteNotificationSource {

    private val api: AppNotificationsApi? =
        RemoteHttp.createApi(baseUrl, appKey, enableLogging, AppNotificationsApi::class.java)

    override suspend fun fetch(): List<RemoteNotification>? {
        val response = try {
            api?.getAppNotifications() ?: return null
        } catch (t: Throwable) {
            return null
        }
        return response.notifications.orEmpty().mapNotNull { it?.toNotification() }
    }
}

interface AppNotificationsApi {
    @GET("get_app_notifications.php")
    suspend fun getAppNotifications(): AppNotificationsResponseDto
}

data class AppNotificationsResponseDto(
    @Json(name = "notifications") val notifications: List<RemoteNotificationDto?>? = null
)

data class RemoteNotificationDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "category") val category: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "body") val body: String? = null,
    @Json(name = "deepLinkRoute") val deepLinkRoute: String? = null,
    /** Epoch millis. Null/absent → "active from the beginning of time". */
    @Json(name = "startsAt") val startsAt: Long? = null,
    /** Epoch millis. Null/absent → "never expires". */
    @Json(name = "endsAt") val endsAt: Long? = null,
    @Json(name = "priority") val priority: Int? = null
) {
    /**
     * Drops a notification with no id or no body. A malformed window (end before start)
     * is left exactly as published — the engine's own expired-content handling decides
     * what to do with it; silently "repairing" server data here would hide an admin
     * mistake instead of surfacing it.
     */
    fun toNotification(): RemoteNotification? {
        val safeId = id?.takeIf { it.isNotBlank() } ?: return null
        val safeBody = body?.takeIf { it.isNotBlank() } ?: return null
        return RemoteNotification(
            id = safeId,
            category = category?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "GENERAL",
            title = title.orEmpty(),
            body = safeBody,
            deepLinkRoute = deepLinkRoute?.trim()?.takeIf { it.isNotEmpty() } ?: "home",
            startsAt = startsAt ?: 0L,
            endsAt = endsAt ?: Long.MAX_VALUE,
            priority = priority ?: 0
        )
    }
}
