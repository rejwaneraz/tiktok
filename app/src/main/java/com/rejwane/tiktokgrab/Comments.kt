package com.rejwane.tiktokgrab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One saved comment. `photos` are file names inside Backup/photos, in the order the comment shows them. */
data class Comment(
    val id: String,
    val name: String,
    val text: String,
    val seconds: Long,
    val likes: Long,
    val photos: List<String> = emptyList(),
    val replies: Int = 0,
    /** Where the pictures live at the reader — fetched only when the row is tapped. */
    val photoUrls: List<String> = emptyList()
) {
    fun ago(): String = GridAdapter.ago(seconds)
}

/**
 * What one pull actually did. `failed` is the part that used to be invisible: a reader that refuses,
 * rate-limits, or runs into a dead network returns nothing, and nothing looked exactly like "this
 * clip has no comments" — two very different things that both counted as zero.
 */
data class Fetched(val comments: List<Comment>, val failed: Boolean)

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
            Comment(
                id = o.optString("id"),
                name = o.optString("name"),
                text = o.optString("text"),
                seconds = o.optLong("t"),
                likes = o.optLong("l"),
                photos = listOfNotNull(o.opt("p")).flatMap { strings(it) },
                replies = o.optInt("r"),
                photoUrls = listOfNotNull(o.opt("u")).flatMap { strings(it) }
            )
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
     * what came. Blocking — call it off the main thread.
     */
    fun fetch(context: Context, clipId: String): Fetched {
        if (clipId.isBlank()) return Fetched(emptyList(), true)
        var kept = load(context, clipId)
        var cursor = 0L
        var pages = 0
        var retries = 0
        var answered = false
        while (kept.size < LIMIT && pages < 8) {
            pages++
            pace()
            val body = runCatching { JSONObject(Http.openGet(page(clipId, cursor))) }.getOrNull()
                ?: break
            val code = body.optInt("code", 0)
            // "code 0" is the reader saying "here is the clip". A one-per-second refusal is pacing,
            // not a dead clip: it is waited out and asked again instead of reported as a failure.
            if (code != 0) {
                if (retries < 2 && body.optString("msg").contains("limit", ignoreCase = true)) {
                    retries++
                    pages--
                    runCatching { Thread.sleep(1400) }
                    continue
                }
                break
            }
            answered = true
            val data = body.optJSONObject("data") ?: break
            val batch = data.optJSONArray("comments") ?: JSONArray()
            if (batch.length() == 0) break
            kept = merge(context, clipId, (0 until batch.length()).mapNotNull { i ->
                batch.optJSONObject(i)?.let { parse(context, clipId, it) }
            })
            if (kept.size >= LIMIT || !data.optBoolean("hasMore")) break
            cursor = data.optLong("cursor", cursor + PAGE)
        }
        return Fetched(kept, failed = !answered)
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
        val urls = allUrls(item.opt("images"))
        return Comment(
            id = id,
            name = user.optString("nickname").ifBlank { user.optString("unique_id") }
                .ifBlank { "TikTok user" },
            text = text,
            seconds = item.optLong("create_time"),
            likes = item.optLong("digg_count"),
            photos = savedPhotos(context, clipId, id, urls.size),
            replies = item.optInt("reply_total"),
            photoUrls = urls
        )
    }

    /** Every picture a comment carries, in order; the file name is index-based so the order holds. */
    private fun photoName(clipId: String, commentId: String, index: Int) =
        "${clipId}_${commentId}_$index.jpg"

    private fun savedPhotos(context: Context, clipId: String, id: String, count: Int): List<String> =
        (0 until count).mapNotNull { i ->
            val name = photoName(clipId, id, i)
            if (Store.exists(context, PHOTOS, name)) name else null
        }

    /**
     * Pictures are pulled when the comment is tapped, never during a batch: they are the part that
     * would quietly turn a 40 KB file into megabytes. Every picture the comment carries is saved.
     */
    fun downloadPhotos(context: Context, clipId: String, comment: Comment): Boolean {
        if (comment.photoUrls.isEmpty()) return false
        var got = false
        comment.photoUrls.forEachIndexed { i, url ->
            if (Store.download(context, PHOTOS, photoName(clipId, comment.id, i), url)) got = true
        }
        return got
    }

    /** Accepts a bare string, an array of strings, or an array of objects that carry one. */
    private fun allUrls(value: Any?): List<String> = when (value) {
        is String -> if (value.startsWith("http")) listOf(value) else emptyList()
        is JSONArray -> (0 until value.length()).flatMap { allUrls(value.opt(it)) }
        is JSONObject -> value.keys().asSequence().flatMap { allUrls(value.opt(it)) }.toList()
        else -> emptyList()
    }.distinct()

    /** Old files stored one string; new ones store an array, so both are read. */
    private fun strings(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it) }.filter { it.isNotBlank() }
        is String -> if (value.isBlank()) emptyList() else listOf(value)
        else -> emptyList()
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
                .put("p", JSONArray(comment.photos)).put("r", comment.replies)
                .put("u", JSONArray(comment.photoUrls)))
        }
        Store.writeText(context, FOLDER, "$clipId.json", array.toString())
    }
}
