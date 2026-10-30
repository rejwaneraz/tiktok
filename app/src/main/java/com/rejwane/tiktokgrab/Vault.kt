package com.rejwane.tiktokgrab

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where the videos live: one plain folder, Downloads/TikTokGrab/library, with names ending in
 * .nomedia and a .nomedia marker beside them — no gallery shows them, no file manager presents them
 * as a video, and no other player offers to open them, while they are still plain files that outlive
 * an uninstall. Files that MediaStore owns for this package are deleted when the app is uninstalled,
 * which is exactly what took the library away before, so nothing here goes through MediaStore.
 *
 * Which profile a clip belongs to is a record, not a folder: one folder for all videos, as agreed.
 */
object Vault {

    const val ROOT = "TikTokGrab"
    const val LIBRARY = "library"
    const val EXT = ".nomedia"

    private const val PREF = "saved"
    private const val KEY = "files"

    /** How old a half-written .part has to be before a scan is allowed to remove it. */
    private const val STALE_PART_MS = 30 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Can we write into Downloads ourselves? On Android 11+ that is the "All files access" switch. */
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** True on phones that ask for the old storage permission instead of the All files switch. */
    fun usesRuntimePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /**
     * Android never shows a dialog for All files access — the switch lives on a system page, and the
     * app has to open that page itself. Three addresses are tried in order, because some phones hide
     * the first one (and a few bury it under the app's own details page).
     */
    fun openAccessSettings(activity: android.app.Activity): Boolean {
        if (usesRuntimePermission()) {
            // Android 10 and below still ask at runtime; the legacy flag in the manifest is what
            // then turns that permission into real file access for our own folder.
            activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 41)
            return true
        }
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + activity.packageName)),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.packageName))
        )
        for (intent in tries) {
            if (runCatching { activity.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    fun dir(context: Context): File {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$ROOT/$LIBRARY")
        runCatching { if (!folder.isDirectory) folder.mkdirs() }
        if (folder.isDirectory && !File(folder, ".nomedia").exists()) runCatching { File(folder, ".nomedia").createNewFile() }
        return folder
    }

    /**
     * A caption turned into a file name.
     *
     * The old rule cleaned the caption and *then* cut it at 60 characters, so a cut that landed after
     * a full stop left the name ending in "." — and this storage layer refuses such a name with a
     * FileNotFoundException whose whole message is the path, which is exactly what the phone showed for
     * a reel that would not download ("… যাই…😢🔥."). Cutting on whole characters fixes that, and two
     * more hazards with it: an emoji split in half becomes an invalid character, and a Bangla caption
     * costs three bytes a letter (four for an emoji), so a name that looks short in characters can pass
     * the 255 bytes a single file name is allowed. The limit is therefore counted in bytes, and any
     * trailing dot or space is dropped after the cut.
     */
    fun titleOf(caption: String, maxBytes: Int): String {
        val kept = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < caption.length) {
            val point = caption.codePointAt(i)
            i += Character.charCount(point)
            if (Character.isISOControl(point)) continue
            val size = if (point < 0x80) 1 else if (point < 0x800) 2 else if (point < 0x10000) 3 else 4
            if (bytes + size > maxBytes) break
            kept.appendCodePoint(point)
            bytes += size
        }
        var name = kept.toString()
        while (name.isNotEmpty() && (name.last() == '.' || name.last() == ' ')) name = name.dropLast(1)
        return name.filterNot { "<>:\"/\\|?*".contains(it) }.trim()
    }

    /**
     * One picture of a photo post: same folder, same clip id, a "p<i>" suffix so a set of images
     * stays together and still reads as one clip.
     */
    fun photoFile(context: Context, caption: String, clipId: String, index: Int, count: Int): File {
        val title = titleOf(caption, 120).ifBlank { "photo" }
        val suffix = if (count > 1) " p$index" else ""
        return File(dir(context), "$title [$clipId]$suffix.jpg$EXT")
    }

    /** A file nobody will mistake for a video, and never the one that is already there. */
    fun file(context: Context, caption: String, clipId: String): File {
        val base = "${titleOf(caption, 150).ifBlank { "clip" }} [$clipId]$EXT"
        val first = File(dir(context), base)
        // The id is in the name, so a file already sitting there is this same clip coming back —
        // a second copy of it under " (2)" would be two library rows for one clip.
        if (first.exists() && clipId.isNotBlank()) return first
        var n = 2
        while (true) {
            val next = File(dir(context), base.substringBeforeLast("$EXT") + " ($n)$EXT")
            if (!next.exists()) return next
            n++
        }
    }

    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter {
            it.isFile && it.length() > 0 && it.name != ".nomedia" && it.name.endsWith(EXT)
        } ?: emptyList()

    /**
     * Every clip ends in .nomedia: no gallery indexes it, and no other player offers to open it.
     * Clips written by the older builds (.tgr, or an .mp4 that landed here before) are renamed in
     * place — same bytes, same folder, no copy, no second file.
     */
    fun normalise(context: Context): Int {
        if (!granted(context)) return 0
        var renamed = 0
        val folder = dir(context)
        folder.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            // A download that died leaves its half-written .part behind. Only ones nobody could still
            // be writing are swept, so a download in flight is never cut off.
            if (name.endsWith(".part")) {
                if (System.currentTimeMillis() - file.lastModified() > STALE_PART_MS)
                    runCatching { file.delete() }
                return@forEach
            }
            if (name == ".nomedia" || name.endsWith(EXT)) return@forEach
            if (!name.endsWith(".tgr") && !name.endsWith(".mp4")) return@forEach
            val target = File(folder, name.substringBeforeLast('.') + EXT)
            if (target.exists()) return@forEach
            if (runCatching { file.renameTo(target) }.getOrDefault(false)) renamed++
        }
        return renamed
    }

    fun record(context: Context, clipId: String, author: String, caption: String, page: String = "") {
        if (clipId.isBlank()) return
        // One lock for read + write: several downloads run at the same time, and the last one to
        // finish used to overwrite the others' records with the table it read before they landed.
        synchronized(this) {
            val root = table(context)
            root.put(clipId, JSONArray().put(author).put(caption).put(page))
            prefs(context).edit().putString(KEY, root.toString()).apply()
        }
    }

    fun captionOf(context: Context, clipId: String): String =
        if (clipId.isBlank()) "" else table(context).optJSONArray(clipId)?.optString(1).orEmpty()

    /**
     * Where the clip really came from. A file has no host in its name, so without this an Instagram
     * clip saved in the same library would be "opened on TikTok" by the long-press menu — a link to a
     * video that does not exist there.
     */
    fun pageOf(context: Context, clipId: String): String =
        if (clipId.isBlank()) "" else table(context).optJSONArray(clipId)?.optString(2).orEmpty()

    /** The profile a file belongs to: our own record first, then whatever folder it came from. */
    fun authorOf(context: Context, clipId: String, fallback: String): String {
        val held = if (clipId.isBlank()) "" else table(context).optJSONArray(clipId)?.optString(0).orEmpty()
        return held.ifBlank { fallback }.ifBlank { LocalVideos.UNASSIGNED }
    }

    /** "Move to profile" is a record now, so nothing about the file has to change. */
    fun setAuthor(context: Context, clipId: String, author: String): Boolean {
        if (clipId.isBlank()) return false
        synchronized(this) {
            val root = table(context)
            val old = root.optJSONArray(clipId)
            root.put(clipId, JSONArray().put(author).put(old?.optString(1) ?: "")
                .put(old?.optString(2) ?: ""))
            prefs(context).edit().putString(KEY, root.toString()).apply()
        }
        return true
    }

    private fun table(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY, "{}")) }.getOrDefault(JSONObject())

    /**
     * The old shape kept one folder per author inside MediaStore, which the system then deleted with
     * the app. Those files are copied into the library, remembered with their author, and the row is
     * dropped — once, and only while we are allowed to write.
     */
    fun migrateLegacy(context: Context): Int {
        if (!granted(context)) return 0
        var moved = 0
        oldRows(context).forEach { row ->
            val clipId = Regex("\\[(\\d+)]").find(row.name)?.groupValues?.get(1) ?: ""
            val caption = row.name.removeSuffix(".mp4").substringBeforeLast('[').trim()
            // A half-finished move must not become two copies of the same clip.
            if (clipId.isNotBlank() && list(context).any { it.name.contains("[$clipId]") }) {
                runCatching { context.contentResolver.delete(row.uri, null, null) }
                return@forEach
            }
            val to = file(context, caption.ifBlank { "Video" }, clipId.ifBlank { row.name.hashCode().toString() })
            // The row is only dropped once the library file holds every byte the row promised: a
            // copy that stopped half way used to delete the original and lose the clip.
            val copied = runCatching {
                context.contentResolver.openInputStream(row.uri)?.use { input ->
                    to.outputStream().use { output -> input.copyTo(output) }
                } != null && sameSize(to, row.bytes)
            }.getOrDefault(false)
            if (!copied) {
                runCatching { to.delete() }
                return@forEach
            }
            if (clipId.isNotBlank()) Vault.record(context, clipId, row.folder, caption)
            runCatching { context.contentResolver.delete(row.uri, null, null) }
            moved++
        }
        return moved
    }

    /** True when the copy is exactly the size the row said it would be (or the row never said). */
    private fun sameSize(file: File, expected: Long) =
        file.length() > 0 && (expected <= 0L || file.length() == expected)

    private data class LegacyRow(val uri: Uri, val name: String, val folder: String, val bytes: Long)

    /** MediaStore still holds the videos from the older builds of this app. */
    private fun oldRows(context: Context): List<LegacyRow> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE)
        val out = ArrayList<LegacyRow>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, columns,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'video/%'",
                arrayOf("Download/$ROOT/%"), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val folder = c.getString(2).orEmpty().substringAfter("Download/$ROOT/").trim('/')
                    out.add(LegacyRow(
                        Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()),
                        c.getString(1).orEmpty(), folder.substringBefore('/'), c.getLong(3)))
                }
            }
        }
        return out
    }
}
