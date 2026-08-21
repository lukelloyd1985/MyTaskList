package com.mytasks.app.data.remote

import android.app.Activity
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

interface AuthRepository {
    val authState: Flow<FirebaseUser?>
    val currentUser: FirebaseUser?

    suspend fun signInWithGoogleIdToken(idToken: String): FirebaseUser
    suspend fun signInWithFacebookAccessToken(accessToken: String): FirebaseUser
    suspend fun signInWithMicrosoft(activity: Activity): FirebaseUser

    /** Used by the LinkedIn / Proton flows: the provider's ID token is
     *  exchanged server-side (Cloud Function) for a Firebase custom token,
     *  which is then redeemed here. */
    suspend fun signInWithCustomToken(customToken: String): FirebaseUser

    fun signOut()
}

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
) : AuthRepository {

    override val authState: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser) }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }

    override val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    override suspend fun signInWithGoogleIdToken(idToken: String): FirebaseUser {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        return firebaseAuth.signInWithCredential(credential).await().user
            ?: error("Google sign-in returned no user")
    }

    override suspend fun signInWithFacebookAccessToken(accessToken: String): FirebaseUser {
        val credential = FacebookAuthProvider.getCredential(accessToken)
        return firebaseAuth.signInWithCredential(credential).await().user
            ?: error("Facebook sign-in returned no user")
    }

    override suspend fun signInWithMicrosoft(activity: Activity): FirebaseUser {
        val pending = firebaseAuth.pendingAuthResult
        val result = if (pending != null) {
            pending.await()
        } else {
            val provider = OAuthProvider.newBuilder("microsoft.com").apply {
                // Forces the account picker every time instead of silently
                // reusing the last Microsoft session in the system browser.
                addCustomParameter("prompt", "select_account")
                setScopes(listOf("openid", "profile", "email"))
            }.build()
            firebaseAuth.startActivityForSignInWithProvider(activity, provider).await()
        }
        return result.user ?: error("Microsoft sign-in returned no user")
    }

    override suspend fun signInWithCustomToken(customToken: String): FirebaseUser {
        return firebaseAuth.signInWithCustomToken(customToken).await().user
            ?: error("Custom token sign-in returned no user")
    }

    override fun signOut() {
        firebaseAuth.signOut()
    }
}
