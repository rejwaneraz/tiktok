package com.rejwane.tiktokgrab

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * What we learn about an author while listing their videos: TikTok's own nickname, avatar link and
 * stats. Kept in the app's private files so a profile still looks right with no network.
 */
data class AuthorInfo(
    val user: String,
    val nickname: String = "",
    val avatarUrl: String = "",
    val following: Long = 0L,
    val followers: Long = 0L,
    val likes: Long = 0L
) {
    val known: Boolean get() = nickname.isNotBlank() || followers > 0L

    fun display(fallback: String) = nickname.ifBlank { fallback }

    fun statsNote(): String = if (followers == 0L && likes == 0L) "" else buildString {
        if (following > 0) append(GridAdapter.short(following)).append(" Following · ")
        if (followers > 0) append(GridAdapter.short(followers)).append(" Followers · ")
        if (likes > 0) append(GridAdapter.short(likes)).append(" Likes")
    }.trimEnd('·', ' ')
}

object Authors {

    private const val PREF = "authors"
    private const val KEY = "info"
    private const val VIEWS = "views"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun read(context: Context) =
        runCatching { JSONObject(prefs(context).getString(KEY, "{}")) }.getOrDefault(JSONObject())

    fun all(context: Context): Map<String, AuthorInfo> {
        val root = read(context)
        return root.keys().asSequence().associateWith { get(context, it) }
    }

    fun get(context: Context, user: String): AuthorInfo {
        val o = read(context).optJSONObject(user) ?: return AuthorInfo(user)
        return AuthorInfo(
            user = user,
            nickname = o.optString("nickname"),
            avatarUrl = o.optString("avatar"),
            following = o.optLong("following"),
            followers = o.optLong("followers"),
            likes = o.optLong("likes")
        )
    }

    /** Never lets a later, thinner answer wipe what we already knew. */
    fun put(context: Context, user: String, nickname: String = "", avatar: String = "",
            following: Long = 0L, followers: Long = 0L, likes: Long = 0L) {
        if (user.isBlank()) return
        val root = read(context)
        val old = root.optJSONObject(user) ?: JSONObject()
        val merged = JSONObject()
            .put("nickname", nickname.ifBlank { old.optString("nickname") })
            .put("avatar", avatar.ifBlank { old.optString("avatar") })
            .put("following", maxOf(following, old.optLong("following")))
            .put("followers", maxOf(followers, old.optLong("followers")))
            .put("likes", maxOf(likes, old.optLong("likes")))
        root.put(user, merged)
        prefs(context).edit().putString(KEY, root.toString()).apply()
    }

    /** The author object TikTok ships inside every item of the video list. */
    fun captureItem(context: Context, author: JSONObject) {
        val user = author.optString("uniqueId")
        if (user.isBlank()) return
        val stats = author.optJSONObject("stats") ?: author.optJSONObject("stalk")
        put(context, user = user,
            nickname = author.optString("nickname"),
            avatar = urlOf(author.opt("avatarThumb")).ifBlank { urlOf(author.opt("avatarUrl")) },
            followers = stats?.optLong("followerCount") ?: 0L,
            following = stats?.optLong("followingCount") ?: 0L,
            likes = stats?.optLong("heartCount") ?: 0L
        )
    }

    /** The profile page JSON we already read once for secUid also carries the avatar and stats. */
    fun captureFromPage(context: Context, user: String, json: String) {
        if (json.isBlank()) return
        runCatching {
            val info = JSONObject(json).optJSONObject("__DEFAULT_SCOPE__")
                ?.optJSONObject("webapp.user-detail")?.optJSONObject("userInfo") ?: return@runCatching
            val profile = info.optJSONObject("user") ?: return@runCatching
            if (!profile.optString("uniqueId").equals(user, true)) return@runCatching
            val stats = info.optJSONObject("stats") ?: JSONObject()
            put(context,
                user = profile.optString("uniqueId").ifBlank { user },
                nickname = profile.optString("nickname"),
                avatar = urlOf(profile.opt("avatarThumb")).ifBlank { urlOf(profile.opt("avatarUrl")) },
                following = stats.optLong("followingCount"),
                followers = stats.optLong("followerCount"),
                likes = stats.optLong("heartCount")
            )
        }
    }

    private fun urlOf(value: Any?): String = when (value) {
        is String -> value
        is org.json.JSONArray -> if (value.length() > 0) urlOf(value.get(0)) else ""
        is JSONObject -> value.optString("Url").ifBlank { value.optString("url") }
        else -> ""
    }

    fun avatarFile(context: Context, user: String) =
        File(authorsDir(context), "${user.replace('/', '_')}.jpg")

    fun hasAvatar(context: Context, user: String) = avatarFile(context, user).length() > 0

    private fun authorsDir(context: Context) =
        File(context.filesDir, "authors").apply { mkdirs() }

    /** One small image per author, kept forever; returns false when offline or already there. */
    fun fetchAvatar(context: Context, user: String): Boolean {
        val info = get(context, user)
        if (info.avatarUrl.isBlank()) return false
        val file = avatarFile(context, user)
        if (file.length() > 0) return true
        return runCatching { Http.save(info.avatarUrl, file) }.getOrDefault(false)
    }

    fun saveViews(context: Context, views: Map<String, Long>) {
        if (views.isEmpty()) return
        val merged = JSONObject(prefs(context).getString(VIEWS, "{}"))
        views.forEach { (id, count) -> if (id.isNotBlank()) merged.put(id, count) }
        prefs(context).edit().putString(VIEWS, merged.toString()).apply()
        synchronized(this) { cache = null }
    }

    private var cache: JSONObject? = null

    /** Parsed once and kept — the grid asks for this for every cell it draws. */
    fun viewCount(context: Context, clipId: String): Long {
        if (clipId.isBlank()) return 0L
        val table = synchronized(this) {
            cache ?: runCatching { JSONObject(prefs(context).getString(VIEWS, "{}")) }
                .getOrDefault(JSONObject()).also { cache = it }
        }
        return table.optLong(clipId)
    }
}
