package com.mytasks.app.notifications

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mytasks.app.data.remote.UserRepository

/**
 * Receives pushes sent by the `onTaskWrite` and `dueDateReminders` Cloud
 * Functions (task assignment + due-date pushes for lists the recipient
 * isn't currently viewing) and registers this device's FCM token so those
 * functions know where to deliver them.
 */
@AndroidEntryPoint
class MyTasksMessagingService : FirebaseMessagingService() {

    @Inject lateinit var userRepository: UserRepository

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { userRepository.addFcmToken(uid, token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        NotificationHelper.show(applicationContext, notificationId = message.messageId.hashCode(), title = title, body = body)
    }
}
