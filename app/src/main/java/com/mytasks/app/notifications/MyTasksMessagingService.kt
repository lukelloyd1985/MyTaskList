package com.mytasks.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mytasks.app.data.remote.AuthRepository

/**
 * Receives pushes sent server-side (task assignment + due-date pushes for
 * lists the recipient isn't currently viewing) and registers this device's
 * FCM token as an Appwrite Messaging push Target (see
 * AuthRepository.registerPushTarget) so the `notifications` Appwrite
 * Function can reach it. FCM stays the transport; delivery itself -
 * including dead-token pruning - is handled by Appwrite's Messaging
 * service and its FCM Provider, not by this app.
 */
@AndroidEntryPoint
class MyTasksMessagingService : FirebaseMessagingService() {

    @Inject lateinit var authRepository: AuthRepository

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (authRepository.currentUser == null) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { authRepository.registerPushTarget(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        NotificationHelper.show(
            applicationContext,
            notificationId = message.messageId.hashCode(),
            title = title,
            body = body,
            listId = message.data["listId"],
            taskId = message.data["taskId"],
        )
    }
}
