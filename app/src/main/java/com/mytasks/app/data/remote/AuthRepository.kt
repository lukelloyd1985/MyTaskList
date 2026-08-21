package com.mytasks.app.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
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

    override fun signOut() {
        firebaseAuth.signOut()
    }
}
