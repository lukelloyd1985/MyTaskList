package com.github.lukelloyd1985.mytasklist.data.model

import java.util.Date

enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
}

/** Mirrors a document in the flat `tasks` collection. Appwrite has no
 *  subcollections, so `listId` is the sole way a task scopes to a list
 *  (previously a denormalized convenience field, now load-bearing).
 *
 *  Appwrite document mapping is done by hand in AppwriteTaskRepository -
 *  see AppwriteDocumentMapping.kt. There's no `createdAt`/`updatedAt`
 *  field: Appwrite's `$createdAt`/`$updatedAt` system fields on the
 *  document supersede them. */
data class TaskItem(
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
    /** Manual drag-to-reorder position within the list. Left unset (0)
     *  until the list is actually reordered - createTask never sets it
     *  either, so new tasks keep sorting by due date like every other
     *  untouched task, rather than always landing at the very bottom.
     *  Kotlin's sortedBy is stable, so ties at 0 simply fall back to the
     *  list's existing query order (see TaskRepository.observeTasks) until
     *  someone actually drags. */
    val order: Long = 0L,
    val createdBy: String = "",
    val createdByName: String = "",
    /** Set once a due-date push has been sent, so it isn't re-sent on the
     *  next sweep (see the server-side workstream owning that sweep). */
    val reminderSent: Boolean = false,
)
