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

    /** Returns whether the video is liked after the tap. */
    fun toggle(context: Context, video: LocalVideo): Boolean {
        val now = HashSet(ids(context))
        val on = if (key(video) in now) {
            now.remove(key(video)); false
        } else {
            now.add(key(video)); true
        }
        prefs(context).edit().putStringSet(KEY, now).apply()
        return on
    }

    fun liked(context: Context, videos: List<LocalVideo>) = videos.filter { isLiked(context, it) }
}
