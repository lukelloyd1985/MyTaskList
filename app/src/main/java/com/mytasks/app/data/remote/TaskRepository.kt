package com.mytasks.app.data.remote

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObjects
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import com.mytasks.app.data.model.TaskItem
import com.mytasks.app.data.model.TaskPriority

interface TaskRepository {
    fun observeTasks(listId: String): Flow<List<TaskItem>>
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
class FirestoreTaskRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : TaskRepository {

    private fun tasksOf(listId: String) =
        firestore.collection(FirestorePaths.LISTS).document(listId).collection(FirestorePaths.TASKS)

    override fun observeTasks(listId: String): Flow<List<TaskItem>> = callbackFlow {
        val query = tasksOf(listId)
            .orderBy("completed")
            .orderBy("dueAt", Query.Direction.ASCENDING)
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.toObjects<TaskItem>() ?: emptyList())
        }
        awaitClose { registration.remove() }
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
    ): String {
        val doc = tasksOf(listId).document()
        val task = mapOf(
            "listId" to listId,
            "title" to title,
            "description" to description,
            "assigneeId" to assigneeId,
            "assigneeName" to assigneeName,
            "priority" to priority.name,
            "dueAt" to dueAt,
            "notify" to notify,
            "completed" to false,
            "reminderSent" to false,
            "createdBy" to createdBy,
            "createdByName" to createdByName,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        doc.set(task).await()
        return doc.id
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
        val updates = mapOf(
            "title" to title,
            "description" to description,
            "assigneeId" to assigneeId,
            "assigneeName" to assigneeName,
            "priority" to priority.name,
            "dueAt" to dueAt,
            "notify" to notify,
            "reminderSent" to false,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        tasksOf(listId).document(taskId).update(updates).await()
    }

    override suspend fun setCompleted(listId: String, taskId: String, completed: Boolean) {
        tasksOf(listId).document(taskId).update(
            mapOf("completed" to completed, "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    override suspend fun deleteTask(listId: String, taskId: String) {
        tasksOf(listId).document(taskId).delete().await()
    }

    override suspend fun reorderTasks(listId: String, orderedTaskIds: List<String>) {
        val tasks = tasksOf(listId)
        val batch = firestore.batch()
        orderedTaskIds.forEachIndexed { index, taskId ->
            batch.update(tasks.document(taskId), "order", index.toLong())
        }
        batch.commit().await()
    }
}
