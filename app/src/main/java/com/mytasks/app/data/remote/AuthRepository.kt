package com.mytasks.app.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.appwrite.enums.ExecutionStatus
import io.appwrite.exceptions.AppwriteException
import io.appwrite.services.Account
import io.appwrite.services.Functions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
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

    /** Bridges a Google ID token obtained natively via Credential Manager
     *  (see LoginScreen.kt - no browser redirect, no Appwrite-branded page
     *  ever shown) into a real Appwrite session. Sends the token to the
     *  `maintenance` Function's /google-sign-in route (see
     *  appwrite/functions/maintenance/src/googleSignIn.ts), which verifies
     *  it against Google, creates the Appwrite Auth user on first sign-in,
     *  and mints a custom token; this then exchanges that token for a
     *  session via account.createSession, following Appwrite's documented
     *  Custom Token login pattern. */
    suspend fun signInWithGoogleIdToken(idToken: String): AppUser

    suspend fun signOut()

    /** Invokes the `maintenance` Appwrite Function over HTTP (runs as the
     *  currently-signed-in user via the SDK's session) - the function
     *  dispatches an HTTP-triggered execution to its account-deletion
     *  handler (see appwrite/functions/maintenance/src/main.ts), which
     *  deletes this user's data and their Appwrite Auth account. Clears the
     *  local session afterward. */
    suspend fun deleteAccount()

    /** Registers (or refreshes) this device's FCM token as an Appwrite
     *  Messaging push Target for the current session, so server-sent
     *  pushes (see appwrite/functions/notifications) reach it. Requires an
     *  active session - callers must guard on being signed in first. Safe
     *  to call repeatedly: creates a Target on first call, updates it on
     *  every later one (e.g. FCM token rotation). */
    suspend fun registerPushTarget(fcmToken: String)

    /** Best-effort removal of this device's push Target - call before
     *  signing out, so a different account signing in on the same device
     *  later doesn't inherit its Target registration. Never throws:
     *  sign-out must not be blocked by this. */
    suspend fun unregisterPushTarget()
}

@Singleton
class AppwriteAuthRepository @Inject constructor(
    private val account: Account,
    private val functions: Functions,
    @ApplicationContext private val appContext: Context,
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

    override suspend fun signInWithGoogleIdToken(idToken: String): AppUser {
        val requestBody = JSONObject().put("idToken", idToken).toString()
        val execution = functions.createExecution(
            functionId = BuildConfig.APPWRITE_FUNCTION_MAINTENANCE_ID,
            body = requestBody,
            path = "/google-sign-in",
        )
        // A bug inside the Function (an exception outside its own
        // try/catch blocks, e.g. a scope/permission error on an Appwrite
        // API call) can leave responseBody empty even though this
        // createExecution call itself succeeded - parsing that as JSON
        // throws an opaque "End of input at character 0" from org.json
        // rather than anything actionable, so it's checked explicitly
        // first and surfaced with the execution's status for
        // troubleshooting (see its logs in Appwrite Console).
        if (execution.responseBody.isBlank()) {
            error("Google sign-in failed: empty response from the maintenance Function (status=${execution.status}, code=${execution.responseStatusCode}) - check its execution logs in Appwrite Console")
        }
        val responseBody = try {
            JSONObject(execution.responseBody)
        } catch (e: JSONException) {
            error("Google sign-in failed: unexpected response from the maintenance Function")
        }
        if (!responseBody.optBoolean("success", false)) {
            error(responseBody.optString("message", "Google sign-in failed"))
        }
        val userId = responseBody.getString("userId")
        val secret = responseBody.getString("secret")
        val photoUrl = responseBody.optString("photoUrl", "")

        account.createSession(userId = userId, secret = secret)
        val appUser = mapUser(account.get(), photoUrlOverride = photoUrl)
        _authState.value = appUser
        return appUser
    }

    override suspend fun signOut() {
        // Best-effort, and must run before deleteSession - unregistering
        // needs the still-active session to identify which Target to
        // remove.
        runCatching { unregisterPushTarget() }
        account.deleteSession(sessionId = "current")
        _authState.value = null
    }

    override suspend fun deleteAccount() {
        val execution = functions.createExecution(functionId = BuildConfig.APPWRITE_FUNCTION_MAINTENANCE_ID)
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

    private fun mapUser(user: io.appwrite.models.User<*>, photoUrlOverride: String? = null): AppUser = AppUser(
        uid = user.id,
        displayName = user.name,
        email = user.email,
        photoUrl = photoUrlOverride.orEmpty(),
    )

    // createPushTarget/updatePushTarget/deletePushTarget signatures
    // verified against io.appwrite.services.Account in
    // github.com/appwrite/sdk-for-android at tag 25.2.0 (matching this
    // project's pinned SDK version) - not exercised against a live call
    // in this sandbox, which has no network path to a real Appwrite
    // project.
    override suspend fun registerPushTarget(fcmToken: String) {
        val targetId = pushTargetId()
        try {
            account.createPushTarget(targetId = targetId, identifier = fcmToken)
        } catch (e: AppwriteException) {
            // 409: this device already has a Target under this ID (e.g. a
            // token refresh, or a re-registration after app restart) -
            // update it with the new token instead of failing.
            if (e.code == 409) {
                account.updatePushTarget(targetId = targetId, identifier = fcmToken)
            } else {
                throw e
            }
        }
    }

    override suspend fun unregisterPushTarget() {
        try {
            account.deletePushTarget(targetId = pushTargetId())
        } catch (e: AppwriteException) {
            // Nothing to clean up (never registered, or already gone) -
            // fine either way, sign-out must not fail because of this.
        }
    }

    /** A random ID generated once per app install and persisted locally,
     *  identifying this device's push Target across app restarts and FCM
     *  token refreshes (Appwrite Targets are looked up/updated by ID, not
     *  by their token value). Not tied to any particular signed-in
     *  account - if a different user signs in on the same device later,
     *  registerPushTarget's create-then-update-on-409 handles re-pointing
     *  this same Target at the new session. */
    private fun pushTargetId(): String {
        val prefs = appContext.getSharedPreferences(PUSH_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PUSH_TARGET_ID, null) ?: UUID.randomUUID().toString().also { generated ->
            prefs.edit().putString(KEY_PUSH_TARGET_ID, generated).apply()
        }
    }

    private companion object {
        const val PUSH_PREFS_NAME = "mytasks_push"
        const val KEY_PUSH_TARGET_ID = "push_target_id"
    }
}
