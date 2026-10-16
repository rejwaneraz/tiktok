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
 * TikTok guards its comment API behind a browser signature, so we never call it ourselves: the
 * video page inside our hidden WebView asks for its own comments and we keep a copy of what it is
 * given (see TikTokWeb.captureComments). Each clip's set is one JSON file under Backup, so a
 * reinstall cannot take it.
 */
object Comments {

    private const val FOLDER = "comments"
    private const val PHOTOS = "photos"
    const val LIMIT = 100

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

    /** Videos we saved before comments were captured — Settings can fetch these one by one. */
    fun missingFor(context: Context, videos: List<LocalVideo>) =
        videos.filter { it.clipId.isNotBlank() && !has(context, it.clipId) }

    /** One page of comments from the page's own request; keeps what is new, up to LIMIT. */
    fun absorb(context: Context, clipId: String, body: String): Int {
        val batch = runCatching { JSONObject(body).optJSONArray("comments") }.getOrNull() ?: JSONArray()
        val fresh = (0 until batch.length()).mapNotNull { i ->
            batch.optJSONObject(i)?.let { parse(context, clipId, it) }
        }
        return merge(context, clipId, fresh)
    }

    /** How many comments a clip already holds, which is what the capture loop counts on. */
    fun count(context: Context, clipId: String) = load(context, clipId).size

    /**
     * Comments read back off the page itself when no API reply ever passed through. Names, text,
     * hearts and pictures are there; a time only when the page shows one.
     */
    fun absorbDom(context: Context, clipId: String, raw: String): Int {
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val fresh = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("t").trim()
            val name = o.optString("n").ifBlank { "TikTok user" }
            if (text.length < 2) return@mapNotNull null
            val id = "d" + (name + text).hashCode()
            val picture = o.optString("p")
            val saved = if (picture.startsWith("http") &&
                Store.download(context, PHOTOS, "${clipId}_$id.jpg", picture)) "${clipId}_$id.jpg" else ""
            Comment(
                id = id,
                name = name,
                text = text,
                seconds = stampOf(o.optString("w")),
                likes = heartsOf(o.optString("l")),
                photo = saved
            )
        }
        return merge(context, clipId, fresh)
    }

    private fun merge(context: Context, clipId: String, fresh: List<Comment>): Int {
        if (fresh.isEmpty()) return count(context, clipId)
        val byId = LinkedHashMap<String, Comment>()
        (load(context, clipId) + fresh).forEach {
            if (it.id.isNotBlank() && !byId.containsKey(it.id)) byId[it.id] = it
        }
        val merged = byId.values.take(LIMIT)
        save(context, clipId, merged)
        return merged.size
    }

    /** "1.2K" on the page is 1200 to us; a bare number comes through untouched. */
    private fun heartsOf(text: String): Long {
        val value = text.trim().uppercase().replace(",", "")
        val number = value.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0
        val suffix = value.lastOrNull()?.takeIf { it.isLetter() }
        return (number * when (suffix) {
            'K' -> 1_000.0
            'M' -> 1_000_000.0
            'B' -> 1_000_000_000.0
            else -> 1.0
        }).toLong()
    }

    /** The page shows a datetime attribute, sometimes only as seconds since 1970. */
    private fun stampOf(text: String): Long {
        val value = text.trim()
        if (value.isEmpty()) return 0
        value.toLongOrNull()?.let { return if (it > 1_000_000_000_000L) it / 1000 else it }
        val plain = value.take(19)
        val format = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        }.getOrNull() ?: return 0
        return runCatching { format.parse(plain)?.time / 1000L }.getOrDefault(0L)
    }

    private fun parse(context: Context, clipId: String, item: JSONObject): Comment {
        val user = item.optJSONObject("user") ?: JSONObject()
        val id = item.optString("cid").ifBlank { item.optString("comment_id") }
        return Comment(
            id = id,
            name = user.optString("nickname").ifBlank { "TikTok user" },
            text = item.optString("text").trim(),
            seconds = item.optLong("createTime"),
            likes = item.optLong("diggCount", item.optLong("likeCount")),
            photo = photoOf(context, clipId, id, item),
            replies = item.optInt("replyCount")
        )
    }

    /** Only pictures that a comment actually carries cost data — everything else stays generic. */
    private fun photoOf(context: Context, clipId: String, id: String, item: JSONObject): String {
        val url = firstUrl(item.opt("imageList")) ?: firstUrl(item.opt("image")) ?: return ""
        val name = "${clipId}_$id.jpg"
        return if (Store.download(context, PHOTOS, name, url)) name else ""
    }

    private fun firstUrl(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.startsWith("http") }
        is JSONArray -> (0 until value.length()).mapNotNull { firstUrl(value.opt(it)) }.firstOrNull()
        is JSONObject -> value.keys().asSequence().mapNotNull { firstUrl(value.opt(it)) }.firstOrNull()
        else -> null
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
