package com.rejwane.tiktokgrab

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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

    /** Anything under this is an error page, not a clip — the shortest real video is far bigger. */
    private const val NOT_A_VIDEO = 10_000L

    /** The smallest picture TikTok ever hands out is still well above this. */
    private const val SMALLEST_PICTURE = 1_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context) = context.getSharedPreferences("saved", Context.MODE_PRIVATE)

    /**
     * Whose CDN this is. TikTok's wants the session cookies attached; Instagram's takes a cookie it
     * never issued as a foreign credential and answers 403, so each host gets only what it asked for.
     */
    private fun fromInstagram(clip: Clip) = clip.page.contains("instagram.com")

    private fun refererOf(clip: Clip) =
        if (fromInstagram(clip)) InstagramApi.REFERER else "https://www.tiktok.com/"

    fun doneIds(context: Context) = prefs(context).getStringSet("ids", emptySet()) ?: emptySet()

    /** Deleting the file must not leave the clip marked as downloaded forever. */
    fun forget(context: Context, clipId: String) {
        if (clipId.isBlank() || clipId !in doneIds(context)) return
        val kept = HashSet(doneIds(context))
        kept.remove(clipId)
        prefs(context).edit().putStringSet("ids", kept).apply()
    }

    /**
     * The record must not outlive the file. A video removed from the folder by a file manager, or by
     * the system, leaves its id behind — and then the list calls that clip "already downloaded" while
     * the player has nothing to open. Only drops ids while we can still see the library, so a revoked
     * permission cannot empty the list and make everything download again.
     */
    fun reconcile(context: Context, present: Set<String>) {
        if (!Vault.granted(context)) return
        val known = doneIds(context)
        if (known.isEmpty()) return
        val kept = known.filterTo(HashSet()) { it in present }
        if (kept.size != known.size) prefs(context).edit().putStringSet("ids", kept).apply()
    }

    private fun fileName(clip: Clip): String {
        val title = clip.title.filterNot { ILLEGAL.contains(it) }.take(50).trim()
        return "${if (title.isBlank()) "tiktok" else title} [${clip.id}].mp4"
    }

    /** One folder per author: Downloads/TikTokGrab/<username>/… */
    private fun folderFor(clip: Clip) = LocalVideos.sanitize(clip.author)

    /** The first hit sometimes fails until TikTok hands back a fresh msToken, so retry once. */
    suspend fun save(context: Context, clip: Clip, onPercent: (Float) -> Unit): Saved {
        if (clip.play.isBlank() && clip.images.isEmpty()) throw RuntimeException("no video link")
        if (clip.id in doneIds(context)) return Saved(clip.id, "", "", 0L, true)
        return try {
            saveNow(context, clip, onPercent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Instagram's addresses are signed and die within hours, so a clip listed this morning can
            // already answer 403 by the time it is ticked. Re-reading that one post costs ~0.86 MB and
            // is the difference between "some reels download and some do not" and a library that works.
            val fresh: Clip = refreshInstagram(clip) ?: throw e
            saveNow(context, fresh, onPercent)
        }
    }

    /** The post again through its own link, when that link says Instagram and names a post. */
    private suspend fun refreshInstagram(clip: Clip): Clip? {
        if (!fromInstagram(clip)) return null
        val code = Regex("/(?:p|reel)/([A-Za-z0-9_-]{11})").find(clip.page)?.groupValues?.get(1)
            ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { InstagramApi.clipFor(code) }.getOrNull()
        }
    }

    private suspend fun saveNow(context: Context, clip: Clip, onPercent: (Float) -> Unit): Saved {
        // A photo (slideshow) post has no video: its pictures are the download.
        if (clip.play.isBlank()) return savePhotos(context, clip, onPercent)

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

    /**
     * Every picture of a photo post, numbered, in the same library folder as the videos. Each file
     * carries the clip id, so the app can show them as one post and a reinstall still knows whose
     * they are.
     */
    private suspend fun savePhotos(context: Context, clip: Clip,
                                   onPercent: (Float) -> Unit): Saved = withContext(Dispatchers.IO) {
        if (!Vault.granted(context)) {
            throw RuntimeException("All files access is off - turn it on in Settings")
        }
        var first = ""
        var total = 0L
        clip.images.forEachIndexed { index, url ->
            coroutineContext.ensureActive()
            val target = Vault.photoFile(context, clip.title, clip.id, index + 1, clip.images.size)
            if (!target.isFile || target.length() == 0L) {
                val request = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Referer", refererOf(clip))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
                    val body = response.body ?: throw RuntimeException("empty picture")
                    val expected = body.contentLength()
                    // A picture that lands half way is worse than one that never lands: it is a
                    // permanent file the next pass reads as complete. So it comes down as a .part,
                    // is measured against what the server promised, and only then becomes the file.
                    val temp = File(target.absolutePath + ".part")
                    try {
                        temp.outputStream().use { out -> body.byteStream().use { input -> input.copyTo(out) } }
                        val written = temp.length()
                        if (written < SMALLEST_PICTURE)
                            throw RuntimeException("not a picture (only $written bytes)")
                        if (expected > 0 && written != expected)
                            throw RuntimeException("incomplete picture: $written of $expected bytes")
                        if (!temp.renameTo(target)) throw RuntimeException("could not store the picture")
                    } catch (e: Exception) {
                        runCatching { temp.delete() }
                        throw e
                    }
                }
            }
            if (index == 0) first = target.absolutePath
            total += target.length()
            onPercent(((index + 1) * 100f) / clip.images.size)
        }
        Vault.record(context, clip.id, clip.author, clip.title, clip.page)
        prefs(context).edit().putStringSet("ids", doneIds(context) + clip.id).apply()
        Saved(clip.id, first, Uri.fromFile(File(first)).toString(), total, false)
    }

    private suspend fun attempt(context: Context, clip: Clip, name: String,
                                onPercent: (Float) -> Unit): Saved {
        val builder = Request.Builder().url(clip.play)
            .header("User-Agent", UA)
            .header("Referer", refererOf(clip))
        if (!fromInstagram(clip)) builder.header("Cookie", Cookies.header())
        val request = builder.build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                // Only TikTok's own msToken is ever stored; a CDN answer from elsewhere must not
                // touch the session that the login depends on.
                if (!fromInstagram(clip)) Cookies.update(response.headers("Set-Cookie"))
                if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
                val total = response.body?.contentLength() ?: -1L
                var written = 0L
                val onBytes: (Long) -> Unit = { w ->
                    written = w
                    if (total > 0) onPercent(w * 100f / total)
                }
                val (path, uri) = when {
                    Vault.granted(context) -> saveVault(context, clip, response, onBytes)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                        // MediaStore would put the file somewhere the system deletes with the app, and
                        // in the gallery as a playable video. That is the shape that lost the library
                        // once already, so it is refused out loud instead of used quietly.
                        throw RuntimeException("All files access is off - turn it on in Settings")
                    else -> saveLegacy(context, folderFor(clip), name, response, onBytes)
                }
                // The profile is a record now, not a folder — written even for the old paths, so a
                // later move into the library still knows who the video belongs to.
                Vault.record(context, clip.id, clip.author, clip.title, clip.page)
                prefs(context).edit().putStringSet("ids", doneIds(context) + clip.id).apply()
                Saved(clip.id, path, uri, written, false)
            }
        }
    }

    /** A plain file in our own library folder: no gallery, no MediaStore, no uninstall cleanup. */
    private suspend fun saveVault(context: Context, clip: Clip, response: Response,
                                  onBytes: (Long) -> Unit): Pair<String, String> {
        val file = Vault.file(context, clip.title.ifBlank { "tiktok" }, clip.id)
        // The clip only becomes the real file once every byte is down: writing straight into it would
        // leave a half file (or none at all, if the retry then failed) where a good clip used to be.
        val temp = File(file.absolutePath + ".part")
        try {
            temp.outputStream().use { stream(response, it, onBytes) }
            if (!temp.renameTo(file)) throw RuntimeException("could not store the video")
        } catch (e: Exception) {
            runCatching { temp.delete() }
            throw e
        }
        return Pair(file.absolutePath, Uri.fromFile(file).toString())
    }

    private suspend fun saveLegacy(context: Context, folder: String, name: String, response: Response,
                                   onBytes: (Long) -> Unit): Pair<String, String> {
        val base = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        val dir = File(base, folder)
        dir.mkdirs()
        val file = File(dir, name)
        try {
            file.outputStream().use { stream(response, it, onBytes) }
        } catch (e: Exception) {
            runCatching { file.delete() }
            throw e
        }
        return Pair(file.absolutePath, Uri.fromFile(file).toString())
    }

    private suspend fun stream(response: Response, out: OutputStream, onBytes: (Long) -> Unit) {
        val body = response.body ?: throw RuntimeException("empty response")
        val total = body.contentLength()
        val src = body.byteStream()
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
        // A connection that closes early still returns a short file: it plays a few seconds and stops,
        // yet it used to be marked downloaded, so it was never retried. Three guards, cheapest first —
        // nothing is not a video, an error page is tiny, a short body is shorter than its own header.
        if (written == 0L) throw RuntimeException("empty download")
        if (written < NOT_A_VIDEO) throw RuntimeException("not a video (only $written bytes)")
        if (total > 0 && written != total) throw RuntimeException("incomplete: $written of $total bytes")
    }
}
