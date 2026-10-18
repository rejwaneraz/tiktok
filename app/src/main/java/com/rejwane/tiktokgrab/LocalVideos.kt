package com.rejwane.tiktokgrab

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One downloaded video on this phone. New ones are plain files in Download/TikTokGrab/library named
 * "<caption> [clipId].tgr", so the caption and the clip id come off the name and the profile comes
 * from our own record — no API, no network, no login.
 */
data class LocalVideo(
    val uri: Uri,
    val name: String,
    val caption: String,
    val clipId: String,
    val author: String,
    val bytes: Long,
    val durationMs: Long,
    val addedAt: Long
) {
    val key: String get() = uri.toString()

    fun size(): String = GridAdapter.mb(bytes)

    fun clock(): String = LocalThumbs.clock(this)

    /** TikTok's own play count when we were online for it; empty means fall back to the length. */
    fun badge(context: Context): String {
        val plays = ClipStats.of(context, clipId).plays
        return if (plays > 0) "▶ ${GridAdapter.short(plays)}" else ""
    }

    fun day(): String = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(addedAt * 1000))
}

object LocalVideos {

    private const val ROOT = "TikTokGrab"
    private const val ILLEGAL = "<>:\"/\\|?*"

    /** Videos downloaded before per-author folders existed have no folder to name them by. */
    const val UNASSIGNED = "Unassigned"

    fun sanitize(name: String) =
        name.filterNot { ILLEGAL.contains(it) }.trim().trim('.').ifBlank { UNASSIGNED }

    fun scan(context: Context): List<LocalVideo> {
        val found = runCatching {
            (vault(context) + scoped(context) + legacy(context)).sortedByDescending { it.addedAt }
        }.getOrNull() ?: return emptyList()
        // The record follows the folder, not the other way round: a clip that no longer has a file
        // anywhere is not downloaded, whatever the saved id set still says.
        Downloader.reconcile(context, found.filter { it.clipId.isNotBlank() }.map { it.clipId }.toSet())
        return found
    }

    /** The library folder itself: one place for every video, whatever the caption or the profile. */
    private fun vault(context: Context): List<LocalVideo> {
        if (!Vault.granted(context)) return emptyList()
        return Vault.list(context).map { file ->
            val clipId = Regex("\\[(\\d+)]").find(file.name)?.groupValues?.get(1) ?: ""
            val caption = Vault.captionOf(context, clipId).ifBlank { captionOf(file.name) }
            LocalVideo(
                uri = Uri.fromFile(file),
                name = file.name,
                caption = caption,
                clipId = clipId,
                author = Vault.authorOf(context, clipId, ""),
                bytes = file.length(),
                durationMs = 0L,
                addedAt = file.lastModified() / 1000
            )
        }
    }

    /**
     * For You order is rolled once and then kept, so swiping back never throws you somewhere else.
     * New videos land wherever their roll falls, deleted ones simply drop out.
     */
    fun forYou(context: Context, videos: List<LocalVideo>): List<LocalVideo> {
        val prefs = context.getSharedPreferences("foryou", Context.MODE_PRIVATE)
        val saved = runCatching { JSONObject(prefs.getString("order", "{}")) }
            .getOrDefault(JSONObject())
        val dice = HashMap<String, Double>()
        var fresh = false
        for (video in videos) {
            val roll = if (saved.has(video.key)) saved.optDouble(video.key, -1.0) else -1.0
            if (roll < 0) {
                dice[video.key] = Math.random()
                fresh = true
            } else dice[video.key] = roll
        }
        if (fresh) {
            val order = JSONObject()
            dice.forEach { (key, value) -> order.put(key, value) }
            prefs.edit().putString("order", order.toString()).apply()
        }
        return videos.sortedBy { dice[it.key] }
    }

    fun reshuffle(context: Context) =
        context.getSharedPreferences("foryou", Context.MODE_PRIVATE).edit().remove("order").apply()

