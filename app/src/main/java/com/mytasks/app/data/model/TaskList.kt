package com.mytasks.app.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

enum class ListVisibility {
    PRIVATE,
    SHARED,
}

/** A lightweight, embedded snapshot of a member so list screens don't need
 *  an extra read to show who's on a shared list. */
data class ListMember(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String = "",
)

/** Mirrors a document at `lists/{listId}`. Examples: "Short term", "Long
 *  term", "Garden", "House". */
data class TaskList(
    @DocumentId
    val id: String = "",
    val name: String = "",
    val icon: String = "checklist",
    val colorHex: String = "#1B5E20",
    val visibility: ListVisibility = ListVisibility.PRIVATE,
    val ownerId: String = "",
    val ownerName: String = "",
    val memberIds: List<String> = emptyList(),
    val members: List<ListMember> = emptyList(),
    @ServerTimestamp
    val createdAt: Date? = null,
) {
    fun isMember(uid: String) = uid == ownerId || memberIds.contains(uid)
}
