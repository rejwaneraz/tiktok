package com.rejwane.tiktokgrab

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Writes one still frame per clip under Download/TikTokGrab/Backup/frames, into a folder named after
 * the clip's first tag (or `_untagged`). Those folders are the training set: a folder name is a label
 * a person already agreed with, which is worth more than any guess.
 *
 * Nothing leaves the phone: the pictures stay in Downloads until the user copies them to a PC.
 */
object FrameExport {

    private const val DIR = "frames"
    private const val WIDTH = 480
    private const val HEIGHT = 854
    private const val QUALITY = 85

    fun folder(context: Context): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "TikTokGrab/Backup/$DIR"
    )

    /** Frames already written, so the Settings row can say whether there is anything to copy. */
    fun existing(context: Context): Int = folder(context).walkTopDown().count { it.isFile && it.name.endsWith(".jpg") }

    /**
     * One frame per clip, skipping the ones already on disk. Blocking; call off the main thread and
     * cancel the surrounding coroutine to stop.
     */
    suspend fun export(context: Context, videos: List<LocalVideo>,
                       onProgress: (Int, Int, String) -> Unit): Int {
        if (!Vault.granted(context)) return -1
        val base = folder(context)
        var written = 0
        videos.forEachIndexed { index, video ->
            coroutineContext.ensureActive()
            val key = ClipTags.key(video.clipId, video.name)
            val tag = ClipTags.of(context, key).firstOrNull()?.let { safe(it) } ?: "_untagged"
            val target = File(File(base, tag).apply { mkdirs() }, "${key.replace(':', '_')}.jpg")
            onProgress(index + 1, videos.size, tag)
            if (target.isFile && target.length() > 0) return@forEachIndexed
            val frame = frameOf(context, video) ?: return@forEachIndexed
            runCatching {
                target.outputStream().use { out -> frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
                written++
            }
            frame.recycle()
        }
        return written
    }

    private fun safe(name: String) =
        name.filterNot { it in "<>:\"/\\|?*" }.trim().ifBlank { "_untagged" }

    private fun frameOf(context: Context, video: LocalVideo): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val frame = runCatching {
            retriever.setDataSource(context, video.uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // A third of the way in: past the black intro, before the end card.
            val at = (duration * 300).coerceAtLeast(300_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
            } else {
                retriever.getFrameAtTime(at)?.let { full ->
                    val small = Bitmap.createScaledBitmap(full, WIDTH, HEIGHT, true)
                    if (small !== full) full.recycle()
                    small
                }
            }
        }.getOrNull()
        runCatching { retriever.release() }
        return frame
    }
}
