package com.rejwane.tiktokgrab

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Our own records — comment dumps, author pictures, the settings backup — live in
 * Download/TikTokGrab/Backup, not inside the app. A reinstall or a crash cannot take them, and the
 * app never grows because of them. On Android 10+ that means MediaStore; below that plain files.
 */
object Store {

    private const val ROOT = "TikTokGrab"
    private const val DIR = "Backup"

    private fun relative(folder: String) = "Download/$ROOT/$DIR/$folder"

    private fun legacyDir(context: Context, folder: String) =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$ROOT/$DIR/$folder")

    fun find(context: Context, folder: String, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return legacyFile(context, folder, name)?.let(Uri::fromFile)
        val id = runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("${relative(folder)}/", name), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull() ?: return legacyFile(context, folder, name)?.let(Uri::fromFile)
        return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
    }

    fun exists(context: Context, folder: String, name: String) = find(context, folder, name) != null

    fun write(context: Context, folder: String, name: String, mime: String, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val known = find(context, folder, name)
            if (known != null && known.scheme != "file") {
                return runCatching {
                    context.contentResolver.openOutputStream(known, "wt")?.use { it.write(bytes) }
                    true
                }.getOrDefault(false)
            }
            return runCatching {
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${relative(folder)}/")
                    }) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                true
            }.getOrDefault(false)
        }
        return runCatching {
            val dir = legacyDir(context, folder).apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    fun writeText(context: Context, folder: String, name: String, text: String) =
        write(context, folder, name, "application/json", text.toByteArray())

    fun readBytes(context: Context, folder: String, name: String): ByteArray? {
        val uri = find(context, folder, name) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    fun readText(context: Context, folder: String, name: String): String? =
        readBytes(context, folder, name)?.decodeToString()

    /** Downloads are written by us, so a saved picture can be shown straight from its uri. */
    fun download(context: Context, folder: String, name: String, url: String): Boolean {
        if (exists(context, folder, name)) return true
        return runCatching {
            val temp = File.createTempFile("grab", ".img")
            try {
                if (!Http.save(url, temp)) return false
                write(context, folder, name, "image/jpeg", temp.readBytes())
            } finally {
                temp.delete()
            }
        }.getOrDefault(false)
    }

    fun count(context: Context, folder: String): Int = urisIn(context, folder).size

    fun clear(context: Context, folder: String) {
        urisIn(context, folder).forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) legacyDir(context, folder).deleteRecursively()
    }

    private fun urisIn(context: Context, folder: String): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return legacyDir(context, folder).listFiles()?.filter { it.length() > 0 }
                ?.map { Uri.fromFile(it) } ?: emptyList()
        }
        val out = ArrayList<Uri>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("${relative(folder)}/"), null
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString()))
                }
            }
        }
        return out
    }

    private fun legacyFile(context: Context, folder: String, name: String): File? {
        val file = File(legacyDir(context, folder), name)
        return if (file.length() > 0L) file else null
    }
}
