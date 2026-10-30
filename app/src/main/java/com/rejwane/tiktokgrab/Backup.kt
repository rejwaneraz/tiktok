package com.rejwane.tiktokgrab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The app's own records — authors, play counts, likes, follows, shuffle order, theme — are small,
 * but losing them after a reinstall hurts. They are mirrored into
 * Download/TikTokGrab/Backup/meta/tiktokgrab.json, and a fresh install reads that file back before
 * the first screen draws.
 */
object Backup {

    private const val FOLDER = "meta"
    private const val NAME = "tiktokgrab.json"

    /**
     * Every store we keep. The hand-made tags are the reason this list has to stay complete: they are
     * the labels a person gave their own clips, and losing them on a reinstall is losing work.
     */
    private val stores = listOf("authors", "follows", "likes", "collection", "tags",
        "comment_likes", "foryou", "ui", "saved", "secuids")

    /** A value that is not text, tagged so it comes back as the type it was written as. */
    private const val KIND = "#"
    private const val TEXT = "v"

    /** The stores that say whether this install already has its own records. */
    private val core = listOf("saved", "authors", "ui")

    /** Written on its own thread: a screen leaving must not pay for a file write on the frame thread. */
    private val io = Executors.newSingleThreadExecutor()

    fun snapshot(context: Context) {
        val app = context.applicationContext
        io.execute { runCatching { Store.writeText(app, FOLDER, NAME, dump(app).toString()) } }
    }

    /**
     * Reading the backup file is the slow part, and an install that already has its records does not
     * need it — so on an ordinary launch nothing is read from disk at all. One empty store (tags,
     * likes) is not a fresh install; only the core ones decide.
     */
    fun restoreIfEmpty(context: Context) {
        val fresh = core.any { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isEmpty() }
        if (!fresh) return
        restore(context, force = false)
    }

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
                    is JSONObject -> when (value.optString(KIND)) {
                        // Written as a number or a switch, so it is read back as one. Restoring an Int
                        // as text is how the next getInt() crashed on a fresh install.
                        "int" -> editor.putInt(key, value.optString(TEXT).toIntOrNull() ?: 0)
                        "long" -> editor.putLong(key, value.optString(TEXT).toLongOrNull() ?: 0L)
                        "bool" -> editor.putBoolean(key, value.optString(TEXT) == "true")
                        "float" -> editor.putFloat(key, value.optString(TEXT).toFloatOrNull() ?: 0f)
                        else -> editor.putString(key, value.optString(TEXT))
                    }
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
                    // Everything else keeps its own type, because JSON would otherwise round-trip an
                    // Int as text and a Boolean would be dropped outright.
                    is Int -> out.put(key, tagged("int", value))
                    is Long -> out.put(key, tagged("long", value))
                    is Float -> out.put(key, tagged("float", value))
                    is Boolean -> out.put(key, tagged("bool", value))
                }
            }
            root.put(name, out)
        }
        return root
    }

    private fun tagged(kind: String, value: Any) =
        JSONObject().put(KIND, kind).put(TEXT, value.toString())
}
