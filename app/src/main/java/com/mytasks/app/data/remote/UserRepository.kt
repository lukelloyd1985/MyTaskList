package com.mytasks.app.data.remote

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import com.mytasks.app.data.model.UserProfile

interface UserRepository {
    suspend fun upsertProfile(user: FirebaseUser)
    suspend fun findByEmail(email: String): UserProfile?
    suspend fun getById(uid: String): UserProfile?
    suspend fun addFcmToken(uid: String, token: String)
}

@Singleton
class FirestoreUserRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : UserRepository {

    private val users get() = firestore.collection(FirestorePaths.USERS)

    override suspend fun upsertProfile(user: FirebaseUser) {
        val profile = mapOf(
            "displayName" to (user.displayName ?: user.email.orEmpty()),
            "email" to (user.email?.trim()?.lowercase() ?: ""),
            "photoUrl" to (user.photoUrl?.toString() ?: ""),
            "locale" to Locale.getDefault().language,
            "lastSignedInAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
        )
        users.document(user.uid).set(profile, SetOptions.merge()).await()
    }

    override suspend fun findByEmail(email: String): UserProfile? {
        val snapshot = users.whereEqualTo("email", email.trim().lowercase()).limit(1).get().await()
        return snapshot.documents.firstOrNull()?.toObject(UserProfile::class.java)
    }

    override suspend fun getById(uid: String): UserProfile? {
        return users.document(uid).get().await().toObject(UserProfile::class.java)
    }

    override suspend fun addFcmToken(uid: String, token: String) {
        users.document(uid).set(
            mapOf("fcmTokens" to com.google.firebase.firestore.FieldValue.arrayUnion(token)),
            SetOptions.merge(),
        ).await()
    }
}
