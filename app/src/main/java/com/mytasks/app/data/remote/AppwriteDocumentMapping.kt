package com.mytasks.app.data.remote

import com.mytasks.app.data.model.ListMember
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Date

/**
 * Small manual mapping helpers shared by the Appwrite*Repository classes.
 *
 * Appwrite documents are just `Map<String, Any?>` (see io.appwrite.models.Document) -
 * per the migration plan, each repository maps its own model to/from that
 * map by hand rather than going through a generic serialization layer. This
 * file only factors out the two bits of encoding that are genuinely shared
 * across repositories: `datetime` attribute strings, and the JSON-encoded
 * `members` list on `lists` documents (Appwrite has no array-of-objects
 * attribute type - see AppwriteListRepository).
 */

/** Appwrite `datetime` attributes are ISO 8601 strings. */
internal fun Date.toAppwriteIso(): String = Instant.ofEpochMilli(time).toString()

/** Parses an Appwrite `datetime` attribute value back into a [Date],
 *  tolerating both the `Z`-suffixed and explicit-offset ISO forms Appwrite
 *  may return. Returns null for anything else (missing field, wrong type,
 *  unparseable string) rather than throwing - a malformed/absent due date
 *  should read as "no due date", not crash the mapping. */
internal fun Any?.asAppwriteDate(): Date? {
    val text = this as? String ?: return null
    return try {
        Date.from(Instant.parse(text))
    } catch (e: DateTimeParseException) {
        try {
            Date.from(OffsetDateTime.parse(text).toInstant())
        } catch (e2: DateTimeParseException) {
            null
        }
    }
}

/** Encodes the embedded member list as a JSON array string, since Appwrite
 *  has no array-of-objects attribute type for the `lists.members` field. */
internal fun encodeMembers(members: List<ListMember>): String {
    val array = JSONArray()
    members.forEach { member ->
        array.put(
            JSONObject().apply {
                put("uid", member.uid)
                put("displayName", member.displayName)
                put("email", member.email)
                put("photoUrl", member.photoUrl)
            },
        )
    }
    return array.toString()
}

/** Inverse of [encodeMembers]. Returns an empty list for anything that
 *  isn't a valid JSON array (missing field, wrong type, corrupt data). */
internal fun decodeMembers(value: Any?): List<ListMember> {
    val text = value as? String ?: return emptyList()
    return try {
        val array = JSONArray(text)
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            ListMember(
                uid = obj.optString("uid", ""),
                displayName = obj.optString("displayName", ""),
                email = obj.optString("email", ""),
                photoUrl = obj.optString("photoUrl", ""),
            )
        }
    } catch (e: org.json.JSONException) {
        emptyList()
    }
}
