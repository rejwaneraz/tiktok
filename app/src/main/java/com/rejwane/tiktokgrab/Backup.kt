package com.rejwane.tiktokgrab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's own records — authors, play counts, likes, follows, shuffle order, theme — are small,
 * but losing them after a reinstall hurts. They are mirrored into
 * Download/TikTokGrab/Backup/meta/tiktokgrab.json, and a fresh install reads that file back before
 * the first screen draws.
 */
object Backup {

    private const val FOLDER = "meta"
    private const val NAME = "tiktokgrab.json"

    /** Every store we keep; all of them only ever hold strings or string sets. */
    private val stores = listOf("authors", "follows", "likes", "collection", "foryou", "ui", "saved", "secuids")

    fun snapshot(context: Context) {
        val json = runCatching { dump(context).toString() }.getOrNull() ?: return
        Store.writeText(context, FOLDER, NAME, json)
    }

    fun restoreIfEmpty(context: Context) = restore(context, force = false)

    /** Without force this only fills stores that are still empty, so a live install is untouched. */
    fun restore(context: Context, force: Boolean) {
        val raw = Store.readText(context, FOLDER, NAME) ?: return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        stores.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (!force && prefs.all.isNotEmpty()) return@forEach
            val store = json.optJSONObject(name) ?: return@forEach
            val editor = prefs.edit()
            store.keys().forEach { key ->
                when (val value = store.get(key)) {
                    is JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.getString(it) }.toSet())
                    is String -> editor.putString(key, value)
                }
            }
            editor.apply()
        }
    }

    /** How much is already parked in the Downloads folder. */
    fun recordCount(context: Context) =
        Store.count(context, "comments") + Authors.cachedAvatars(context)

    private fun dump(context: Context): JSONObject {
        val root = JSONObject()
        stores.forEach { name ->
            val out = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                when (value) {
                    is Set<*> -> {
                        val items = JSONArray()
                        value.forEach { items.put(it?.toString() ?: "") }
                        out.put(key, items)
                    }
                    is String -> out.put(key, value)
                    is Number -> out.put(key, value.toString())
                }
            }
            root.put(name, out)
        }
        return root
    }
}