    private fun scoped(context: Context): List<LocalVideo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val out = ArrayList<LocalVideo>()
        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, columns,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'video/%'",
                arrayOf("Download/$ROOT/%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )
        }.getOrNull() ?: return emptyList()
        cursor.use { c ->
            val id = c.index(MediaStore.MediaColumns._ID)
            val title = c.index(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = c.index(MediaStore.MediaColumns.SIZE)
            val added = c.index(MediaStore.MediaColumns.DATE_ADDED)
            val path = c.index(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val folder = c.str(path).substringAfter("Download/$ROOT/").trim('/')
                val rowId = c.getLong(id)
                val clipId = Regex("\\[(\\d+)]").find(c.str(title))?.groupValues?.get(1) ?: ""
                out.add(LocalVideo(
                    uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, rowId.toString()),
                    name = c.str(title),
                    caption = captionOf(c.str(title)),
                    clipId = clipId,
                    author = Vault.authorOf(context, clipId, folder.substringBefore('/')),
                    bytes = c.getLong(size),
                    durationMs = 0L,
                    addedAt = c.getLong(added)
                ))
            }
        }
        return out
    }

    /** Videos written by the pre-Android-10 build of this app, in its own downloads dir. */
    private fun legacy(context: Context): List<LocalVideo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return emptyList()
        val base = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), ROOT)
        if (!base.isDirectory) return emptyList()
        val files = ArrayList<File>()
        base.listFiles()?.forEach { entry ->
            if (entry.isDirectory) entry.listFiles()?.let { files.addAll(it) } else files.add(entry)
        }
        return files.filter { it.isFile && it.name.endsWith(".mp4") && it.length() > 0 }.map { file ->
            LocalVideo(
                uri = Uri.fromFile(file),
                name = file.name,
                caption = captionOf(file.name),
                clipId = Regex("\\[(\\d+)]").find(file.name)?.groupValues?.get(1) ?: "",
                author = file.parentFile?.name?.takeIf { it != ROOT } ?: UNASSIGNED,
                bytes = file.length(),
                durationMs = 0L,
                addedAt = file.lastModified() / 1000
            )
        }
    }

    private fun captionOf(name: String) = name.removeSuffix(Vault.EXT).removeSuffix(".mp4")
        .substringBeforeLast('[').trim().ifBlank { "Video" }

    private fun Cursor.str(column: Int) = getString(column) ?: ""

    private fun Cursor.index(column: String) = getColumnIndexOrThrow(column)

    fun delete(context: Context, video: LocalVideo): Boolean {
        val gone = runCatching {
            if (video.uri.scheme == "file") File(video.uri.path!!).delete()
            else context.contentResolver.delete(video.uri, null, null) > 0
        }.getOrDefault(false)
        if (gone) Downloader.forget(context, video.clipId)
        return gone
    }

    /** Re-files a video under another author, which is how it changes profile. */
    fun moveTo(context: Context, video: LocalVideo, author: String): Boolean {
        if (video.clipId.isNotBlank()) return Vault.setAuthor(context, video.clipId, author)
        return runCatching {
            val folder = sanitize(author)
            if (video.uri.scheme == "file") {
                val dir = File(Vault.dir(context), folder)
                dir.mkdirs()
                File(video.uri.path!!).renameTo(File(dir, video.name))
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$ROOT/$folder/")
                }
                context.contentResolver.update(video.uri, values, null, null) > 0
            }
        }.getOrDefault(false)
    }
}

/** A frame taken out of the file itself, so the grid also works with the network off. */
object LocalThumbs {

    private val cache = LruCache<String, Bitmap>(30)
    private val durations = ConcurrentHashMap<String, Int>()
    private val pool = Executors.newFixedThreadPool(2)

    /** Reading the file once gives both the cover frame and the length MediaStore withholds. */
    fun into(cover: ImageView, badge: TextView?, video: LocalVideo, context: Context) {
        val views = video.badge(context)
        val cached = synchronized(cache) { cache.get(video.key) }
        if (cached != null) {
            cover.setImageBitmap(cached)
            badge?.text = views.ifBlank { clock(video) }
            return
        }
        cover.tag = video.key
        cover.setImageDrawable(null)
        badge?.text = views
        pool.execute {
            val bmp = frame(context, video) ?: return@execute
            synchronized(cache) { cache.put(video.key, bmp) }
            Handler(Looper.getMainLooper()).post {
                if (cover.tag == video.key) {
                    cover.setImageBitmap(bmp)
                    badge?.text = views.ifBlank { clock(video) }
                }
            }
        }
    }

    fun clock(video: LocalVideo): String =
        GridAdapter.clock(durations[video.key] ?: (video.durationMs / 1000).toInt())

    private fun frame(context: Context, video: LocalVideo): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val fromFile = runCatching {
            retriever.setDataSource(context, video.uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.div(1000)?.toInt()?.let { durations[video.key] = it }
            retriever.getFrameAtTime(3_000_000)
        }.getOrNull()
        runCatching { retriever.release() }
        // The system can only hand back a picture for media it indexes itself; our own files are not
        // indexed on purpose, so the frame always comes out of the file.
        if (video.uri.scheme != "file" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { return context.contentResolver.loadThumbnail(video.uri, Size(280, 400), null) }
        }
        return fromFile
    }
}
