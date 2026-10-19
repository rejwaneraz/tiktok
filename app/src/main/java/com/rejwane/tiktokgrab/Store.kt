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

    /**
     * With All files access we own the folder, so records are plain files. MediaStore rows belong to
     * this package and the system deletes them with the app — the exact reason a reinstall used to
     * come back with nothing, comments and avatars included.
     */
    private fun plainDir(context: Context, folder: String) = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$ROOT/$DIR"),
        folder
    )

    private fun plainFile(context: Context, folder: String, name: String) =
        File(plainDir(context, folder), onDisk(name))

    /**
     * A saved picture is written as "x.jpg.nomedia": the bytes are a JPEG, but no gallery indexes a
     * type it does not know and no other app offers to open it. The app reads it back by name, so
     * nothing else changes.
     */
    private fun onDisk(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".png", true) -> "$name.nomedia"
        else -> name
    }

    /** Renames pictures written before the hidden extension, then leaves the marker a scanner obeys. */
    fun hideExisting(context: Context) {
        if (!Vault.granted(context)) return
        listOf("photos", "avatars").forEach { folder ->
            val dir = plainDir(context, folder)
            if (!dir.isDirectory) return@forEach
            runCatching { if (!File(dir, ".nomedia").exists()) File(dir, ".nomedia").createNewFile() }
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val name = file.name
                if (name == ".nomedia" || name.endsWith(".nomedia")) return@forEach
                if (!name.endsWith(".jpg", true) && !name.endsWith(".png", true)) return@forEach
                runCatching { file.renameTo(File(dir, "$name.nomedia")) }
            }
        }
        adoptStored(context, "photos")
        adoptStored(context, "avatars")
    }

    /**
     * Pictures saved before All files access was granted are MediaStore rows, which a gallery shows.
     * Each is copied into the hidden plain file and its row dropped — the app keeps the picture, the
     * gallery loses it, and a reinstall still finds it on disk.
     */
    private fun adoptStored(context: Context, folder: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rows = runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("${relative(folder)}/%"), null
            )?.use { c ->
                val out = ArrayList<Pair<Long, String>>()
                while (c.moveToNext()) {
                    if (c.getColumnCount() < 2) break
                    out.add(c.getLong(0) to (c.getString(1) ?: ""))
                }
                out
            }
        }.getOrNull().orEmpty()
        if (rows.isEmpty()) return
        val dir = plainDir(context, folder).apply { mkdirs() }
        rows.forEach { (id, name) ->
            val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            val to = File(dir, onDisk(name.ifBlank { "$folder$id.jpg" }))
            if (to.isFile && to.length() > 0) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                return@forEach
            }
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    to.outputStream().use { out -> input.copyTo(out) }
                }
                true
            }.getOrDefault(false) && to.length() > 0
            if (copied) runCatching { context.contentResolver.delete(uri, null, null) }
        }
        runCatching { if (!File(dir, ".nomedia").exists()) File(dir, ".nomedia").createNewFile() }
    }

    fun find(context: Context, folder: String, name: String): Uri? {
        val disk = onDisk(name)
        if (Vault.granted(context)) {
            val plain = plainFile(context, folder, name)
            if (plain.isFile && plain.length() > 0) return Uri.fromFile(plain)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return legacyFile(context, folder, disk)?.let(Uri::fromFile)
        }
        val id = runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("${relative(folder)}/", disk), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull() ?: return legacyFile(context, folder, disk)?.let(Uri::fromFile)
        return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
    }

    fun exists(context: Context, folder: String, name: String) = find(context, folder, name) != null

    fun write(context: Context, folder: String, name: String, mime: String, bytes: ByteArray): Boolean {
        val disk = onDisk(name)
        if (Vault.granted(context)) {
            return runCatching {
                val dir = plainDir(context, folder).apply { mkdirs() }
                runCatching { if (!File(dir, ".nomedia").exists()) File(dir, ".nomedia").createNewFile() }
                File(dir, disk).writeBytes(bytes)
                true
            }.getOrDefault(false)
        }
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
                        put(MediaStore.Downloads.DISPLAY_NAME, disk)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${relative(folder)}/")
                    }) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                true
            }.getOrDefault(false)
        }
        return runCatching {
            val dir = legacyDir(context, folder).apply { mkdirs() }
            File(dir, disk).writeBytes(bytes)
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
            if (uri.scheme == "file") runCatching { File(uri.path ?: return@runCatching).delete() }
            else runCatching { context.contentResolver.delete(uri, null, null) }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) legacyDir(context, folder).deleteRecursively()
    }

    private fun urisIn(context: Context, folder: String): List<Uri> {
        if (Vault.granted(context)) {
            return plainDir(context, folder).listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.map { Uri.fromFile(it) } ?: emptyList()
        }
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
