package com.mytasks.app.data.remote

/** Central place for Firestore collection names so they can't drift between
 *  repositories, security rules, and Cloud Functions. */
object FirestorePaths {
    const val USERS = "users"
    const val LISTS = "lists"
    const val TASKS = "tasks"
}
