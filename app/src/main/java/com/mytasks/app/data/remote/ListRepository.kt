package com.mytasks.app.data.remote

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObjects
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import com.mytasks.app.data.model.ListMember
import com.mytasks.app.data.model.ListVisibility
import com.mytasks.app.data.model.TaskList

interface ListRepository {
    /** Every list the user owns, plus every shared list they're a member of. */
    fun observeMyLists(uid: String): Flow<List<TaskList>>
    fun observeList(listId: String): Flow<TaskList?>
    suspend fun createList(name: String, icon: String, colorHex: String, visibility: ListVisibility, owner: ListMember): String
    suspend fun setVisibility(listId: String, visibility: ListVisibility)
    suspend fun addMember(listId: String, member: ListMember)
    suspend fun removeMember(listId: String, uid: String)
    suspend fun renameList(listId: String, name: String)
    suspend fun deleteList(listId: String)
}

@Singleton
class FirestoreListRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : ListRepository {

    private val lists get() = firestore.collection(FirestorePaths.LISTS)

    override fun observeMyLists(uid: String): Flow<List<TaskList>> = callbackFlow {
        val query = lists.where(
            Filter.or(
                Filter.equalTo("ownerId", uid),
                Filter.arrayContains("memberIds", uid),
            ),
        )
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.toObjects<TaskList>() ?: emptyList())
        }
        awaitClose { registration.remove() }
    }

    override fun observeList(listId: String): Flow<TaskList?> = callbackFlow {
        val registration = lists.document(listId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.toObject(TaskList::class.java))
        }
        awaitClose { registration.remove() }
    }

    override suspend fun createList(
        name: String,
        icon: String,
        colorHex: String,
        visibility: ListVisibility,
        owner: ListMember,
    ): String {
        val doc = lists.document()
        val list = mapOf(
            "name" to name,
            "icon" to icon,
            "colorHex" to colorHex,
            "visibility" to visibility.name,
            "ownerId" to owner.uid,
            "ownerName" to owner.displayName,
            "memberIds" to emptyList<String>(),
            "members" to emptyList<Map<String, String>>(),
            "createdAt" to FieldValue.serverTimestamp(),
        )
        doc.set(list).await()
        return doc.id
    }

    override suspend fun setVisibility(listId: String, visibility: ListVisibility) {
        // Going private also revokes everyone else's access: memberIds is
        // the field Firestore security rules actually key off, so it must
        // never disagree with `visibility` (see firestore.rules).
        val updates = if (visibility == ListVisibility.PRIVATE) {
            mapOf(
                "visibility" to visibility.name,
                "memberIds" to emptyList<String>(),
                "members" to emptyList<Map<String, String>>(),
            )
        } else {
            mapOf("visibility" to visibility.name)
        }
        lists.document(listId).update(updates).await()
    }

    override suspend fun addMember(listId: String, member: ListMember) {
        val memberMap = mapOf(
            "uid" to member.uid,
            "displayName" to member.displayName,
            "email" to member.email,
            "photoUrl" to member.photoUrl,
        )
        lists.document(listId).update(
            mapOf(
                "memberIds" to FieldValue.arrayUnion(member.uid),
                "members" to FieldValue.arrayUnion(memberMap),
            ),
        ).await()
    }

    override suspend fun removeMember(listId: String, uid: String) {
        val snapshot = lists.document(listId).get().await()
        val list = snapshot.toObject(TaskList::class.java) ?: return
        val remainingMembers = list.members.filterNot { it.uid == uid }
        val memberMaps = remainingMembers.map {
            mapOf("uid" to it.uid, "displayName" to it.displayName, "email" to it.email, "photoUrl" to it.photoUrl)
        }
        lists.document(listId).update(
            mapOf(
                "memberIds" to FieldValue.arrayRemove(uid),
                "members" to memberMaps,
            ),
        ).await()
    }

    override suspend fun renameList(listId: String, name: String) {
        lists.document(listId).update("name", name).await()
    }

    override suspend fun deleteList(listId: String) {
        val tasksSnapshot = lists.document(listId).collection(FirestorePaths.TASKS).get().await()
        val batch = firestore.batch()
        tasksSnapshot.documents.forEach { batch.delete(it.reference) }
        batch.delete(lists.document(listId))
        batch.commit().await()
    }
}
