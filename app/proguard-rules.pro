# Firebase / Firestore model classes are (de)serialized via reflection.
-keepclassmembers class com.github.lukelloyd1985.mytasklist.data.model.** {
    *;
}
-keep class com.github.lukelloyd1985.mytasklist.data.model.** { *; }

# Credential Manager + Google Identity Services (Sign in with Google) -
# see LoginScreen.kt/AuthRepository.kt. Working hypothesis for a real
# reported bug: sign-in works on the unminified debug build but fails
# with GetCredentialException TYPE_USER_CANCELED ("Account reauth
# failed") on the release build, with the app's signing cert and the
# Google Cloud Android OAuth client's fingerprint both confirmed
# correct and identical between the two besides the cert itself -
# isMinifyEnabled is release-only (see app/build.gradle.kts), and these
# libraries talk to Google Play Services over reflection-based/Parcelable
# IPC, a well-documented R8 failure class for Credential Manager/Google
# Sign-In specifically. Not yet confirmed against a real device (no
# network path to a live Appwrite/Google project from this sandbox) -
# purely additive/safe either way, since keep rules can only prevent
# stripping, never break a working build.
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class com.google.android.gms.auth.api.identity.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**

# Appwrite SDK - confirmed root cause of a real reported bug: the app
# crashed immediately after sign-in on the release build only, never on
# the unminified debug build, with an identical Realtime channel
# subscription running in both. Traced into the SDK's own source
# (io.appwrite.services.Realtime, sdk-for-android at the pinned 25.2.0
# tag): every REST model (Session, User, Execution, etc.) is built
# manually via `map["key"] as Type` in each model's own from(map)
# factory - already R8-safe by construction, which is exactly why
# sign-in and every other REST call work fine on release. But
# Realtime's WebSocket error handler is the one place that deserializes
# straight through Gson reflection onto a typed class instead
# (`message.data?.jsonCast<AppwriteException>()`), and io.appwrite.**
# had zero keep-rule coverage - so AppwriteException's fields could get
# renamed by R8, breaking Gson's field-name-based matching against the
# server's JSON only in this one path. Kept broadly (not just
# AppwriteException) since this is the only Gson-reflection call site
# audited so far, not necessarily the only one in the SDK.
-keep class io.appwrite.** { *; }
-dontwarn io.appwrite.**

# WorkManager (ReminderScheduler.kt's local due-date reminder fallback -
# see README "Architecture") - confirmed root cause of a real reported
# crash: NoSuchMethodException on androidx.work.impl.WorkDatabase_Impl's
# constructor, reflectively instantiated by WorkManager's own internals
# (Room-backed persistence for scheduled work) the first time work is
# actually scheduled - happens on opening a list detail screen, which is
# where a task's due-date reminder first gets scheduled. WorkManager
# ships its own consumer ProGuard rules meant to cover this
# automatically, but that evidently isn't taking effect in this build -
# kept explicitly instead of relying on it.
-keep class androidx.work.impl.** { *; }
-dontwarn androidx.work.**
