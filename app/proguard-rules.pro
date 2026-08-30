# Firebase / Firestore model classes are (de)serialized via reflection.
-keepclassmembers class com.mytasks.app.data.model.** {
    *;
}
-keep class com.mytasks.app.data.model.** { *; }

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
