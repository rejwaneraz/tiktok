package com.rejwane.tiktokgrab

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Clip(
    val id: String,
    val title: String,
    val duration: Int,
    val stat: Stat,
    val bytes: Long,
    val thumb: String,
    val play: String,
    val page: String,
    val author: String = "",
    /** A photo (slideshow) post carries pictures instead of a video. */
    val images: List<String> = emptyList()
) {
    val isPhoto: Boolean get() = play.isBlank() && images.isNotEmpty()
}

/** clips + the link the user typed, with any short link already expanded. match is set when a
 *  specific video link was given and found. */
data class Listing(val clips: List<Clip>, val source: String, val match: Clip? = null,
                   val searchedPages: Int = 0)

object TikTokApi {

    private const val TAG = "tiktokgrab"
    private const val ITEM_LIST = "https://www.tiktok.com/api/creator/item_list/"
    private const val PAGE = 15          // TikTok rejects any other count
    private const val SEARCH_PAGES = 12  // a video link searches back this far (~5 MB worst case)
    private const val PREF = "secuids"

    private val PROFILE = Regex("tiktok\\.com/@([\\w.\\-]+)")

    /**
     * Accepts "@user", "user", profile/video URLs, scheme-less short links, and full share text
     * ("… This post is shared via TikTok Lite …") by keeping only the first link in it.
     */
    private fun normalize(input: String): String {
        var text = Regex("https?://\\S+").find(input)?.value ?: input.trim().trimEnd('/')
        if (text.startsWith("@") || !text.contains('/')) return text
        if (!text.startsWith("http")) text = "https://" + text.removePrefix("//")
        if (PROFILE.containsMatchIn(text)) return text
        return runCatching { Http.finalUrl(text) }.getOrDefault(text)
    }

    /** Accepts "@user", "user", a profile URL, or a video link — always lands on the author. */
    fun username(input: String): String {
        val raw = input.trim()
        PROFILE.find(raw)?.let { return it.groupValues[1] }
        Regex("@([\\w.\\-]+)").find(raw)?.let { return it.groupValues[1] }
        if (raw.startsWith("http")) return raw.trimEnd('/').substringAfterLast('/').removePrefix("@")
        return raw.removePrefix("@").substringBefore("/").trim()
    }

    private fun msToken(): String =
        Regex("msToken=([^;]+)").find(Cookies.header())?.groupValues?.get(1) ?: ""

    private fun urlOf(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> if (value.length() > 0) urlOf(value.get(0)) else ""
        is JSONObject -> {
            val list = value.optJSONArray("URLList") ?: value.optJSONArray("urlList")
            when {
                list != null && list.length() > 0 -> urlOf(list.get(0))
                else -> value.optString("Url").ifBlank { value.optString("url") }
            }
        }
        else -> ""
    }

