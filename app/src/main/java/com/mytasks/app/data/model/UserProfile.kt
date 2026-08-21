package com.mytasks.app.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Mirrors a document at `users/{uid}`. Created/merged on every sign-in so
 * other members can be looked up by email when inviting them to a shared
 * list, and so the assignee's FCM tokens are reachable for push
 * notifications.
 */
data class UserProfile(
    @DocumentId
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val fcmTokens: List<String> = emptyList(),
    @ServerTimestamp
    val lastSignedInAt: Date? = null,
)
