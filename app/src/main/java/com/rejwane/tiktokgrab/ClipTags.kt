package com.rejwane.tiktokgrab

import android.content.Context
import org.json.JSONObject

/**
 * The tags the user puts on a clip by hand. They win over the guessed ones from [Tags]: a person
 * saying "this is a school video" is better evidence than a caption that never mentioned school.
 *
 * Kept by clip id (the file name's id, or the file name itself when there is none), so a re-download
 * or a rename does not lose the label.
 */
object ClipTags {

    private const val PREF = "tags"
    private const val KEY = "by_clip"
    private const val LABELS = "known"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Stable key for a clip: its id when it has one, otherwise its file name. */
    fun key(clipId: String, name: String) = clipId.ifBlank { "file:$name" }

    fun of(context: Context, key: String): List<String> {
        if (key.isBlank()) return emptyList()
        val table = runCatching { JSONObject(prefs(context).getString(KEY, "{}")) }.getOrDefault(JSONObject())
        return table.optString(key).split('|').filter { it.isNotBlank() }
    }

    fun set(context: Context, key: String, labels: List<String>) {
        if (key.isBlank()) return
        val table = runCatching { JSONObject(prefs(context).getString(KEY, "{}")) }.getOrDefault(JSONObject())
        val clean = labels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        table.put(key, clean.joinToString("|"))
        prefs(context).edit().putString(KEY, table.toString()).apply()
        clean.forEach { remember(context, it) }
    }

    /** Every tag ever used, so the picker offers them instead of asking for typing each time. */
    fun known(context: Context): List<String> =
        (prefs(context).getStringSet(LABELS, emptySet()) ?: emptySet()).sorted()

    fun remember(context: Context, label: String) {
        val name = label.trim()
        if (name.isBlank()) return
        val now = HashSet(prefs(context).getStringSet(LABELS, emptySet()) ?: emptySet())
        if (now.add(name)) prefs(context).edit().putStringSet(LABELS, now).apply()
    }

    /** How many clips carry a hand-made tag, for the Settings row. */
    fun taggedCount(context: Context): Int {
        val table = runCatching { JSONObject(prefs(context).getString(KEY, "{}")) }.getOrDefault(JSONObject())
        var count = 0
        table.keys().forEach { key -> if (table.optString(key).isNotBlank()) count++ }
        return count
    }

    /** Bulk add: one collection name onto many clips at once, which is how a collection is made. */
    fun addAll(context: Context, keys: List<String>, label: String) {
        val name = label.trim()
        if (name.isBlank() || keys.isEmpty()) return
        remember(context, name)
        val table = runCatching { JSONObject(prefs(context).getString(KEY, "{}")) }.getOrDefault(JSONObject())
        keys.filter { it.isNotBlank() }.forEach { key ->
            val now = table.optString(key).split('|').filter { it.isNotBlank() }.toMutableList()
            if (name !in now) now.add(name)
            table.put(key, now.joinToString("|"))
        }
        prefs(context).edit().putString(KEY, table.toString()).apply()
    }

    /** How many saved clips a collection holds, for the Explore chip. */
    fun countIn(context: Context, label: String): Int {
        val table = runCatching { JSONObject(prefs(context).getString(KEY, "{}")) }.getOrDefault(JSONObject())
        var count = 0
        table.keys().forEach { key ->
            if (table.optString(key).split('|').contains(label)) count++
        }
        return count
    }
}
