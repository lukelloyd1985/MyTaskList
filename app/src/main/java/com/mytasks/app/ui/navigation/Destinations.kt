package com.mytasks.app.ui.navigation

object Destinations {
    const val LISTS = "lists"
    const val LIST_DETAIL = "listDetail/{listId}"
    const val LIST_SETTINGS = "listSettings/{listId}"
    const val PROFILE = "profile"

    fun listDetail(listId: String) = "listDetail/$listId"
    fun listSettings(listId: String) = "listSettings/$listId"
}
