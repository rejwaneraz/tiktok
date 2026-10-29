package com.rejwane.tiktokgrab

import android.content.Context
import org.json.JSONObject

/**
 * One video, resolved without a browser. TikTok keeps its profile page behind a JavaScript check,
 * but a single link still answers over plain HTTP through the same public reader that brings the
 * comments — so a pasted link still works even when a profile page would not open.
 */
object SingleClip {

    private const val BASE = "https://www.tikwm.com/api/"

    fun clip(context: Context, videoId: String, pageUrl: String = ""): Clip? {
        if (videoId.isBlank()) return null
        // The link we were given knows whether this is a /photo/ or a /video/ post; guessing costs a
        // failed lookup. "v" is the anonymous form the reader accepts for anything.
        val page = pageUrl.takeIf { it.contains("/photo/") || it.contains("/video/") }
            ?: "https://www.tiktok.com/@v/video/$videoId"
        val data = runCatching {
            JSONObject(Http.openGet("$BASE?url=$page&hd=1")).optJSONObject("data")
        }.getOrNull() ?: return null
        val images = imagesOf(data.opt("images"))
        // A photo post still ships its music as `play`; the pictures are what it is, so the audio is
        // dropped and the post is treated as a slideshow.
        val photo = images.isNotEmpty()
        val play = if (photo) "" else data.optString("hdplay")
            .ifBlank { data.optString("play") }.ifBlank { data.optString("wmplay") }
        if (play.isBlank() && images.isEmpty()) return null
        val author = data.optJSONObject("author") ?: JSONObject()
        val user = author.optString("unique_id")
        if (user.isNotBlank()) runCatching {
            Authors.captureItem(context, JSONObject()
                .put("uniqueId", user)
                .put("nickname", author.optString("nickname"))
                .put("avatarThumb", author.optString("avatar")))
        }
        val music = data.optJSONObject("music") ?: JSONObject()
        return Clip(
            id = data.optString("id").ifBlank { videoId },
            title = data.optString("title").trim(),
            duration = data.optInt("duration"),
            stat = Stat(
                plays = data.optLong("play_count"),
                likes = data.optLong("digg_count"),
                comments = data.optLong("comment_count"),
                collects = data.optLong("collect_count"),
                shares = data.optLong("share_count"),
                sound = music.optString("title"),
                soundAuthor = music.optString("authorName")
            ),
            bytes = if (photo) 0L else (data.optLong("hd_size").takeIf { it > 0 } ?: data.optLong("size")),
            thumb = data.optString("origin_cover").ifBlank { data.optString("cover") }
                .ifBlank { images.firstOrNull().orEmpty() },
            play = play,
            page = page,
            author = user,
            images = images
        )
    }

    /** The reader lists a photo post as an array of urls, or of objects that carry one. */
    private fun imagesOf(value: Any?): List<String> = when (value) {
        is String -> if (value.startsWith("http")) listOf(value) else emptyList()
        is org.json.JSONArray -> (0 until value.length()).flatMap { imagesOf(value.opt(it)) }
        is JSONObject -> listOf(
            value.optString("url"),
            value.optString("display_image"),
            value.optString("image_url")
        ).firstOrNull { it.startsWith("http") }?.let { listOf(it) } ?: emptyList()
        else -> emptyList()
    }.distinct()
}
