package com.mytasks.app.data.remote

import io.appwrite.ID
import io.appwrite.Permission
import io.appwrite.Query
import io.appwrite.Role
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
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import com.mytasks.app.BuildConfig
import com.mytasks.app.data.model.TaskItem
import com.mytasks.app.data.model.TaskPriority

interface TaskRepository {
    fun observeTasks(listId: String): Flow<List<TaskItem>>

    /** [listOwnerId]/[listMemberIds] are the parent list's current owner +
     *  members, passed in by the caller (which already has the loaded
     *  TaskList) rather than re-fetched here, so task-creation permissions
     *  (owner + every member get full read/update/delete) can be computed
     *  without an extra round trip. A separate server-side Appwrite
     *  Function keeps a task's permissions in sync when list membership
     *  later changes. */
    suspend fun createTask(
        listId: String,
        title: String,
        description: String,
        assigneeId: String,
        assigneeName: String,
        priority: TaskPriority,
        dueAt: Date?,
        notify: Boolean,
        createdBy: String,
        createdByName: String,
        listOwnerId: String,
        listMemberIds: List<String>,
    ): String

    suspend fun updateTask(
        listId: String,
        taskId: String,
        title: String,
        description: String,
        assigneeId: String,
        assigneeName: String,
        priority: TaskPriority,
        dueAt: Date?,
        notify: Boolean,
    )

    suspend fun setCompleted(listId: String, taskId: String, completed: Boolean)
    suspend fun deleteTask(listId: String, taskId: String)

    /** Persists a new manual order for (typically) the open tasks in a
     *  list, in the given sequence - see TaskItem.order. */
    suspend fun reorderTasks(listId: String, orderedTaskIds: List<String>)
}

@Singleton
class AppwriteTaskRepository @Inject constructor(
    private val databases: Databases,
    private val realtime: Realtime,
) : TaskRepository {

    private val databaseId = BuildConfig.APPWRITE_DATABASE_ID
    private val tasksId = BuildConfig.APPWRITE_COLLECTION_TASKS_ID

    override fun observeTasks(listId: String): Flow<List<TaskItem>> = callbackFlow {
        suspend fun refresh() {
            try {
                val result = databases.listDocuments(
                    databaseId,
                    tasksId,
                    queries = listOf(
                        Query.equal("listId", listId),
                        Query.orderAsc("completed"),
                        Query.orderAsc("dueAt"),
                        Query.limit(500),
                    ),
                )
                trySend(result.documents.map { it.toTaskItem() })
            } catch (t: Throwable) {
                close(t)
            }
        }
        refresh()
        // Realtime subscribes at collection granularity, not with
        // arbitrary query filters, so every `tasks` collection event is
        // filtered down to this list here before triggering a refetch.
        val channel = "databases.$databaseId.collections.$tasksId.documents"
        val subscription = realtime.subscribe(channel) { response ->
            @Suppress("UNCHECKED_CAST")
            val payload = response.payload as? Map<String, Any?>
            val eventListId = payload?.get("listId") as? String
            if (eventListId == null || eventListId == listId) {
                launch { refresh() }
            }
        }
        awaitClose { subscription.close() }
    }

    override suspend fun createTask(
        listId: String,
        title: String,
        description: String,
        assigneeId: String,
        assigneeName: String,
        priority: TaskPriority,
        dueAt: Date?,
        notify: Boolean,
        createdBy: String,
        createdByName: String,
        listOwnerId: String,
        listMemberIds: List<String>,
    ): String {
        val data = mapOf(
            "listId" to listId,
            "title" to title,
            "description" to description,
            "assigneeId" to assigneeId,
            "assigneeName" to assigneeName,
            "priority" to priority.name,
            "dueAt" to dueAt?.toAppwriteIso(),
            "notify" to notify,
            "completed" to false,
            "order" to 0,
            "createdBy" to createdBy,
            "createdByName" to createdByName,
            "reminderSent" to false,
        )
        val document = databases.createDocument(
            databaseId = databaseId,
            collectionId = tasksId,
            documentId = ID.unique(),
            data = data,
            permissions = taskPermissions(listOwnerId, listMemberIds),
        )
        return document.id
    }

    override suspend fun updateTask(
        listId: String,
        taskId: String,
        title: String,
        description: String,
        assigneeId: String,
        assigneeName: String,
        priority: TaskPriority,
        dueAt: Date?,
        notify: Boolean,
    ) {
        val data = mapOf(
            "title" to title,
            "description" to description,
            "assigneeId" to assigneeId,
            "assigneeName" to assigneeName,
            "priority" to priority.name,
            "dueAt" to dueAt?.toAppwriteIso(),
            "notify" to notify,
            // Reset on every edit - app-level behavior, unchanged by the
            // migration; no manual updatedAt bump needed ($updatedAt is
            // automatic).
            "reminderSent" to false,
        )
        databases.updateDocument(databaseId, tasksId, taskId, data)
    }

    override suspend fun setCompleted(listId: String, taskId: String, completed: Boolean) {
        databases.updateDocument(databaseId, tasksId, taskId, mapOf("completed" to completed))
    }

    override suspend fun deleteTask(listId: String, taskId: String) {
        databases.deleteDocument(databaseId, tasksId, taskId)
    }

    // No batch primitive in Appwrite: each task's `order` is updated in its
    // own request, in parallel. Accepted small atomicity gap - a partial
    // failure leaves some tasks with a stale order, which self-heals the
    // next time the list is successfully reordered.
    override suspend fun reorderTasks(listId: String, orderedTaskIds: List<String>) {
        coroutineScope {
            orderedTaskIds.mapIndexed { index, taskId ->
                async { databases.updateDocument(databaseId, tasksId, taskId, mapOf("order" to index)) }
            }.awaitAll()
        }
    }

    private fun taskPermissions(ownerId: String, memberIds: List<String>): List<String> =
        (listOf(ownerId) + memberIds).distinct().flatMap { uid ->
            listOf(
                Permission.read(Role.user(uid)),
                Permission.update(Role.user(uid)),
                Permission.delete(Role.user(uid)),
            )
        }
}

private fun Document<Map<String, Any>>.toTaskItem(): TaskItem {
    val fields = data
    return TaskItem(
        id = id,
        listId = fields["listId"] as? String ?: "",
        title = fields["title"] as? String ?: "",
        description = fields["description"] as? String ?: "",
        assigneeId = fields["assigneeId"] as? String ?: "",
        assigneeName = fields["assigneeName"] as? String ?: "",
        priority = (fields["priority"] as? String)
            ?.let { runCatching { TaskPriority.valueOf(it) }.getOrNull() }
            ?: TaskPriority.MEDIUM,
        dueAt = fields["dueAt"].asAppwriteDate(),
        notify = fields["notify"] as? Boolean ?: false,
        completed = fields["completed"] as? Boolean ?: false,
        order = (fields["order"] as? Number)?.toLong() ?: 0L,
        createdBy = fields["createdBy"] as? String ?: "",
        createdByName = fields["createdByName"] as? String ?: "",
        reminderSent = fields["reminderSent"] as? Boolean ?: false,
    )
}
