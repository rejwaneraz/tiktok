package com.rejwane.tiktokgrab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The same long-press menu on every screen that lists saved videos. */
object LocalMenu {

    fun show(host: AppCompatActivity, clip: LocalVideo, profiles: List<String>,
             onDeleted: () -> Unit, onMoved: () -> Unit) {
        val labels = arrayOf("Delete", "Share", "Play in another app", "Open on TikTok",
            "Copy file name", "Move to profile", "Collections…")
        MaterialAlertDialogBuilder(host)
            .setTitle(clip.caption.ifBlank { clip.name })
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> confirm(host, clip, onDeleted)
                    1 -> share(host, clip)
                    2 -> openOutside(host, clip)
                    3 -> openOnTikTok(host, clip)
                    4 -> copy(host, clip)
                    5 -> move(host, clip, profiles, onMoved)
                    6 -> tag(host, clip, onMoved)
                }
            }
            .show()
    }

    /**
     * Hand-made tags, which win over the ones guessed from a caption. They also decide the folder a
     * training frame is exported into, so this is the one place the user teaches the app anything.
     */
    private fun tag(host: AppCompatActivity, clip: LocalVideo, onChanged: () -> Unit) {
        val key = ClipTags.key(clip.clipId, clip.name)
        val known = ClipTags.known(host)
        if (known.isEmpty()) {
            newTag(host, key, onChanged)
            return
        }
        val current = ClipTags.of(host, key).toMutableSet()
        val checked = known.map { it in current }.toBooleanArray()
        // No setMessage here: a message and a list cannot share this dialog (the message wins and
        // the checkboxes never appear, which is exactly what broke the List order picker).
        MaterialAlertDialogBuilder(host)
            .setTitle("Collections for this clip")
            .setMultiChoiceItems(known.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) current.add(known[which]) else current.remove(known[which])
            }
            .setPositiveButton("Save") { _, _ ->
                ClipTags.set(host, key, current.toList())
                onChanged()
            }
            .setNeutralButton("New collection") { _, _ -> newTag(host, key, onChanged) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newTag(host: AppCompatActivity, key: String, onChanged: () -> Unit) {
        val input = android.widget.EditText(host).apply {
            hint = "e.g. School"
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(host)
            .setTitle("New collection")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    ClipTags.remember(host, name)
                    ClipTags.set(host, key, ClipTags.of(host, key) + name)
                    onChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Profiles are folders, so moving the file is what moves it between profiles. */
    private fun move(host: AppCompatActivity, clip: LocalVideo, profiles: List<String>,
                     onMoved: () -> Unit) {
        val targets = profiles.filter { it != clip.author }.sorted()
        if (targets.isEmpty()) {
            note(host, host.getString(R.string.no_other_profile))
            return
        }
        MaterialAlertDialogBuilder(host)
            .setTitle("Move to which profile?")
            .setItems(targets.toTypedArray()) { _, which ->
                if (LocalVideos.moveTo(host, clip, targets[which])) onMoved()
                else note(host, host.getString(R.string.move_failed))
            }
            .show()
    }

    private fun confirm(host: AppCompatActivity, clip: LocalVideo, onDeleted: () -> Unit) {
        MaterialAlertDialogBuilder(host)
            .setTitle("Delete this video?")
            .setMessage("${clip.name}\n\nIt is removed from Download/TikTokGrab/${Vault.LIBRARY}. This cannot be undone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                if (LocalVideos.delete(host, clip)) onDeleted()
                else note(host, "Could not delete — the file is gone or protected")
            }
            .show()
    }

    fun share(host: AppCompatActivity, clip: LocalVideo) {
        // A slideshow post is one clip holding several files, so all of them go out together.
        val uris = (if (clip.photos.isNotEmpty()) clip.photos else listOf(clip.uri))
            .mapNotNull { outward(host, it) }
        if (uris.isEmpty()) return note(host, "Cannot share this file")
        val intent = Intent(
            if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
        ).apply {
            type = mime(clip)
            if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            else putExtra(Intent.EXTRA_STREAM, uris.first())
            putExtra(Intent.EXTRA_TEXT, clip.caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { host.startActivity(Intent.createChooser(intent, "Share")) }
            .onFailure { note(host, "Cannot share this file") }
    }

    private fun openOutside(host: AppCompatActivity, clip: LocalVideo) {
        val send = outward(host, clip.uri) ?: return note(host, "No player for this file")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(send, mime(clip))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { host.startActivity(intent) }.onFailure { note(host, "No player for this file") }
    }

    private fun mime(clip: LocalVideo) = if (clip.isPhoto) "image/jpeg" else "video/mp4"

    /** Other apps are not allowed a file:// address, so our own folder leaves through a provider. */
    private fun outward(host: AppCompatActivity, uri: Uri): Uri? {
        if (uri.scheme != "file") return uri
        val file = uri.path?.let { java.io.File(it) } ?: return null
        return runCatching {
            androidx.core.content.FileProvider.getUriForFile(host, "${host.packageName}.files", file)
        }.getOrNull()
    }

    private fun openOnTikTok(host: AppCompatActivity, clip: LocalVideo) {
        val kind = if (clip.isPhoto) "photo" else "video"
        val page = if (clip.clipId.isBlank()) "https://www.tiktok.com/@${clip.author}"
        else "https://www.tiktok.com/@${clip.author}/$kind/${clip.clipId}"
        runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page))) }
            .onFailure { note(host, "No browser for this link") }
    }

    private fun copy(host: AppCompatActivity, clip: LocalVideo) {
        host.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("name", clip.name))
        note(host, "Copied ${clip.name}")
    }

    private fun note(host: AppCompatActivity, text: String) {
        Toast.makeText(host, text, Toast.LENGTH_SHORT).show()
    }
}
