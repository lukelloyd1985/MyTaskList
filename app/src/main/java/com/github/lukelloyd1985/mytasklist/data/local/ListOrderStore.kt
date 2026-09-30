package com.github.lukelloyd1985.mytasklist.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers, per signed-in user and per device, the order that user has
 *  arranged their lists in. Kept local rather than on the `lists` document
 *  because a list can be shared: members only have read access to it, and
 *  a server-side position would be one order imposed on everyone. */
@Singleton
class ListOrderStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(uid: String): List<String> =
        prefs.getString(key(uid), null)?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    fun save(uid: String, orderedListIds: List<String>) {
        prefs.edit().putString(key(uid), orderedListIds.joinToString(SEPARATOR)).apply()
    }

    private fun key(uid: String) = "order_$uid"

    private companion object {
        const val PREFS_NAME = "mytasklist_list_order"
        const val SEPARATOR = ","
    }
}
