package com.mytasks.app.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
}

/** Mirrors a document at `lists/{listId}/tasks/{taskId}`. */
data class TaskItem(
    @DocumentId
    val id: String = "",
    val listId: String = "",
    val title: String = "",
    val description: String = "",
    val assigneeId: String = "",
    val assigneeName: String = "",
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val dueAt: Date? = null,
    val notify: Boolean = false,
    val completed: Boolean = false,
    val createdBy: String = "",
    val createdByName: String = "",
    @ServerTimestamp
    val createdAt: Date? = null,
    @ServerTimestamp
    val updatedAt: Date? = null,
    /** Set by the scheduled Cloud Function once a due-date push has been
     *  sent, so it isn't re-sent on the next sweep. */
    val reminderSent: Boolean = false,
)
