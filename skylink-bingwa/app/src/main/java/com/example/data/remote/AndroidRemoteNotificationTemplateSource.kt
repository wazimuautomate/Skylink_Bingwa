package com.example.data.remote

import com.example.core.notifications.engine.NotificationTemplate
import com.example.core.notifications.engine.NotificationTemplateSet
import com.example.core.notifications.engine.RemoteNotificationTemplateSource
import com.squareup.moshi.Json
import retrofit2.http.GET

/**
 * Retrofit implementation of [RemoteNotificationTemplateSource] backed by
 * `get_notification_templates.php`.
 *
 * Templates are WORDING, not sends: the on-device composer decides whether local
 * conditions allow a message and then picks one variant. Syncing them lets the owner
 * refresh notification copy without an app release.
 *
 * Any failure returns null and the app keeps its in-APK seed / last synced set, so the
 * notification engine always has something to say — or, better, stays silent — but is
 * never left with an empty template set because a request failed (CLAUDE.md §9).
 */
class AndroidRemoteNotificationTemplateSource(
    baseUrl: String,
    appKey: String,
    enableLogging: Boolean = false
) : RemoteNotificationTemplateSource {

    private val api: NotificationTemplatesApi? =
        RemoteHttp.createApi(baseUrl, appKey, enableLogging, NotificationTemplatesApi::class.java)

    override suspend fun fetch(): NotificationTemplateSet? {
        val response = try {
            api?.getNotificationTemplates() ?: return null
        } catch (t: Throwable) {
            return null
        }
        return response.toTemplateSet()
    }
}

interface NotificationTemplatesApi {
    @GET("get_notification_templates.php")
    suspend fun getNotificationTemplates(): NotificationTemplateSetDto
}

data class NotificationTemplateSetDto(
    @Json(name = "version") val version: Int? = null,
    @Json(name = "templates") val templates: List<NotificationTemplateDto?>? = null
) {
    fun toTemplateSet(): NotificationTemplateSet = NotificationTemplateSet(
        version = version ?: 0,
        templates = templates.orEmpty().mapNotNull { it?.toTemplate() }
    )
}

data class NotificationTemplateDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "category") val category: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "body") val body: String? = null,
    @Json(name = "weight") val weight: Int? = null,
    @Json(name = "enabled") val enabled: Boolean? = null
) {
    /**
     * Drops a template with no id or no body — an empty notification is worse than no
     * notification. [category] stays a raw String (as the domain type intends), so a
     * category this build does not know is simply never selected rather than crashing.
     */
    fun toTemplate(): NotificationTemplate? {
        val safeId = id?.takeIf { it.isNotBlank() } ?: return null
        val safeBody = body?.takeIf { it.isNotBlank() } ?: return null
        return NotificationTemplate(
            id = safeId,
            category = category?.trim()?.uppercase().orEmpty(),
            title = title.orEmpty(),
            body = safeBody,
            // The domain treats anything below 1 as 1; normalise here too so a bad
            // server value cannot make a template unselectable in a subtle way.
            weight = (weight ?: 1).coerceAtLeast(1),
            enabled = enabled ?: true
        )
    }
}
