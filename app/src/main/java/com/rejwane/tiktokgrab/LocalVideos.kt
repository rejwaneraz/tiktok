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
import coil.load
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One downloaded video on this phone. New ones are plain files in Download/TikTokGrab/library named
 * "<caption> [clipId].nomedia", so the caption and the clip id come off the name and the profile comes
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
    val addedAt: Long,
    val isPhoto: Boolean = false,
    /** Every picture of a slideshow post, in order; a video carries none. */
    val photos: List<Uri> = emptyList()
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

    /** True for a picture saved from a photo post, which is shown instead of a video. */
    fun isPicture(name: String) =
        name.endsWith(".jpg${Vault.EXT}", true) ||
            name.endsWith(".png${Vault.EXT}", true) ||
            name.endsWith(".webp${Vault.EXT}", true)

    /**
     * Listing the folder costs disk reads and a MediaStore query, so it never runs on the thread that
     * draws. The time it took is kept for the Settings screen.
     */
    suspend fun scanOf(context: Context): List<LocalVideo> = withContext(Dispatchers.IO) {
        val began = Startup.now()
        try {
            scan(context)
        } finally {
            Startup.scanMs = Startup.now() - began
        }
    }

    fun scan(context: Context): List<LocalVideo> {
        // Older builds wrote .tgr (and, even earlier, .mp4) into the same folder: rename them once so
        // every clip carries the same discreet extension.
        runCatching { Vault.normalise(context) }
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
        val files = Vault.list(context)
        // The pictures of one slideshow post share its clip id, so they are one clip here rather than
        // fourteen: the first is the tile, the whole set travels with it.
        val posts = files.filter { isPicture(it.name) && clipIdOf(it.name).isNotBlank() }
            .groupBy { clipIdOf(it.name) }
        val used = HashSet(posts.values.flatten())
        val out = ArrayList<LocalVideo>()
        posts.forEach { (clipId, set) ->
            val ordered = set.sortedBy { pictureIndex(it.name) }
            val first = ordered.first()
            out.add(LocalVideo(
                uri = Uri.fromFile(first),
                name = first.name,
                caption = Vault.captionOf(context, clipId).ifBlank { captionOf(first.name) },
                clipId = clipId,
                author = Vault.authorOf(context, clipId, ""),
                bytes = ordered.sumOf { it.length() },
                durationMs = 0L,
                addedAt = ordered.maxOf { it.lastModified() } / 1000,
                isPhoto = true,
                photos = ordered.map { Uri.fromFile(it) }
            ))
        }
        out.addAll(files.filterNot { used.contains(it) }.map { file ->
            val clipId = clipIdOf(file.name)
            val caption = Vault.captionOf(context, clipId).ifBlank { captionOf(file.name) }
            LocalVideo(
                uri = Uri.fromFile(file),
                name = file.name,
                caption = caption,
                clipId = clipId,
                author = Vault.authorOf(context, clipId.ifBlank { "file:" + file.name }, ""),
                bytes = file.length(),
                durationMs = 0L,
                addedAt = file.lastModified() / 1000,
                // A picture that arrived on its own (no clip id to group it by) is still a picture.
                isPhoto = isPicture(file.name),
                photos = if (isPicture(file.name)) listOf(Uri.fromFile(file)) else emptyList()
            )
        })
        return out
    }

    private fun clipIdOf(name: String) = Regex("\\[(\\d+)]").find(name)?.groupValues?.get(1) ?: ""

    /** " p12." sorts after " p2." only when the number is read as a number. */
    private fun pictureIndex(name: String) = Regex(" p(\\d+)\\.").find(name)?.groupValues
        ?.get(1)?.toIntOrNull() ?: 1

    /**
     * For You order. With `fresh` the roll happens again — TikTok gives a new feed every time it
     * opens — and without it the same order is kept, so a tab change never moves the video that is
     * playing. New videos land wherever their roll falls, deleted ones simply drop out.
     */
    fun forYou(context: Context, videos: List<LocalVideo>, fresh: Boolean = false): List<LocalVideo> {
        val prefs = context.getSharedPreferences("foryou", Context.MODE_PRIVATE)
        val saved = if (fresh) JSONObject() else runCatching { JSONObject(prefs.getString("order", "{}")) }
            .getOrDefault(JSONObject())
        val dice = HashMap<String, Double>()
        var rolled = false
        for (video in videos) {
            val roll = if (saved.has(video.key)) saved.optDouble(video.key, -1.0) else -1.0
            if (roll < 0) {
                dice[video.key] = Math.random()
                rolled = true
            } else dice[video.key] = roll
        }
        if (rolled) {
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
        val targets = if (video.photos.size > 1) video.photos else listOf(video.uri)
        var gone = false
        targets.forEach { uri ->
            val removed = runCatching {
                if (uri.scheme == "file") uri.path?.let { File(it).delete() } ?: false
                else context.contentResolver.delete(uri, null, null) > 0
            }.getOrDefault(false)
            gone = gone || removed
        }
        // The clip is only "not downloaded" once none of its files are left — one picture out of a
        // fourteen-picture post must not put the whole post back on the download list.
        val left = runCatching {
            Vault.list(context).any { it.isFile && clipIdOf(it.name) == video.clipId }
        }.getOrDefault(true)
        if (gone && !left) Downloader.forget(context, video.clipId)
        return gone
    }

    /**
     * Moving a clip between profiles is a record, never a folder. A file that arrived without a clip
     * id (dropped into the folder by hand) is filed under its own name, so it still moves — and it
     * stays in the one folder the library scans instead of vanishing into a sub-folder.
     */
    fun moveTo(context: Context, video: LocalVideo, author: String): Boolean {
        val key = video.clipId.ifBlank { "file:" + video.name }
        return Vault.setAuthor(context, key, author)
    }
}

/** A frame taken out of the file itself, so the grid also works with the network off. */
object LocalThumbs {

    private val cache = LruCache<String, Bitmap>(200)
    private val durations = ConcurrentHashMap<String, Int>()
    private val pool = Executors.newFixedThreadPool(3)

    /** Reading the file once gives both the cover frame and the length MediaStore withholds. */
    fun into(cover: ImageView, badge: TextView?, video: LocalVideo, context: Context) {
        // Claim the cell first: every early return below still has to stop a frame that an earlier
        // clip is decoding, or that late bitmap lands on the wrong tile.
        cover.tag = video.key
        if (video.isPhoto) {
            // A picture is already a picture: no frame to seek, no duration to read.
            cover.load(video.uri) { crossfade(false) }
            badge?.text = ""
            return
        }
        val views = video.badge(context)
        val cached = synchronized(cache) { cache.get(video.key) }
        if (cached != null) {
            cover.setImageBitmap(cached)
            badge?.text = views.ifBlank { clock(video) }
            return
        }
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
            // A full-size frame for a 120 dp cell is a lot of pixels to decode and throw away.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 280, 400
                )
            } else {
                retriever.getFrameAtTime(3_000_000)?.let { full ->
                    val small = Bitmap.createScaledBitmap(full, 280, 400, true)
                    if (small !== full) full.recycle()
                    small
                }
            }
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