    private fun bitrateOf(url: String): Long =
        Regex("[?&]bt=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /** Always the best available: TikTok's own rendition list, highest bitrate wins. */
    private fun bestSource(video: JSONObject): String {
        val candidates = ArrayList<Pair<Long, String>>()
        fun add(url: String) {
            if (url.isNotBlank()) candidates.add(bitrateOf(url) to url)
        }
        add(urlOf(video.opt("playAddr")))
        video.optJSONArray("bitrateInfo")?.let { list ->
            for (i in 0 until list.length()) {
                add(urlOf(list.optJSONObject(i)?.opt("PlayAddr")))
            }
        }
        return candidates.maxByOrNull { it.first }?.second ?: ""
    }

    /**
     * Counts arrive in `stats` as numbers and, on some responses, in `statsV2` as strings. Reading
     * only the first shape left every count at zero — which quietly made "Popular" do nothing.
     */
    private fun statOf(item: JSONObject): Stat {
        val plain = item.optJSONObject("stats") ?: JSONObject()
        val text = item.optJSONObject("statsV2") ?: JSONObject()
        fun count(key: String): Long {
            val direct = plain.optLong(key, 0L)
            if (direct > 0) return direct
            val digits = text.optString(key).filter { it.isDigit() }
            return digits.toLongOrNull() ?: 0L
        }
        val music = item.optJSONObject("music") ?: JSONObject()
        return Stat(
            plays = count("playCount"),
            likes = count("diggCount"),
            comments = count("commentCount"),
            collects = count("collectCount"),
            shares = count("shareCount"),
            sound = music.optString("title"),
            soundAuthor = music.optString("authorName"),
            labels = labelsOf(item)
        )
    }

    /**
     * TikTok sometimes ships its own labels with a post. They cost nothing when they are there and
     * are simply empty when they are not — the Explore tags fall back to the caption's words.
     */
    private fun labelsOf(item: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        item.optJSONArray("diversificationLabels")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        item.optJSONObject("videoTag")?.let { tag ->
            listOf("videoTagName", "tagName", "name").forEach { key ->
                tag.optString(key).trim().takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        item.optString("contentLabel").trim().takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out.toList().take(8)
    }

    private fun parse(context: Context, data: JSONObject, user: String): List<Clip> {
        val status = data.optInt("statusCode", 0)
        if (status != 0) throw RuntimeException("TikTok API: ${data.optString("statusMsg", "$status")}")
        val items = data.optJSONArray("itemList") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            if (item.optBoolean("privateItem") || item.optBoolean("secret")) return@mapNotNull null
            val video = item.optJSONObject("video") ?: JSONObject()
            val author = item.optJSONObject("author") ?: JSONObject()
            Authors.captureItem(context, author)
            Clip(
                id = item.optString("id"),
                title = item.optString("desc").trim(),
                duration = video.optInt("duration", item.optInt("duration")),
                stat = statOf(item),
                bytes = video.optLong("size").takeIf { it > 0 }
                    ?: video.optJSONArray("bitrateInfo")?.optJSONObject(0)?.optJSONObject("PlayAddr")?.optLong("DataSize") ?: 0L,
                thumb = urlOf(video.opt("cover")).ifBlank { urlOf(video.opt("originCover")) },
                play = bestSource(video).ifBlank { urlOf(video.opt("downloadAddr")) },
                page = "https://www.tiktok.com/@$user/video/${item.optString("id")}",
                author = author.optString("uniqueId").ifBlank { user },
                images = imagesOf(item)
            )
        }
    }

    /**
     * A photo post lists its pictures under `imagePost.images`, each with a display URL. The shapes
     * differ between responses, so the first non-empty of the known keys wins and an absent post
     * simply yields nothing.
     */
    private fun imagesOf(item: JSONObject): List<String> {
        val post = item.optJSONObject("imagePost") ?: return emptyList()
        val arr = post.optJSONArray("images") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val image = arr.optJSONObject(i) ?: continue
            val url = urlOf(image.opt("displayImage"))
                .ifBlank { urlOf(image.opt("thumbnail")) }
                .ifBlank { urlOf(image.opt("imageURL")) }
            if (url.isNotBlank()) out.add(url)
        }
        return out
    }

    /** Walks the page JSON to the profile owner's own secUid, and refuses another account's id. */
    private fun secUidFromData(json: String, user: String): String {
        if (json.isBlank()) return ""
        runCatching {
            val profile = JSONObject(json).optJSONObject("__DEFAULT_SCOPE__")
                ?.optJSONObject("webapp.user-detail")?.optJSONObject("userInfo")
                ?.optJSONObject("user") ?: return@runCatching
            if (!profile.optString("uniqueId").equals(user, true)) return@runCatching
            val uid = profile.optString("secUid")
            if (uid.isNotBlank()) return uid
        }
        return ""
    }

    /**
     * Only the profile page needs a real browser, so this runs once per account and the id is
     * kept forever — every later listing is plain HTTP.
     */
    private suspend fun secUid(context: Context, web: TikTokWeb, user: String): String {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val key = "v2_$user"
        prefs.getString(key, null)?.let { return it }

        val json = withContext(Dispatchers.Main) {
            web.openProfile(user)
            web.pageData()
        }
        val uid = secUidFromData(json, user)
        if (uid.isBlank()) {
            throw RuntimeException("@$user — no data for this profile (private, or check login/internet)")
        }
        Authors.captureFromPage(context, user, json)
        prefs.edit().putString(key, uid).apply()
        Log.d(TAG, "cached secUid for $user")
        return uid
    }

    /** Cached secUids go stale when an account is renamed or made private — this forces a re-read. */
    fun clearCache(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()

    /**
     * One small page read answers the only question that matters about a session: does TikTok still
     * reply. Null means yes; anything else is the reason to show. Blocking — call off the main thread.
     */
    fun probe(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val uid = prefs.all.values.filterIsInstance<String>().firstOrNull { it.startsWith("MS4w") }
        if (uid.isNullOrBlank()) "no profile has been listed yet"
        else {
            val rows = JSONObject(Http.get(pageUrl(uid, ""))).optJSONArray("itemList")?.length() ?: 0
            if (rows > 0) null else "TikTok answered with no videos"
        }
    }.getOrElse { it.message ?: "read failed" }

    private fun pageUrl(uid: String, cursor: String) =
        ITEM_LIST + "?secUid=$uid&count=$PAGE&cursor=$cursor&type=1&source=0&aid=1988" +
            "&app_name=tiktok_web&device_platform=web_pc&channel=tiktok_web&cookie_enabled=true" +
            "&os=windows&language=en&msToken=" + msToken()

    suspend fun list(context: Context, web: TikTokWeb, input: String, limit: Int,
                     onPage: (List<Clip>) -> Unit = {},
                     onProgress: (Int) -> Unit): Listing = withContext(Dispatchers.IO) {
        val source = normalize(input)
        val user = username(source)
        if (user.isBlank()) throw RuntimeException("username not found")

        val wantedId = Regex("/video/(\\d+)").find(source)?.groupValues?.get(1)

        // A link that names one video is answered as that one video — no profile read, no author
        // shelf, nothing else in the grid. The reader returns it over plain HTTP in about a second.
        if (wantedId != null) {
            val one = runCatching { SingleClip.clip(context, wantedId) }.getOrNull()
            if (one != null) {
                Log.d(TAG, "single clip $wantedId resolved without a profile read")
                return@withContext Listing(listOf(one), source, one, 1)
            }
        }

        val uid = runCatching { secUid(context, web, user) }.getOrNull().orEmpty()
        if (uid.isBlank()) {
            // The profile page is the only thing TikTok's browser check guards, so a link that names
            // one video can still be answered over plain HTTP instead of giving up.
            val one = wantedId?.let { SingleClip.clip(context, it) }
            if (one != null) return@withContext Listing(listOf(one), source, one, 1)
            throw RuntimeException("@$user — TikTok would not open the profile page. Check the name " +
                "is public, or log in again from Settings.")
        }
        // 0 means everything the account has: pages keep coming until TikTok says there are none.
        val wanted = if (limit <= 0) Int.MAX_VALUE else limit
        val out = ArrayList<Clip>()
        val seen = HashSet<String>()
        var cursor = ""
        var pages = 0
        var match: Clip? = null
        var rowsOnFirstPage = -1

        while (true) {
            val data = JSONObject(Http.get(pageUrl(uid, cursor)))
            val batch = data.optJSONArray("itemList") ?: JSONArray()
            if (rowsOnFirstPage < 0) rowsOnFirstPage = batch.length()
            if (batch.length() == 0) break
            pages++
            val clips = parse(context, data, user)
            clips.firstOrNull { it.id == wantedId }?.let { match = it }
            val fresh = ArrayList<Clip>()
            for (clip in clips) if (seen.add(clip.id)) fresh.add(clip)
            val room = (wanted - out.size).coerceAtLeast(0)
            val take = if (fresh.size > room) fresh.subList(0, room) else fresh
            if (take.isNotEmpty()) {
                out.addAll(take)
                // The grid shows this page while the next one is still on the wire.
                onPage(ArrayList(take))
            }
            ClipStats.save(context, clips.associate { it.id to it.stat })
            onProgress(out.size)
            if (match != null) break
            if (out.size >= wanted) break
            if (take.isEmpty()) break   // a page of nothing new: stop instead of spinning
            if (!data.optBoolean("hasMorePrevious")) break
            if (wantedId != null && pages >= SEARCH_PAGES) break
            val last = batch.optJSONObject(batch.length() - 1)?.optLong("createTime") ?: break
            cursor = (last * 1000).toString()   // cursor is milliseconds, not seconds
        }

        if (wantedId != null && match != null) {
            Log.d(TAG, "found single clip $wantedId in $pages pages")
            return@withContext Listing(listOf(match!!), source, match, pages)
        }
        if (wantedId != null) {
            // Older clips sit past what we searched; the reader answers them by id without a search.
            val one = SingleClip.clip(context, wantedId)
            if (one != null) return@withContext Listing(listOf(one), source, one, pages)
        }
        if (out.isEmpty()) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("v2_$user").apply()
            throw RuntimeException(
                "@$user: TikTok returned 0 videos (API rows: $rowsOnFirstPage) — all private/unlisted, " +
                    "or the session expired. Tap again — cache cleared."
            )
        }
        Log.d(TAG, "listed ${out.size} clips for $user")
        Listing(out, source, null, pages)
    }
}
