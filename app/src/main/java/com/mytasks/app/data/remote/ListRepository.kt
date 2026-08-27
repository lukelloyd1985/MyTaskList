package com.mytasks.app.data.remote

import io.appwrite.ID
import io.appwrite.Permission
import io.appwrite.Query
import io.appwrite.Role
import io.appwrite.exceptions.AppwriteException
import io.appwrite.models.Document
import io.appwrite.services.Databases
import io.appwrite.services.Realtime
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.mytasks.app.BuildConfig
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
class AppwriteListRepository @Inject constructor(
    private val databases: Databases,
    private val realtime: Realtime,
) : ListRepository {

    private val databaseId = BuildConfig.APPWRITE_DATABASE_ID
    private val listsId = BuildConfig.APPWRITE_COLLECTION_LISTS_ID
    private val tasksId = BuildConfig.APPWRITE_COLLECTION_TASKS_ID

    // No uid-based query filter needed: Appwrite document permissions
    // already scope listDocuments results to what this caller can see
    // (owner read/write + member read, set in createList/addMember/etc.
    // below), unlike the old Firestore `ownerId == uid OR memberIds
    // contains uid` filter.
    override fun observeMyLists(uid: String): Flow<List<TaskList>> = callbackFlow {
        suspend fun refresh() {
            try {
                val result = databases.listDocuments(databaseId, listsId, queries = listOf(Query.limit(200)))
                trySend(result.documents.map { it.toTaskList() })
            } catch (t: Throwable) {
                close(t)
            }
        }
        refresh()
        val channel = "databases.$databaseId.collections.$listsId.documents"
        val subscription = realtime.subscribe(channel) { launch { refresh() } }
        awaitClose { subscription.close() }
    }

    override fun observeList(listId: String): Flow<TaskList?> = callbackFlow {
        suspend fun refresh() {
            try {
                trySend(getListOrNull(listId))
            } catch (t: Throwable) {
                close(t)
            }
        }
        refresh()
        val channel = "databases.$databaseId.collections.$listsId.documents.$listId"
        val subscription = realtime.subscribe(channel) { launch { refresh() } }
        awaitClose { subscription.close() }
    }

    override suspend fun createList(
        name: String,
        icon: String,
        colorHex: String,
        visibility: ListVisibility,
        owner: ListMember,
    ): String {
        val data = mapOf(
            "name" to name,
            "icon" to icon,
            "colorHex" to colorHex,
            "visibility" to visibility.name,
            "ownerId" to owner.uid,
            "ownerName" to owner.displayName,
            "memberIds" to emptyList<String>(),
            "members" to encodeMembers(emptyList()),
        )
        val document = databases.createDocument(
            databaseId = databaseId,
            collectionId = listsId,
            documentId = ID.unique(),
            data = data,
            permissions = listPermissions(owner.uid, emptyList()),
        )
        return document.id
    }

    override suspend fun setVisibility(listId: String, visibility: ListVisibility) {
        if (visibility == ListVisibility.PRIVATE) {
            // Going private also revokes everyone else's access: memberIds
            // (and the permissions computed from it) must never disagree
            // with `visibility`, same invariant as the old Firestore rules.
            val current = getListOrNull(listId) ?: return
            databases.updateDocument(
                databaseId = databaseId,
                collectionId = listsId,
                documentId = listId,
                data = mapOf(
                    "visibility" to visibility.name,
                    "memberIds" to emptyList<String>(),
                    "members" to encodeMembers(emptyList()),
                ),
                permissions = listPermissions(current.ownerId, emptyList()),
            )
        } else {
            databases.updateDocument(
                databaseId = databaseId,
                collectionId = listsId,
                documentId = listId,
                data = mapOf("visibility" to visibility.name),
            )
        }
    }

    override suspend fun addMember(listId: String, member: ListMember) {
        val current = getListOrNull(listId) ?: return
        if (current.memberIds.contains(member.uid)) return
        val newMemberIds = current.memberIds + member.uid
        val newMembers = current.members + member
        databases.updateDocument(
            databaseId = databaseId,
            collectionId = listsId,
            documentId = listId,
            data = mapOf(
                "memberIds" to newMemberIds,
                "members" to encodeMembers(newMembers),
            ),
            permissions = listPermissions(current.ownerId, newMemberIds),
        )
    }

    override suspend fun removeMember(listId: String, uid: String) {
        val current = getListOrNull(listId) ?: return
        val newMemberIds = current.memberIds.filterNot { it == uid }
        val newMembers = current.members.filterNot { it.uid == uid }
        databases.updateDocument(
            databaseId = databaseId,
            collectionId = listsId,
            documentId = listId,
            data = mapOf(
                "memberIds" to newMemberIds,
                "members" to encodeMembers(newMembers),
            ),
            permissions = listPermissions(current.ownerId, newMemberIds),
        )
    }

    override suspend fun renameList(listId: String, name: String) {
        databases.updateDocument(databaseId, listsId, listId, mapOf("name" to name))
    }

    /**
     * Appwrite has no atomic batch delete like Firestore's WriteBatch, so
     * this can't be a single all-or-nothing operation. Tasks are deleted
     * first, in parallel, and the list document is deleted last, on
     * purpose: if this is interrupted partway through, the list document
     * survives with some or all of its tasks already gone - a safe,
     * retryable state, since re-running deleteList finishes the job.
     * Deleting the list first would risk the opposite outcome: tasks left
     * behind with no owning list document, unreachable from the app and
     * only cleanable by hand.
     */
    override suspend fun deleteList(listId: String) {
        val taskDocs = fetchAllTaskDocuments(listId)
        coroutineScope {
            taskDocs.map { doc ->
                async { databases.deleteDocument(databaseId, tasksId, doc.id) }
            }.awaitAll()
        }
        databases.deleteDocument(databaseId, listsId, listId)
    }

    private suspend fun fetchAllTaskDocuments(listId: String): List<Document<Map<String, Any>>> {
        val pageSize = 100
        val allDocs = mutableListOf<Document<Map<String, Any>>>()
        var cursor: String? = null
        while (true) {
            val queries = buildList {
                add(Query.equal("listId", listId))
                add(Query.limit(pageSize))
                cursor?.let { add(Query.cursorAfter(it)) }
            }
            val page = databases.listDocuments(databaseId, tasksId, queries)
            allDocs += page.documents
            if (page.documents.size < pageSize) break
            cursor = page.documents.last().id
        }
        return allDocs
    }

    private suspend fun getListOrNull(listId: String): TaskList? = try {
        databases.getDocument(databaseId, listsId, listId).toTaskList()
    } catch (e: AppwriteException) {
        if (e.code == 404) null else throw e
    }

    private fun listPermissions(ownerId: String, memberIds: List<String>): List<String> =
        listOf(
            Permission.read(Role.user(ownerId)),
            Permission.update(Role.user(ownerId)),
            Permission.delete(Role.user(ownerId)),
        ) + memberIds.filterNot { it == ownerId }.map { Permission.read(Role.user(it)) }
}

private fun Document<Map<String, Any>>.toTaskList(): TaskList {
    val fields = data
    return TaskList(
        id = id,
        name = fields["name"] as? String ?: "",
        icon = fields["icon"] as? String ?: "checklist",
        colorHex = fields["colorHex"] as? String ?: "#1B5E20",
        visibility = (fields["visibility"] as? String)
            ?.let { runCatching { ListVisibility.valueOf(it) }.getOrNull() }
            ?: ListVisibility.PRIVATE,
        ownerId = fields["ownerId"] as? String ?: "",
        ownerName = fields["ownerName"] as? String ?: "",
        memberIds = (fields["memberIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        members = decodeMembers(fields["members"]),
    )
}
