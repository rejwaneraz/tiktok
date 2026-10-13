package com.rejwane.tiktokgrab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LibraryItem(val id: String, val title: String, val thumb: String,
                       val bytes: Long, val uri: String)

/** Downloads are recorded here so the Library tab can show and reopen them. */
object Library {

    private const val PREF = "library"
    private const val KEY = "items"

    fun all(context: Context): List<LibraryItem> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            LibraryItem(o.optString("id"), o.optString("title"), o.optString("thumb"),
                o.optLong("bytes"), o.optString("uri"))
        }
    }

    fun add(context: Context, clip: Clip, uri: String) {
        if (uri.isBlank() || all(context).any { it.id == clip.id }) return
        val items = JSONArray()
        all(context).forEach { item ->
            items.put(JSONObject().put("id", item.id).put("title", item.title)
                .put("thumb", item.thumb).put("bytes", item.bytes).put("uri", item.uri))
        }
        items.put(JSONObject().put("id", clip.id).put("title", clip.title)
            .put("thumb", clip.thumb).put("bytes", clip.bytes).put("uri", uri))
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, items.toString()).apply()
    }
}
