package com.mytasks.app.ui.navigation

object Destinations {
    const val LISTS = "lists"
    const val LIST_DETAIL = "listDetail/{listId}?taskId={taskId}"
    const val LIST_SETTINGS = "listSettings/{listId}"
    const val PROFILE = "profile"

    /** [taskId] is optional - when set (from a notification tap, see
     *  MyTasksNavHost's deep-link handling), ListDetailScreen opens that
     *  task's editor sheet as soon as it loads. */
    fun listDetail(listId: String, taskId: String? = null) =
        "listDetail/$listId" + if (taskId != null) "?taskId=$taskId" else ""
    fun listSettings(listId: String) = "listSettings/$listId"
}
