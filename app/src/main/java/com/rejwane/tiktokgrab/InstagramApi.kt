package com.rejwane.tiktokgrab

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Instagram, read without an account.
 *
 * Instagram answers a normal browser request with an empty shell and hands the real media to a
 * crawler — measured on 2026-10-30: the same address asked as Chrome is 511 KB of nothing, asked as
 * Googlebot it is ~0.86 MB carrying picture and video addresses. A session attached to that request
 * makes the payload disappear again, so this reader deliberately stays anonymous.
 *
 * The JSON APIs (web_profile_info, feed/user) answer 429/403 whatever headers or cookies are sent,
 * because Instagram judges the client rather than the session: a profile's list of posts can only come
 * out of a real browser — see [InstagramWeb]. What this file does is turn one known post into a Clip.
 */
object InstagramApi {

    private const val TAG = "tiktokgrab"
    private const val CRAWLER = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    const val REFERER = "https://www.instagram.com/"
    private const val SCRIPT = "<script type=\"application/json\"[^>]*>(.*?)</script>"

    private val reserved = setOf("p", "reel", "tv", "explore", "accounts", "direct", "stories",
        "about", "web", "developer", "legal", "help")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .build()

    fun isInstagram(input: String) = input.contains("instagram.com")

    /** /p/, /reel/ and the old /tv/ shapes all name exactly one post. */
    fun code(input: String): String? =
        Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]{11})").find(input)?.groupValues?.get(1)

    /** A profile address, or null for anything that is not one. */
    fun username(input: String): String? {
        val path = input.substringAfter("instagram.com/", "").trim('/')
        if (path.isBlank() || path.contains('?')) return null
        val first = path.substringBefore('/')
        if (first.isBlank() || first in reserved) return null
        return first
    }

    /**
     * Everything asked for, as clips the rest of the app already understands: pictures in `images`
     * for a slideshow, one `play` address for a video, never both — that is what makes a video play
     * instead of opening as a photo.
     */
    suspend fun read(web: InstagramWeb?, input: String, limit: Int,
                     onProgress: (Int, String) -> Unit): List<Clip> = withContext(Dispatchers.IO) {
        code(input)?.let { c ->
            onProgress(1, "Reading the Instagram post…")
            return@withContext listOf(clipFor(c))
        }
        val user = username(input)
            ?: throw RuntimeException("Instagram: paste a post, reel, or profile link")

        // A browser is the only thing Instagram will list a profile to. A screen closed mid-scan is
        // not a failed read: its cancellation is passed through instead of quietly falling back.
        val wanted = limit.coerceAtLeast(1)
        val codes = try {
            web?.profileCodes(user, wanted) ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "instagram profile scan failed: ${e.message}")
            emptyList()
        }
        if (codes.isNotEmpty()) {
            val out = ArrayList<Clip>()
            codes.take(wanted).forEachIndexed { i, (code, kind) ->
                onProgress(i + 1, "Instagram @$user — ${i + 1} of ${codes.size} posts read")
                val clip = runCatching { clipFor(code) }
                    .onFailure { Log.d(TAG, "instagram $code: ${it.message}") }
                    .getOrNull()
                // A post we could not read is skipped out loud rather than counted as a video that
                // does not exist; the count in the status line is what the user judges the pull by.
                if (clip != null) out.add(clip)
                if (kind == "r" && clip != null && clip.play.isBlank()) {
                    Log.d(TAG, "reel $code came back without a video address")
                }
            }
            if (out.isNotEmpty()) return@withContext out
        }
        onProgress(0, "Instagram @$user — no post links from the browser, reading the grid")
        gridClips(user)
    }

    /** One post, best quality: the biggest picture candidate and the biggest video it carries. */
    fun clipFor(code: String): Clip {
        val text = get("https://www.instagram.com/p/$code/")
        val root = payload(text) ?: throw RuntimeException(
            "Instagram did not answer this post — the account may be private or the link wrong")
        val post = mediaNode(root) ?: throw RuntimeException("Instagram sent no media for $code")
        val user = post.optJSONObject("user")?.optString("username").orEmpty()
            .ifBlank { Regex("\"username\":\"([\\w.]{1,32})\"").find(text)?.groupValues?.get(1) ?: "" }
            .ifBlank { "unknown" }
        val caption = post.optJSONObject("caption")?.optString("text").orEmpty()
            .ifBlank { post.optJSONArray("edge_media_to_caption")
                ?.optJSONObject(0)?.optString("text").orEmpty() }.trim()
        val items = post.optJSONArray("carousel_media").let { car ->
            if (car != null && car.length() > 0) (0 until car.length()).mapNotNull { car.optJSONObject(it) }
            else listOf(post)
        }
        val pictures = items.mapNotNull { bestPicture(it) }
        val video = bestVideo(post)
        val cover = bestPicture(post)
        val pk = (post.optString("pk").ifBlank { post.optString("id") }).removePrefix("POLARIS_")
            .ifBlank { code }
        val views = post.optLong("video_view_count")
            .takeIf { it > 0 } ?: post.optLong("video_views")
        return Clip(
            id = pk,
            title = caption,
            duration = post.optLong("video_duration").toInt(),
            stat = Stat(plays = views, likes = post.optLong("like_count"),
                comments = post.optLong("comment_count")),
            bytes = 0L,
            thumb = (cover ?: pictures.firstOrNull()).orEmpty(),
            // Both at once would read as a slideshow, so a video carries no image list at all.
            play = video.orEmpty(),
            page = "https://www.instagram.com/p/$code/",
            author = user,
            images = if (video.isNullOrBlank()) pictures else emptyList()
        )
    }

    /**
     * The last thing that works with no browser at all: the profile page carries its latest twelve
     * posts as picture addresses, with no post link and no video — covers only. A profile's reels are
     * not in this answer, which is why the browser path is tried first.
     */
    private fun gridClips(user: String): List<Clip> {
        val text = get("https://www.instagram.com/$user/")
        val root = payload(text) ?: throw RuntimeException(
            "Instagram would not list @$user — the profile may be private")
        val nodes = mediaNodes(root)
        // A slideshow's own slides look very much like posts — sometimes with the same keys and always
        // with their own id — so the parents are named first and their children are dropped by id,
        // instead of guessing from how many keys a node happens to carry.
        val children = HashSet<String>()
        nodes.forEach { parent ->
            val car = parent.optJSONArray("carousel_media") ?: return@forEach
            (0 until car.length()).mapNotNull { car.optJSONObject(it) }.forEach { child ->
                val id = child.optString("pk").ifBlank { child.optString("id") }
                    .removePrefix("POLARIS_")
                if (id.isNotBlank()) children.add(id)
            }
        }
        val out = ArrayList<Clip>()
        val seen = HashSet<String>()
        nodes.forEach { node ->
            val pk = (node.optString("pk").ifBlank { node.optString("id") }).removePrefix("POLARIS_")
            if (pk.isBlank() || pk in children || node.has("carousel_parent_id")) return@forEach
            if (!seen.add(pk)) return@forEach
            val items = node.optJSONArray("carousel_media").let { car ->
                if (car != null && car.length() > 0) (0 until car.length()).mapNotNull { car.optJSONObject(it) }
                else listOf(node)
            }
            val pictures = items.mapNotNull { bestPicture(it) }
            if (pictures.isEmpty()) return@forEach
            out.add(Clip(
                id = pk,
                title = node.optJSONObject("caption")?.optString("text").orEmpty().trim(),
                duration = 0,
                stat = Stat(likes = node.optLong("like_count")),
                bytes = 0L,
                thumb = pictures.first(),
                // Covers only: a grid node never carries a video address, so this is a slideshow by
                // the app's own rule and the player is never asked to stream a cover.
                play = "",
                page = "https://www.instagram.com/$user/",
                author = user,
                images = pictures
            ))
        }
        if (out.isEmpty()) throw RuntimeException("@$user: Instagram's profile page carried no pictures")
        return out
    }

    private fun get(url: String): String {
        val call = Request.Builder().url(url).header("User-Agent", CRAWLER)
            .header("Accept-Language", "en-US,en;q=0.9").build()
        client.newCall(call).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RuntimeException("Instagram HTTP ${response.code}")
            return body
        }
    }

    /** The biggest embedded JSON block that actually carries media. */
    private fun payload(text: String): JSONObject? {
        var best: JSONObject? = null
        Regex(SCRIPT, RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
            val node = runCatching { JSONObject(m.groupValues[1]) }.getOrNull() ?: return@forEach
            val blob = node.toString()
            if ((blob.contains("image_versions2") || blob.contains("video_versions")) &&
                (best == null || blob.length > best!!.toString().length)
            ) best = node
        }
        return best
    }

    /** Every dict that holds media, however deep it sits. */
    private fun mediaNodes(node: Any, sink: MutableList<JSONObject> = ArrayList()): List<JSONObject> {
        if (node is JSONObject) {
            if (node.has("image_versions2") || node.has("video_versions") || node.has("carousel_media"))
                sink.add(node)
            node.keys().forEach { key -> mediaNodes(node.opt(key) ?: Unit, sink) }
        } else if (node is org.json.JSONArray) {
            (0 until node.length()).forEach { mediaNodes(node.opt(it) ?: Unit, sink) }
        }
        return sink
    }

    /** The post itself: the node that carries the most, counting a slideshow's own item list. */
    private fun mediaNode(root: JSONObject): JSONObject? =
        mediaNodes(root).filter {
            it.has("image_versions2") || it.has("video_versions") || it.has("carousel_media")
        }.sortedByDescending { it.length() }.firstOrNull()

    private fun candidates(node: JSONObject): List<JSONObject> {
        val versions = (node.optJSONObject("image_versions2") ?: JSONObject())
            .optJSONArray("candidates") ?: return emptyList()
        return (0 until versions.length()).mapNotNull { versions.optJSONObject(it) }
    }

    /**
     * The best picture a node carries. A carousel item on a post page lists no width at all, and there
     * the address itself says what it is: a size token means a resized copy, no token means the file
     * as it was encoded.
     */
    private fun bestPicture(node: JSONObject): String? =
        candidates(node).maxByOrNull { rank(it) }?.optString("url")?.takeIf { it.startsWith("http") }

    private fun bestVideo(node: JSONObject): String? {
        val versions = node.optJSONArray("video_versions") ?: return null
        if (versions.length() == 0) return null
        val best = (0 until versions.length()).mapNotNull { versions.optJSONObject(it) }
            .maxByOrNull { (it.optLong("width") * it.optLong("height")).coerceAtLeast(1L) }
        return best?.optString("url")?.takeIf { it.startsWith("http") }
    }

    private fun rank(candidate: JSONObject): Long {
        val w = candidate.optLong("width")
        val h = candidate.optLong("height")
        if (w > 0 && h > 0) return w * h
        val token = Regex("[p_](\\d{3,4})x(\\d{3,4})").find(candidate.optString("url"))
        return if (token != null) token.groupValues[1].toLong() * token.groupValues[2].toLong()
        else 12_000_000L
    }
}
