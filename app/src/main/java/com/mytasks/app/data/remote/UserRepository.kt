package com.mytasks.app.data.remote

import io.appwrite.Permission
import io.appwrite.Query
import io.appwrite.Role
import io.appwrite.exceptions.AppwriteException
import io.appwrite.models.Document
import io.appwrite.services.Databases
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import com.mytasks.app.BuildConfig
import com.mytasks.app.data.model.UserProfile

interface UserRepository {
    suspend fun upsertProfile(user: AppUser)
    suspend fun findByEmail(email: String): UserProfile?
    suspend fun getById(uid: String): UserProfile?
}

@Singleton
class AppwriteUserRepository @Inject constructor(
    private val databases: Databases,
) : UserRepository {

    private val databaseId = BuildConfig.APPWRITE_DATABASE_ID
    private val usersId = BuildConfig.APPWRITE_COLLECTION_USERS_ID

    // Appwrite has no Firestore-style set(merge=true): this is a manual
    // get-or-create, updating the doc if it already exists (from a
    // previous sign-in) or creating it - with doc ID == Appwrite Auth
    // user's $id - the first time.
    override suspend fun upsertProfile(user: AppUser) {
        val fields = mapOf(
            "displayName" to user.displayName.ifBlank { user.email },
            "email" to user.email.trim().lowercase(),
            "photoUrl" to user.photoUrl,
            "locale" to Locale.getDefault().language,
        )
        val existing = getDocumentOrNull(user.uid)
        if (existing != null) {
            databases.updateDocument(
                databaseId = databaseId,
                collectionId = usersId,
                documentId = user.uid,
                data = fields,
            )
        } else {
            databases.createDocument(
                databaseId = databaseId,
                collectionId = usersId,
                documentId = user.uid,
                data = fields,
                permissions = listOf(
                    Permission.read(Role.users()),
                    Permission.update(Role.user(user.uid)),
                    Permission.delete(Role.user(user.uid)),
                ),
            )
        }
    }

    override suspend fun findByEmail(email: String): UserProfile? {
        val result = databases.listDocuments(
            databaseId = databaseId,
            collectionId = usersId,
            queries = listOf(Query.equal("email", email.trim().lowercase()), Query.limit(1)),
        )
        return result.documents.firstOrNull()?.toUserProfile()
    }

    override suspend fun getById(uid: String): UserProfile? = getDocumentOrNull(uid)?.toUserProfile()

    private suspend fun getDocumentOrNull(uid: String): Document<Map<String, Any>>? = try {
        databases.getDocument(databaseId, usersId, uid)
    } catch (e: AppwriteException) {
        if (e.code == 404) null else throw e
    }
}

private fun Document<Map<String, Any>>.toUserProfile(): UserProfile {
    val fields = data
    return UserProfile(
        uid = id,
        displayName = fields["displayName"] as? String ?: "",
        email = fields["email"] as? String ?: "",
        photoUrl = fields["photoUrl"] as? String ?: "",
        locale = fields["locale"] as? String ?: "",
    )
}
