package com.mytasks.app.data.model

/**
 * Mirrors a document in the `users` collection, doc ID = the Appwrite Auth
 * user's `$id`. Created/merged on every sign-in so other members can be
 * looked up by email when inviting them to a shared list, and so the
 * assignee's FCM tokens are reachable for push notifications.
 *
 * Appwrite document mapping is done by hand in AppwriteUserRepository. No
 * `lastSignedInAt` field: superseded by Appwrite's `$updatedAt` system
 * field on the document.
 */
data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val fcmTokens: List<String> = emptyList(),
    /** ISO 639-1 language code (e.g. "en", "sk", "cs") the app was showing
     *  at last sign-in - refreshed on every sign-in, including any
     *  per-app language override (see res/xml/locales_config.xml), since
     *  Locale.getDefault() reflects that automatically. Used server-side to
     *  localize push notification text; falls back to English there if
     *  unset (e.g. a profile from before this field existed) or
     *  unsupported. */
    val locale: String = "",
)
