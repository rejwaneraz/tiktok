package com.rejwane.tiktokgrab

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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One downloaded video on this phone. TikTokGrab/<author>/<caption> [clipId].mp4 is the shape this
 * app writes, so the author and caption come straight off the path — no API, no network, no login.
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

    fun day(): String = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(addedAt * 1000))
}

object LocalVideos {

    private const val ROOT = "TikTokGrab"

    fun scan(context: Context): List<LocalVideo> = runCatching {
        (scoped(context) + legacy(context)).sortedByDescending { it.addedAt }
    }.getOrDefault(emptyList())

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
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
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
                out += LocalVideo(
                    uri = MediaStore.Downloads.getContentUri(c.getLong(id)),
                    name = c.str(title),
                    caption = captionOf(c.str(title)),
                    clipId = Regex("\\[(\\d+)]").find(c.str(title))?.groupValues?.get(1) ?: "",
                    author = folder.substringBefore('/').ifBlank { "Other" },
                    bytes = c.getLong(size),
                    durationMs = 0L,
                    addedAt = c.getLong(added)
                )
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
                author = file.parentFile?.name?.takeIf { it != ROOT } ?: "Other",
                bytes = file.length(),
                durationMs = 0L,
                addedAt = file.lastModified() / 1000
            )
        }
    }

    private fun captionOf(name: String) =
        name.removeSuffix(".mp4").substringBeforeLast('[').trim().ifBlank { "Video" }

    private fun Cursor.str(column: Int) = getString(column) ?: ""

    private fun Cursor.index(column: String) = getColumnIndexOrThrow(column)

    fun delete(context: Context, video: LocalVideo): Boolean = runCatching {
        if (video.uri.scheme == "file") File(video.uri.path!!).delete()
        else context.contentResolver.delete(video.uri, null, null) > 0
    }.getOrDefault(false)
}

/** A frame taken out of the file itself, so the grid also works with the network off. */
object LocalThumbs {

    private val cache = LruCache<String, Bitmap>(30)
    private val durations = ConcurrentHashMap<String, Int>()
    private val pool = Executors.newFixedThreadPool(2)

    /** Reading the file once gives both the cover frame and the length MediaStore withholds. */
    fun into(cover: ImageView, badge: TextView?, video: LocalVideo, context: Context) {
        val cached = synchronized(cache) { cache.get(video.key) }
        if (cached != null) {
            cover.setImageBitmap(cached)
            badge?.text = clock(video)
            return
        }
        cover.tag = video.key
        cover.setImageDrawable(null)
        badge?.text = ""
        pool.execute {
            val bmp = frame(context, video) ?: return@execute
            synchronized(cache) { cache.put(video.key, bmp) }
            Handler(Looper.getMainLooper()).post {
                if (cover.tag == video.key) {
                    cover.setImageBitmap(bmp)
                    badge?.text = clock(video)
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) retriever.getFrameAtTime(3_000_000) else null
        }.getOrNull()
        runCatching { retriever.release() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return context.contentResolver.loadThumbnail(video.uri, Size(280, 400), null)
            }
        }
        return fromFile
    }
}
