package com.mytasks.app.data.remote

import android.app.Activity
import androidx.activity.ComponentActivity
import io.appwrite.enums.ExecutionStatus
import io.appwrite.enums.OAuthProvider
import io.appwrite.exceptions.AppwriteException
import io.appwrite.services.Account
import io.appwrite.services.Functions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import com.mytasks.app.BuildConfig

/** Firebase-independent stand-in for the old FirebaseUser, mapped from
 *  Appwrite's Account/User model - see AppwriteAuthRepository. */
data class AppUser(
    val uid: String,
    val displayName: String,
    val email: String,
    val photoUrl: String,
)

interface AuthRepository {
    val authState: Flow<AppUser?>
    val currentUser: AppUser?

    /** Runs Appwrite's OAuth2 browser-redirect flow for Google (opens a
     *  Custom Tab against Appwrite's own hosted OAuth endpoint; no Google
     *  ID token ever reaches app code - see AndroidManifest.xml's
     *  appwriteCallbackScheme deep link and Appwrite Console's configured
     *  Google provider). */
    suspend fun signInWithGoogle(activity: Activity): AppUser

    suspend fun signOut()

    /** Calls the `delete-account` Appwrite Function (runs as the
     *  currently-signed-in user via the SDK's session), which deletes this
     *  user's data and their Appwrite Auth account, then clears the local
     *  session. */
    suspend fun deleteAccount()
}

@Singleton
class AppwriteAuthRepository @Inject constructor(
    private val account: Account,
    private val functions: Functions,
) : AuthRepository {

    // No AuthStateListener equivalent in the Appwrite SDK: this is updated
    // imperatively after every sign-in/sign-out/delete, and seeded once at
    // construction below.
    private val _authState = MutableStateFlow<AppUser?>(null)
    override val authState: Flow<AppUser?> = _authState.asStateFlow()

    override val currentUser: AppUser?
        get() = _authState.value

    init {
        // Fire-and-forget: seeds authState from any pre-existing session
        // (e.g. app process restart while still signed in). A fresh
        // install/signed-out user simply gets a 401 here, mapped to null.
        CoroutineScope(Dispatchers.IO).launch { refreshCurrentUser() }
    }

    private suspend fun refreshCurrentUser() {
        _authState.value = try {
            mapUser(account.get())
        } catch (e: AppwriteException) {
            null
        }
    }

    override suspend fun signInWithGoogle(activity: Activity): AppUser {
        // createOAuth2Session switched to createOAuth2Token: Appwrite's own
        // guidance ("Fixing OAuth2 authentication issues in Appwrite",
        // appwrite.io/blog/post/fixing-oauth2-issues-in-appwrite-cloud) is to
        // use the token-based flow because the session-based flow depends on
        // a cookie set on Appwrite's own domain, which third-party-cookie
        // blocking can break. `provider` also takes the io.appwrite.enums.OAuthProvider
        // enum, not a raw String - verified against
        // io.appwrite/services/Account.kt in github.com/appwrite/sdk-for-android
        // (both createOAuth2Session/createOAuth2Token still exist there as of
        // SDK 27.0.0 and share this signature; on Android both already
        // complete the session locally via the WebAuthComponent redirect
        // callback, so no separate createSession() exchange call is needed).
        val componentActivity = activity as ComponentActivity
        account.createOAuth2Token(
            activity = componentActivity,
            provider = OAuthProvider.GOOGLE,
            scopes = listOf("email", "profile"),
        )
        val photoUrl = fetchGooglePhotoUrl()
        val appUser = mapUser(account.get(), photoUrlOverride = photoUrl)
        _authState.value = appUser
        return appUser
    }

    override suspend fun signOut() {
        account.deleteSession(sessionId = "current")
        _authState.value = null
    }

    override suspend fun deleteAccount() {
        val execution = functions.createExecution(functionId = BuildConfig.APPWRITE_FUNCTION_DELETE_ACCOUNT_ID)
        val statusCode = execution.responseStatusCode
        // execution.status is the ExecutionStatus enum, not a String - the
        // original `.equals("failed", ignoreCase = true)` doesn't resolve
        // against that type (no such overload on an enum). Verified against
        // io.appwrite.models.Execution in sdk-for-android.
        val failed = execution.status == ExecutionStatus.FAILED ||
            (statusCode != 0L && statusCode !in 200..299)
        if (failed) {
            error("Account deletion failed (status=${execution.status}, code=$statusCode)")
        }
        signOut()
    }

    /** Appwrite's Account/User model has no OAuth profile-picture field.
     *  Best-effort only: reads the Google OAuth access token off the
     *  current session and asks Google's userinfo endpoint for the profile
     *  photo. Any failure here (missing token, offline, unexpected
     *  response shape) must never break sign-in, so every error path just
     *  falls back to "". */
    private suspend fun fetchGooglePhotoUrl(): String {
        return try {
            val session = account.getSession(sessionId = "current")
            // Field name confirmed against io.appwrite.models.Session in
            // sdk-for-android: providerAccessToken is correct.
            val accessToken = session.providerAccessToken
            if (accessToken.isNullOrBlank()) return ""
            val connection = URL("https://www.googleapis.com/oauth2/v3/userinfo")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.inputStream.use { stream ->
                val body = BufferedReader(InputStreamReader(stream)).readText()
                JSONObject(body).optString("picture", "")
            }
        } catch (t: Throwable) {
            ""
        }
    }

    private fun mapUser(user: io.appwrite.models.User<*>, photoUrlOverride: String? = null): AppUser = AppUser(
        uid = user.id,
        displayName = user.name,
        email = user.email,
        photoUrl = photoUrlOverride.orEmpty(),
    )
}
