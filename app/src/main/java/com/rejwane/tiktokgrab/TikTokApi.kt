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
    val views: Long,
    val bytes: Long,
    val thumb: String,
    val play: String,
    val page: String,
    val author: String = ""
)

/** clips + the link the user typed, with any short link already expanded. match is set when a
 *  specific video link was given and found. */
data class Listing(val clips: List<Clip>, val source: String, val match: Clip? = null,
                   val searchedPages: Int = 0)

object TikTokApi {

    private const val TAG = "tiktokgrab"
    private const val ITEM_LIST = "https://www.tiktok.com/api/creator/item_list/"
    private const val PAGE = 15          // TikTok rejects any other count
    const val CAP = 500                  // ~0.4 MB of JSON per page
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
                views = item.optJSONObject("stats")?.optLong("playCount") ?: 0L,
                bytes = video.optLong("size").takeIf { it > 0 }
                    ?: video.optJSONArray("bitrateInfo")?.optJSONObject(0)?.optJSONObject("PlayAddr")?.optLong("DataSize") ?: 0L,
                thumb = urlOf(video.opt("cover")).ifBlank { urlOf(video.opt("originCover")) },
                play = bestSource(video).ifBlank { urlOf(video.opt("downloadAddr")) },
                page = "https://www.tiktok.com/@$user/video/${item.optString("id")}",
                author = author.optString("uniqueId").ifBlank { user }
            )
        }
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

    private fun pageUrl(uid: String, cursor: String) =
        ITEM_LIST + "?secUid=$uid&count=$PAGE&cursor=$cursor&type=1&source=0&aid=1988" +
            "&app_name=tiktok_web&device_platform=web_pc&channel=tiktok_web&cookie_enabled=true" +
            "&os=windows&language=en&msToken=" + msToken()

    suspend fun list(context: Context, web: TikTokWeb, input: String, limit: Int,
                     onProgress: (Int) -> Unit): Listing = withContext(Dispatchers.IO) {
        val source = normalize(input)
        val user = username(source)
        if (user.isBlank()) throw RuntimeException("username not found")

        val uid = secUid(context, web, user)
        val wantedId = Regex("/video/(\\d+)").find(source)?.groupValues?.get(1)
        val wanted = if (limit <= 0) CAP else minOf(limit, CAP)
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
            for (clip in clips) {
                if (seen.add(clip.id)) out.add(clip)
            }
            Authors.saveViews(context, clips.associate { it.id to it.views })
            onProgress(out.size)
            if (match != null) break
            if (wantedId == null && out.size >= wanted) break
            if (!data.optBoolean("hasMorePrevious")) break
            if (wantedId != null && pages >= SEARCH_PAGES) break
            val last = batch.optJSONObject(batch.length() - 1)?.optLong("createTime") ?: break
            cursor = (last * 1000).toString()   // cursor is milliseconds, not seconds
        }

        if (wantedId != null && match != null) {
            Log.d(TAG, "found single clip $wantedId in $pages pages")
            return@withContext Listing(listOf(match!!), source, match, pages)
        }
        if (out.isEmpty()) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("v2_$user").apply()
            throw RuntimeException(
                "@$user: TikTok returned 0 videos (API rows: $rowsOnFirstPage) — all private/unlisted, " +
                    "or the session expired. Tap again — cache cleared."
            )
        }
        if (out.size > wanted) out.subList(wanted, out.size).clear()
        Log.d(TAG, "listed ${out.size} clips for $user")
        Listing(out, source, null, pages)
    }
}
