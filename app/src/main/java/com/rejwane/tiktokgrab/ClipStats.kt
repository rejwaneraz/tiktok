package com.rejwane.tiktokgrab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The numbers TikTok showed for one clip the last time we read its list. */
data class Stat(
    val plays: Long = 0L,
    val likes: Long = 0L,
    val comments: Long = 0L,
    val collects: Long = 0L,
    val shares: Long = 0L,
    val sound: String = "",
    val soundAuthor: String = "",
    /** Labels TikTok itself attached to the post, when the listing carries them. */
    val labels: List<String> = emptyList()
) {
    val known: Boolean get() = plays > 0 || likes > 0 || sound.isNotBlank()
}

/**
 * Kept per clip id, next to the author records. Frozen at listing time — re-listing the author
 * refreshes them, which is the only honest way to get them.
 */
object ClipStats {

    private const val PREF = "authors"
    private const val KEY = "clips"
    private const val LEGACY_VIEWS = "views"

    private var cache: JSONObject? = null
    private var legacy: JSONObject? = null

    private fun store(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun table(context: Context): JSONObject = synchronized(this) {
        cache ?: runCatching { JSONObject(store(context).getString(KEY, "{}")) }
            .getOrDefault(JSONObject()).also { cache = it }
    }

    private fun legacyPlays(context: Context): JSONObject = synchronized(this) {
        legacy ?: runCatching { JSONObject(store(context).getString(LEGACY_VIEWS, "{}")) }
            .getOrDefault(JSONObject()).also { legacy = it }
    }

    fun save(context: Context, stats: Map<String, Stat>) {
        if (stats.isEmpty()) return
        // The cached table is shared with every grid that is drawing: mutate and persist it under one
        // lock, or two pages arriving at once overwrite each other.
        synchronized(this) {
            val root = table(context)
            stats.forEach { (id, stat) ->
                if (id.isBlank()) return@forEach
                val old = root.optJSONObject(id)
                root.put(id, JSONObject()
                    .put("v", maxOf(stat.plays, old?.optLong("v") ?: 0L))
                    .put("l", maxOf(stat.likes, old?.optLong("l") ?: 0L))
                    .put("c", maxOf(stat.comments, old?.optLong("c") ?: 0L))
                    .put("k", maxOf(stat.collects, old?.optLong("k") ?: 0L))
                    .put("s", maxOf(stat.shares, old?.optLong("s") ?: 0L))
                    .put("m", stat.sound.ifBlank { old?.optString("m") ?: "" })
                    .put("a", stat.soundAuthor.ifBlank { old?.optString("a") ?: "" })
                    .put("t", if (stat.labels.isNotEmpty()) JSONArray(stat.labels)
                    else old?.optJSONArray("t") ?: JSONArray())
                )
            }
            store(context).edit().putString(KEY, root.toString()).apply()
            cache = root
        }
    }

    fun of(context: Context, clipId: String): Stat {
        if (clipId.isBlank()) return Stat()
        val o = table(context).optJSONObject(clipId)
        if (o == null) return Stat(plays = legacyPlays(context).optLong(clipId))
        return Stat(
            plays = o.optLong("v"),
            likes = o.optLong("l"),
            comments = o.optLong("c"),
            collects = o.optLong("k"),
            shares = o.optLong("s"),
            sound = o.optString("m"),
            soundAuthor = o.optString("a"),
            labels = o.optJSONArray("t")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            } ?: emptyList()
        )
    }
}

/** Authors the user chose to keep close; a local thing, TikTok is never told. */
object Follows {

    private const val PREF = "follows"
    private const val KEY = "names"

    private fun store(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(context: Context): Set<String> =
        store(context).getStringSet(KEY, emptySet()) ?: emptySet()

    fun isFollowing(context: Context, author: String) = author in all(context)

    fun toggle(context: Context, author: String): Boolean {
        val now = HashSet(all(context))
        val on = if (author in now) {
            now.remove(author); false
        } else {
            now.add(author); true
        }
        store(context).edit().putStringSet(KEY, now).apply()
        return on
    }
}

/** The bookmark: videos kept aside, by clip id so a re-download still counts. */
object Collection {

    private const val PREF = "collection"
    private const val KEY = "ids"

    private fun store(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun ids(context: Context): Set<String> = store(context).getStringSet(KEY, emptySet()) ?: emptySet()

    private fun key(video: LocalVideo) = video.clipId.ifBlank { video.key }

    fun has(context: Context, video: LocalVideo) = key(video) in ids(context)

    fun toggle(context: Context, video: LocalVideo): Boolean {
        val now = HashSet(ids(context))
        val on = if (key(video) in now) {
            now.remove(key(video)); false
        } else {
            now.add(key(video)); true
        }
        store(context).edit().putStringSet(KEY, now).apply()
        return on
    }

    fun inCollection(context: Context, videos: List<LocalVideo>) = videos.filter { has(context, it) }
}
