package com.rejwane.tiktokgrab

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * What we learn about an author while listing their videos: TikTok's nickname, avatar links and
 * stats. Pictures live in Download/TikTokGrab/Backup/avatars, so they survive a reinstall.
 */
data class AuthorInfo(
    val user: String,
    val nickname: String = "",
    val avatarUrl: String = "",
    val avatarBigUrl: String = "",
    val following: Long = 0L,
    val followers: Long = 0L,
    val likes: Long = 0L,
    val signature: String = ""
) {
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
    private const val FOLDER = "avatars"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private var cache: JSONObject? = null

    /** Parsed once and kept — the feed and every grid cell ask for this while scrolling. */
    private fun read(context: Context): JSONObject = synchronized(this) {
        cache ?: runCatching { JSONObject(prefs(context).getString(KEY, "{}")) }
            .getOrDefault(JSONObject()).also { cache = it }
    }

    fun get(context: Context, user: String): AuthorInfo {
        val o = read(context).optJSONObject(user) ?: return AuthorInfo(user)
        return AuthorInfo(
            user = user,
            nickname = o.optString("nickname"),
            avatarUrl = o.optString("avatar"),
            avatarBigUrl = o.optString("avatarBig"),
            following = o.optLong("following"),
            followers = o.optLong("followers"),
            likes = o.optLong("likes"),
            signature = o.optString("signature")
        )
    }

    /** Never lets a later, thinner answer wipe what we already knew. */
    fun put(context: Context, user: String, nickname: String = "", avatar: String = "",
            avatarBig: String = "", following: Long = 0L, followers: Long = 0L, likes: Long = 0L,
            signature: String = "") {
        if (user.isBlank()) return
        // One lock for read + merge + write: several authors are captured while a listing streams in,
        // and each of them used to write the table it read before the others landed.
        synchronized(this) {
            val root = read(context)
            val old = root.optJSONObject(user) ?: JSONObject()
            val merged = JSONObject()
                .put("nickname", nickname.ifBlank { old.optString("nickname") })
                .put("avatar", avatar.ifBlank { old.optString("avatar") })
                .put("avatarBig", avatarBig.ifBlank { old.optString("avatarBig") })
                .put("following", maxOf(following, old.optLong("following")))
                .put("followers", maxOf(followers, old.optLong("followers")))
                .put("likes", maxOf(likes, old.optLong("likes")))
                .put("signature", signature.ifBlank { old.optString("signature") })
            if (old.length() > 0 && old.toString() == merged.toString()) return
            root.put(user, merged)
            prefs(context).edit().putString(KEY, root.toString()).apply()
            cache = null
        }
    }

    /** The author object TikTok ships inside every item of the video list. */
    fun captureItem(context: Context, author: JSONObject) {
        val user = author.optString("uniqueId")
        if (user.isBlank()) return
        val stats = author.optJSONObject("stats") ?: author.optJSONObject("stalk")
        put(context, user = user,
            nickname = author.optString("nickname"),
            avatar = urlOf(author.opt("avatarThumb")).ifBlank { urlOf(author.opt("avatarUrl")) },
            avatarBig = urlOf(author.opt("avatarLarger")),
            followers = stats?.optLong("followerCount") ?: 0L,
            following = stats?.optLong("followingCount") ?: 0L,
            likes = stats?.optLong("heartCount") ?: 0L,
            signature = author.optString("signature")
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
                avatarBig = urlOf(profile.opt("avatarLarger")),
                following = stats.optLong("followingCount"),
                followers = stats.optLong("followerCount"),
                likes = stats.optLong("heartCount"),
                signature = profile.optString("signature")
            )
        }
    }

    private fun urlOf(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> if (value.length() > 0) urlOf(value.get(0)) else ""
        is JSONObject -> value.optString("Url").ifBlank { value.optString("url") }
        else -> ""
    }

    fun avatarUri(context: Context, user: String, big: Boolean = false): Uri? =
        Store.find(context, FOLDER, name(user, big))

    fun hasAvatar(context: Context, user: String) = avatarUri(context, user) != null

    fun cachedAvatars(context: Context) = Store.count(context, FOLDER) / 2

    fun clearAvatars(context: Context) = Store.clear(context, FOLDER)

    private fun name(user: String, big: Boolean) =
        "${user.replace('/', '_')}${if (big) "-big" else ""}.jpg"

    /**
     * The small face for the rail and the large one for the profile and the full screen view —
     * zooming in only sharpens if we asked TikTok for the bigger file in the first place.
     */
    fun fetchAvatar(context: Context, user: String): Boolean {
        val info = get(context, user)
        val small = info.avatarUrl.ifBlank { info.avatarBigUrl }
        if (small.isBlank()) return false
        val gotSmall = Store.download(context, FOLDER, name(user, false), small)
        val big = info.avatarBigUrl.ifBlank { small }
        val gotBig = Store.download(context, FOLDER, name(user, true), big)
        return gotSmall || gotBig
    }
}
