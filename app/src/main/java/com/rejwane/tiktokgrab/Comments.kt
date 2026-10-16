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
 * The first 100 comments of a clip, pulled while we are online anyway and kept as a file, so the
 * sheet works on a plane. TikTok's web comment endpoint takes the same session as the video list.
 */
object Comments {

    private const val FOLDER = "comments"
    private const val PHOTOS = "photos"
    private const val BASE = "https://www.tiktok.com/api/comment/list/"
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

    fun has(context: Context, clipId: String) = clipId.isNotBlank() && Store.exists(context, FOLDER, "$clipId.json")

    /** Empty on purpose is a real answer — the caller then says so instead of retrying forever. */
    fun fetch(context: Context, clipId: String): List<Comment> {
        if (clipId.isBlank()) return emptyList()
        val out = ArrayList<Comment>()
        var cursor = ""
        var guard = 0
        while (out.size < LIMIT && guard < 12) {
            guard++
            val json = runCatching { JSONObject(Http.get(page(clipId, cursor))) }.getOrNull() ?: break
            val batch = json.optJSONArray("comments") ?: JSONArray()
            for (i in 0 until batch.length()) {
                val item = batch.optJSONObject(i) ?: continue
                if (out.size >= LIMIT) break
                out += parse(context, clipId, item)
            }
            val more = json.optBoolean("has_more") || json.optString("has_more") == "1"
            cursor = json.optString("cursor").ifBlank { cursor }
            if (batch.length() == 0 || !more) break
        }
        if (out.isNotEmpty()) save(context, clipId, out)
        return out
    }

    private fun page(clipId: String, cursor: String) =
        BASE + "?aweme_id=$clipId&count=20&cursor=$cursor&aid=1988&app_name=tiktok_web" +
            "&device_platform=web_pc&cookie_enabled=true&channel=tiktok_web&os=windows" +
            "&language=en&msToken=" + Regex("msToken=([^;]+)").find(Cookies.header())?.groupValues?.get(1).orEmpty()

    private fun parse(context: Context, clipId: String, item: JSONObject): Comment {
        val user = item.optJSONObject("user") ?: JSONObject()
        val id = item.optString("cid").ifBlank { item.optString("comment_id") }
        val photo = photoOf(context, clipId, id, item)
        return Comment(
            id = id,
            name = user.optString("nickname").ifBlank { "TikTok user" },
            text = item.optString("text").trim(),
            seconds = item.optLong("createTime"),
            likes = item.optLong("diggCount", item.optLong("likeCount")),
            photo = photo,
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

    /** How many clips already have a comment file. */
    fun stored(context: Context) = Store.count(context, FOLDER)

    /** The first 100 for a clip we saved earlier but never pulled comments for. */
    fun missingFor(context: Context, videos: List<LocalVideo>) =
        videos.filter { it.clipId.isNotBlank() && !has(context, it.clipId) }
}
