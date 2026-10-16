package com.rejwane.tiktokgrab

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The app's own records — authors, plays, likes, follows, shuffle order — are small, but they are
 * worth more than a reinstall. They are mirrored into Download/TikTokGrab/Backup/ so wiping the app
 * (or crashing) never loses them, and a fresh install reads that file back before the first screen.
 */
object Backup {

    private const val ROOT = "TikTokGrab"
    private const val DIR = "Backup"
    private const val NAME = "tiktokgrab-backup.json"
    private const val MIME = "application/json"

    /** Every store we keep, and all of them only hold strings or string sets. */
    private val stores = listOf("authors", "follows", "likes", "collection", "foryou", "ui", "saved", "secuids")

    private fun externalDir(context: Context): File {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getExternalFilesDir(null)
        else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) File(base, "backup")
        else File(base, "$ROOT/$DIR")
        folder.mkdirs()
        return folder
    }

    private fun fileOnDisk(context: Context) = File(externalDir(context), NAME)

    private fun scopedUri(context: Context, create: Boolean): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val found = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            where, arrayOf("Download/$ROOT/$DIR/", NAME), null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        if (found != null) return Uri.withAppendedPath(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, found.toString()
        )
        if (!create) return null
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, NAME)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$ROOT/$DIR/")
        })
    }

    fun snapshot(context: Context) {
        val json = runCatching { dump(context).toString() }.getOrNull() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                scopedUri(context, true)?.let { uri ->
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                }
            }
            fileOnDisk(context).outputStream().use { it.write(json.toByteArray()) }
        }
    }

    /** Only fills stores that are still empty, so a live install is never overwritten. */
    fun restoreIfEmpty(context: Context) {
        val json = runCatching {
            (readScoped(context) ?: readDisk(context))?.let { JSONObject(it) }
        }.getOrNull() ?: return
        stores.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (prefs.all.isNotEmpty()) return@forEach
            val store = json.optJSONObject(name) ?: return@forEach
            val editor = prefs.edit()
            store.keys().forEach { key ->
                when (val value = store.get(key)) {
                    is JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.getString(it) }.toSet())
                    is String -> editor.putString(key, value)
                }
            }
            editor.apply()
        }
    }

    private fun dump(context: Context): JSONObject {
        val root = JSONObject()
        stores.forEach { name ->
            val out = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is Set<*> -> out.put(key, JSONArray(value as Collection<Any>))
                    is String -> out.put(key, value)
                    is Number -> out.put(key, value.toString())
                }
            }
            root.put(name, out)
        }
        return root
    }

    private fun readScoped(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            scopedUri(context, false)?.let { uri ->
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }
        }.getOrNull()
    }

    private fun readDisk(context: Context): String? = runCatching {
        val file = fileOnDisk(context)
        if (file.length() > 0) file.readText() else null
    }.getOrNull()
}
