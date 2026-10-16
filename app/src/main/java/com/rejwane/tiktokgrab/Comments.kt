package com.rejwane.tiktokgrab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One saved comment. `photo` is a file name inside Backup/photos, empty when there is none. */
data class Comment(
    val id: String,
    val name: String,
    val text: String,
    val seconds: Long,
    val likes: Long,
    val photo: String = "",
    val replies: Int = 0
) {
    fun ago(): String = GridAdapter.ago(seconds)
}

/**
 * TikTok guards its own comment endpoint behind a browser signature, so comments come from a public
 * reader instead: plain HTTP, no login, and it answers with the text, hearts, pictures and names the
 * page itself shows. One clip is one JSON file under Backup, so a reinstall cannot take it.
 */
object Comments {

    private const val FOLDER = "comments"
    private const val PHOTOS = "photos"
    private const val BASE = "https://www.tikwm.com/api/comment/list/"
    const val LIMIT = 100

    /** The reader allows one request per second on a free session, so pages are spaced out. */
    private const val PAGE = 50
    private const val PAUSE_MS = 1200L

    private var lastCall = 0L

    fun load(context: Context, clipId: String): List<Comment> {
        if (clipId.isBlank()) return emptyList()
        val raw = Store.readText(context, FOLDER, "$clipId.json") ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Comment(o.optString("id"), o.optString("name"), o.optString("text"),
                o.optLong("t"), o.optLong("l"), o.optString("p"), o.optInt("r"))
        }
    }

    fun has(context: Context, clipId: String) =
        clipId.isNotBlank() && Store.exists(context, FOLDER, "$clipId.json")

    /** How many clips already have a comment file. */
    fun stored(context: Context) = Store.count(context, FOLDER)

    /** Videos we saved before their comments came down — Settings can fetch these one by one. */
    fun missingFor(context: Context, videos: List<LocalVideo>) =
        videos.filter { it.clipId.isNotBlank() && !has(context, it.clipId) }

    /**
     * Up to LIMIT comments for one clip, written back page by page so stopping half way still keeps
     * what came. Blocking — call it off the main thread. An empty answer is a real one: the clip has
     * no comments, or they are turned off.
     */
    fun fetch(context: Context, clipId: String): List<Comment> {
        if (clipId.isBlank()) return emptyList()
        var kept = load(context, clipId)
        var cursor = 0L
        var pages = 0
        while (kept.size < LIMIT && pages < 8) {
            pages++
            pace()
            val data = runCatching {
                JSONObject(Http.openGet(page(clipId, cursor))).optJSONObject("data")
            }.getOrNull() ?: break
            val batch = data.optJSONArray("comments") ?: JSONArray()
            if (batch.length() == 0) break
            kept = merge(context, clipId, (0 until batch.length()).mapNotNull { i ->
                batch.optJSONObject(i)?.let { parse(context, clipId, it) }
            })
            if (kept.size >= LIMIT || !data.optBoolean("hasMore")) break
            cursor = data.optLong("cursor", cursor + PAGE)
        }
        return kept
    }

    /** Spacing is per request, not per clip: a batch of single-page clips must stay slow too. */
    private fun pace() {
        val since = System.currentTimeMillis() - lastCall
        if (since < PAUSE_MS) runCatching { Thread.sleep(PAUSE_MS - since) }
        lastCall = System.currentTimeMillis()
    }

    private fun page(clipId: String, cursor: Long) =
        "$BASE?url=https://www.tiktok.com/@v/video/$clipId&count=$PAGE&cursor=$cursor"

    private fun parse(context: Context, clipId: String, item: JSONObject): Comment? {
        val id = item.optString("id")
        val text = item.optString("text").trim()
        if (id.isBlank() || text.isBlank()) return null
        val user = item.optJSONObject("user") ?: JSONObject()
        return Comment(
            id = id,
            name = user.optString("nickname").ifBlank { user.optString("unique_id") }
                .ifBlank { "TikTok user" },
            text = text,
            seconds = item.optLong("create_time"),
            likes = item.optLong("digg_count"),
            photo = photoOf(context, clipId, id, item),
            replies = item.optInt("reply_total")
        )
    }

    /** Only pictures that a comment actually carries cost data — everything else stays generic. */
    private fun photoOf(context: Context, clipId: String, id: String, item: JSONObject): String {
        val url = firstUrl(item.opt("images")) ?: return ""
        val name = "${clipId}_$id.jpg"
        return if (Store.download(context, PHOTOS, name, url)) name else ""
    }

    private fun firstUrl(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.startsWith("http") }
        is JSONArray -> (0 until value.length()).mapNotNull { firstUrl(value.opt(it)) }.firstOrNull()
        is JSONObject -> value.keys().asSequence().mapNotNull { firstUrl(value.opt(it)) }.firstOrNull()
        else -> null
    }

    /** Keeps what we already had and adds what is new, then writes the whole set back. */
    private fun merge(context: Context, clipId: String, fresh: List<Comment>): List<Comment> {
        if (fresh.isEmpty()) return load(context, clipId)
        val byId = LinkedHashMap<String, Comment>()
        (load(context, clipId) + fresh).forEach {
            if (it.id.isNotBlank() && !byId.containsKey(it.id)) byId[it.id] = it
        }
        val merged = byId.values.take(LIMIT)
        save(context, clipId, merged)
        return merged
    }

    private fun save(context: Context, clipId: String, list: List<Comment>) {
        val array = JSONArray()
        list.forEach { comment ->
            array.put(JSONObject()
                .put("id", comment.id).put("name", comment.name).put("text", comment.text)
                .put("t", comment.seconds).put("l", comment.likes)
                .put("p", comment.photo).put("r", comment.replies))
        }
        Store.writeText(context, FOLDER, "$clipId.json", array.toString())
    }
}
