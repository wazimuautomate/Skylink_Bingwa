package com.example

import android.util.Log
import com.example.core.model.NotificationItem
import com.example.core.notifications.AppNotifier
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Firebase Cloud Messaging service for My Bingwa.
 *
 * Receives instant admin push notifications sent from the admin dashboard and
 * posts them locally via [AppNotifier], recording the message into the local
 * notification centre store so it appears in [feature.notifications.NotificationsSheet].
 */
class MyBingwaFirebaseService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received: $token")
        val app = applicationContext as? MyBingwaApplication ?: return
        serviceScope.launch {
            app.repository.setFcmToken(token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.data["title"]
            ?: remoteMessage.notification?.title
            ?: "My Bingwa"
        val body = remoteMessage.data["body"]
            ?: remoteMessage.notification?.body
            ?: return
        val route = remoteMessage.data["route"]
            ?.takeIf { it.isNotBlank() }
            ?: "notifications"

        val app = applicationContext as? MyBingwaApplication
        val notifier = app?.let { AppNotifier(it.applicationContext) } ?: AppNotifier(applicationContext)

        // Post system tray notification
        notifier.postPush(
            title = title,
            body = body,
            deepLinkRoute = route,
            stableId = "fcm_${remoteMessage.messageId ?: UUID.randomUUID()}"
        )

        // Record in the repository notifications list for the in-app notification center
        app?.let {
            serviceScope.launch {
                val item = NotificationItem(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    body = body,
                    timestampMillis = System.currentTimeMillis(),
                    isRead = false,
                    deepLinkRoute = route
                )
                it.repository.addNotification(item)
            }
        }
    }

    companion object {
        private const val TAG = "MyBingwaFCM"
    }
}
