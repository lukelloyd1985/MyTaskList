package com.github.lukelloyd1985.mytasklist.data.model

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

/** Mirrors a document in the `lists` collection. Examples: "Short term",
 *  "Long term", "Garden", "House".
 *
 *  Appwrite document mapping (id, `members` JSON-decoding, etc.) is done by
 *  hand in AppwriteListRepository - see AppwriteDocumentMapping.kt. There's
 *  no `createdAt` field: Appwrite's `$createdAt` system field on the
 *  document supersedes it. */
data class TaskList(
    val id: String = "",
    val name: String = "",
    val icon: String = "checklist",
    val colorHex: String = "#1B5E20",
    val visibility: ListVisibility = ListVisibility.PRIVATE,
    val ownerId: String = "",
    val ownerName: String = "",
    val memberIds: List<String> = emptyList(),
    val members: List<ListMember> = emptyList(),
) {
    fun isMember(uid: String) = uid == ownerId || memberIds.contains(uid)
}
