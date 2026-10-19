package com.rejwane.tiktokgrab

import android.content.Context

/** Hearts are local: the clip id survives a re-download, the file uri is the fallback. */
object Likes {

    private const val PREF = "likes"
    private const val KEY = "ids"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun ids(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet()) ?: emptySet()

    private fun key(video: LocalVideo) = video.clipId.ifBlank { video.key }

    fun isLiked(context: Context, video: LocalVideo) = key(video) in ids(context)

    /** The online preview has no file yet, so it is hearted by clip id alone. */
    fun isLiked(context: Context, clipId: String) = clipId.isNotBlank() && clipId in ids(context)

    fun toggle(context: Context, clipId: String): Boolean = flip(context, clipId)

    /** Returns whether the video is liked after the tap. */
    fun toggle(context: Context, video: LocalVideo): Boolean {
        val on = flip(context, key(video))
        return on
    }

    private fun flip(context: Context, key: String): Boolean {
        val now = HashSet(ids(context))
        val on = if (key in now) {
            now.remove(key); false
        } else {
            now.add(key); true
        }
        prefs(context).edit().putStringSet(KEY, now).apply()
        return on
    }

    fun liked(context: Context, videos: List<LocalVideo>) = videos.filter { isLiked(context, it) }
}
