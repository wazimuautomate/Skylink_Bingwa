package com.example.core.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ui.theme.BrandGreen
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin, honest wrapper over [NotificationManagerCompat] for posting Skylink Bingwa
 * notifications. Construct manually with any [Context] — no Hilt.
 *
 * Every notification uses the monochrome brand status icon
 * ([R.drawable.ic_stat_skylink_bingwa]), the brand green accent, auto-cancel, and a
 * tap [PendingIntent] that launches the app with an [EXTRA_DEEP_LINK_ROUTE]
 * string extra the integration step reads to deep-link.
 *
 * This class NEVER requests permissions and NEVER posts a banned delivery claim
 * (CLAUDE.md §7) or a forbidden usage-recommender claim (§8). On API 33+ it
 * silently declines to post when POST_NOTIFICATIONS is not granted — the
 * integration step owns the in-app explanation and the runtime request.
 */
class AppNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** In-process de-dup: stable ids already posted this process are not reposted. */
    private val postedStableIds = ConcurrentHashMap.newKeySet<String>()

    private val brandColor: Int = BrandGreen.toArgb()

    /**
     * General offer/suggestion notification on the OFFERS channel. The caller
     * supplies [title]/[body] — keep them in §8 allowed language. Provide a
     * [stableId] so repeats de-dup within the process.
     */
    fun postOfferSuggestion(
        title: String,
        body: String,
        stableId: String = "offer_suggestion",
        deepLinkRoute: String = "offers"
    ): Boolean = post(
        channelId = NotificationChannels.OFFERS,
        stableId = stableId,
        title = title,
        body = body,
        deepLinkRoute = deepLinkRoute
    )

    /** App-update notice on the UPDATES channel. */
    fun postAppUpdate(
        versionName: String,
        deepLinkRoute: String = "settings"
    ): Boolean = post(
        channelId = NotificationChannels.UPDATES,
        stableId = "update_$versionName",
        title = "Skylink Bingwa $versionName is available",
        body = "A new version of Skylink Bingwa is ready. Tap to learn what is new.",
        deepLinkRoute = deepLinkRoute
    )

    /** Remote push notification from Firebase / Admin on the NEWS channel. */
    fun postPush(
        title: String,
        body: String,
        deepLinkRoute: String = "home",
        stableId: String = "push_${System.currentTimeMillis()}"
    ): Boolean = post(
        channelId = NotificationChannels.NEWS,
        stableId = stableId,
        title = title,
        body = body,
        deepLinkRoute = deepLinkRoute
    )

    /**
     * Posts on behalf of the assistant engine
     * (`core/notifications/engine/NotificationEngine`).
     *
     * Deliberately does NOT consult [postedStableIds]. That set is a permanent
     * per-process de-dup, which is right for the one-shot helpers above but would
     * block every legitimate repeat of a recurring engine notification (a morning
     * greeting, a later low-balance nudge). The engine owns rate limiting itself:
     * per-category cooldowns, quiet hours, a daily cap and content-hash
     * de-duplication persisted across process death (see `NotificationPolicy`).
     *
     * The caller supplies a stable [notificationId] so a newer message replaces
     * the older one in the tray. Returns true only when the notification was
     * actually handed to the system.
     */
    fun postEngine(
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        deepLinkRoute: String
    ): Boolean = postInternal(channelId, notificationId, title, body, deepLinkRoute)

    /**
     * Core builder. Returns true when a notification was handed to the system,
     * false when skipped (already posted this process, or notifications not
     * permitted). Long bodies use BigTextStyle so they expand cleanly.
     */
    private fun post(
        channelId: String,
        stableId: String,
        title: String,
        body: String,
        deepLinkRoute: String
    ): Boolean {
        if (!postedStableIds.add(stableId)) return false
        val posted = postInternal(channelId, stableId.hashCode(), title, body, deepLinkRoute)
        if (!posted) {
            // Allow a later retry once permission is granted / the error clears.
            postedStableIds.remove(stableId)
        }
        return posted
    }

    @SuppressLint("MissingPermission") // Guarded by canPost(); this class never requests the permission.
    private fun postInternal(
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        deepLinkRoute: String
    ): Boolean {
        if (!canPost()) return false

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_skylink_bingwa)
            .setColor(brandColor)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(deepLinkIntent(notificationId, deepLinkRoute))

        return try {
            manager.notify(notificationId, builder.build())
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun deepLinkIntent(requestCode: Int, route: String): PendingIntent {
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                putExtra(EXTRA_DEEP_LINK_ROUTE, route)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            ?: Intent()

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getActivity(context, requestCode, launch, flags)
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * Intent extra (String) carrying the in-app route a notification tap should
         * open, e.g. "home" | "offers" | "activity" | "help" | "settings". The
         * integration step reads this in MainActivity to deep-link.
         */
        const val EXTRA_DEEP_LINK_ROUTE = "deep_link_route"
    }
}
