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
 * Where the videos live: one plain folder, Downloads/TikTokGrab/library, with names ending in .tgr and
 * a .nomedia beside them — no gallery shows them, no file manager presents them as a video, and they
 * are ours to play. Files that MediaStore owns for this package are deleted when the app is uninstalled,
 * which is exactly what took the library away before, so nothing here goes through MediaStore.
 *
 * Which profile a clip belongs to is a record, not a folder: one folder for all videos, as agreed.
 */
object Vault {

    const val ROOT = "TikTokGrab"
    const val LIBRARY = "library"
    const val EXT = ".tgr"

    private const val PREF = "saved"
    private const val KEY = "files"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Can we write into Downloads ourselves? On Android 11+ that is the "All files access" switch. */
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** Takes the user to the one switch we need, or asks for the old permission below Android 11. */
    fun requestAccess(activity: android.app.Activity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.packageName)))
            }.getOrElse {
                runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
            return true
        }
        // Android 10 still asks for the storage permission at runtime; the legacy flag in the
        // manifest is what then turns it into real file access for our own folder.
        activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 41)
        return false
    }

    fun dir(context: Context): File {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$ROOT/$LIBRARY")
        runCatching { if (!folder.isDirectory) folder.mkdirs() }
        if (folder.isDirectory && !File(folder, ".nomedia").exists()) runCatching { File(folder, ".nomedia").createNewFile() }
        return folder
    }

    /** A file nobody will mistake for a video, and never the one that is already there. */
    fun file(context: Context, caption: String, clipId: String): File {
        val base = "${LocalVideos.sanitize(caption).take(60)} [$clipId]$EXT"
        val first = File(dir(context), base)
        if (!first.exists()) return first
        var n = 2
        while (true) {
            val next = File(dir(context), base.substringBeforeLast("$EXT") + " ($n)$EXT")
            if (!next.exists()) return next
            n++
        }
    }

    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(EXT) && it.length() > 0 }
            ?: emptyList()

    fun record(context: Context, clipId: String, author: String, caption: String) {
        if (clipId.isBlank()) return
        val root = table(context)
        root.put(clipId, JSONArray().put(author).put(caption))
        prefs(context).edit().putString(KEY, root.toString()).apply()
    }

    fun captionOf(context: Context, clipId: String): String =
        if (clipId.isBlank()) "" else table(context).optJSONArray(clipId)?.optString(1).orEmpty()

    /** The profile a file belongs to: our own record first, then whatever folder it came from. */
    fun authorOf(context: Context, clipId: String, fallback: String): String {
        val held = if (clipId.isBlank()) "" else table(context).optJSONArray(clipId)?.optString(0).orEmpty()
        return held.ifBlank { fallback }.ifBlank { LocalVideos.UNASSIGNED }
    }

    /** "Move to profile" is a record now, so nothing about the file has to change. */
    fun setAuthor(context: Context, clipId: String, author: String): Boolean {
        if (clipId.isBlank()) return false
        val root = table(context)
        val old = root.optJSONArray(clipId)
        root.put(clipId, JSONArray().put(author).put(old?.optString(1) ?: ""))
        prefs(context).edit().putString(KEY, root.toString()).apply()
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
        oldRows(context).forEach { (uri, name, folder) ->
            val clipId = Regex("\\[(\\d+)]").find(name)?.groupValues?.get(1) ?: ""
            val caption = name.removeSuffix(".mp4").substringBeforeLast('[').trim()
            // A half-finished move must not become two copies of the same clip.
            if (clipId.isNotBlank() && list(context).any { it.name.contains("[$clipId]") }) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                return@forEach
            }
            val to = file(context, caption.ifBlank { "Video" }, clipId.ifBlank { name.hashCode().toString() })
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    to.outputStream().use { output -> input.copyTo(output) }
                } != null && to.length() > 0
            }.getOrDefault(false)
            if (!copied) return@forEach
            if (clipId.isNotBlank()) Vault.record(context, clipId, folder, caption)
            runCatching { context.contentResolver.delete(uri, null, null) }
            moved++
        }
        return moved
    }

    /** MediaStore still holds the videos from the older builds of this app. */
    private fun oldRows(context: Context): List<Triple<Uri, String, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH)
        val out = ArrayList<Triple<Uri, String, String>>()
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
                    out.add(Triple(
                        Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()),
                        c.getString(1).orEmpty(), folder.substringBefore('/')))
                }
            }
        }
        return out
    }
}
