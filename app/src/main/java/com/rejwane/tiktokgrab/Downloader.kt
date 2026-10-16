package com.rejwane.tiktokgrab

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class Saved(val clipId: String, val path: String, val uri: String, val bytes: Long, val skipped: Boolean)

/** The video CDN only answers with TikTok cookies attached — TLS fingerprint is not checked here. */
object Downloader {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    private const val FOLDER = "TikTokGrab"
    private const val ILLEGAL = "<>:\"/\\|?*"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context) = context.getSharedPreferences("saved", Context.MODE_PRIVATE)

    fun doneIds(context: Context) = prefs(context).getStringSet("ids", emptySet()) ?: emptySet()

    /** Deleting the file must not leave the clip marked as downloaded forever. */
    fun forget(context: Context, clipId: String) {
        if (clipId.isBlank() || clipId !in doneIds(context)) return
        val kept = HashSet(doneIds(context))
        kept.remove(clipId)
        prefs(context).edit().putStringSet("ids", kept).apply()
    }

    private fun fileName(clip: Clip): String {
        val title = clip.title.filterNot { ILLEGAL.contains(it) }.take(50).trim()
        return "${if (title.isBlank()) "tiktok" else title} [${clip.id}].mp4"
    }

    /** One folder per author: Downloads/TikTokGrab/<username>/… */
    private fun folderFor(clip: Clip) = LocalVideos.sanitize(clip.author)

    /** The first hit sometimes fails until TikTok hands back a fresh msToken, so retry once. */
    suspend fun save(context: Context, clip: Clip, onPercent: (Float) -> Unit): Saved {
        if (clip.play.isBlank()) throw RuntimeException("no video link")
        if (clip.id in doneIds(context)) return Saved(clip.id, "", "", 0L, true)

        var failure: Exception? = null
        repeat(2) { tryAgain ->
            try {
                return attempt(context, clip, fileName(clip), onPercent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                if (tryAgain == 0) delay(1500)
            }
        }
        throw failure ?: RuntimeException("download failed")
    }

    private suspend fun attempt(context: Context, clip: Clip, name: String,
                                onPercent: (Float) -> Unit): Saved {
        val request = Request.Builder().url(clip.play)
            .header("Cookie", Cookies.header())
            .header("User-Agent", UA)
            .header("Referer", "https://www.tiktok.com/")
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                Cookies.update(response.headers("Set-Cookie"))
                if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
                val total = response.body?.contentLength() ?: -1L
                var written = 0L
                val onBytes: (Long) -> Unit = { w ->
                    written = w
                    if (total > 0) onPercent(w * 100f / total)
                }
                val (path, uri) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    saveScoped(context, folderFor(clip), name, response, onBytes)
                else
                    saveLegacy(context, folderFor(clip), name, response, onBytes)
                prefs(context).edit().putStringSet("ids", doneIds(context) + clip.id).apply()
                Saved(clip.id, path, uri, written, false)
            }
        }
    }

    private suspend fun saveScoped(context: Context, folder: String, name: String, response: Response,
                                   onBytes: (Long) -> Unit): Pair<String, String> {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$folder")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("could not create file")
        context.contentResolver.openOutputStream(uri)?.use { stream(response, it, onBytes) }
            ?: throw RuntimeException("could not write file")
        return Pair("Downloads/$FOLDER/$folder/$name", uri.toString())
    }

    private suspend fun saveLegacy(context: Context, folder: String, name: String, response: Response,
                                   onBytes: (Long) -> Unit): Pair<String, String> {
        val base = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        val dir = File(base, folder)
        dir.mkdirs()
        val file = File(dir, name)
        file.outputStream().use { stream(response, it, onBytes) }
        return Pair(file.absolutePath, Uri.fromFile(file).toString())
    }

    private suspend fun stream(response: Response, out: OutputStream, onBytes: (Long) -> Unit) {
        val src = response.body?.byteStream() ?: throw RuntimeException("empty response")
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        src.use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                written += read
                onBytes(written)
            }
        }
        out.flush()
    }
}
