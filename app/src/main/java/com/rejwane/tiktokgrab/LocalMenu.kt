package com.rejwane.tiktokgrab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The same long-press menu on every screen that lists saved videos. */
object LocalMenu {

    fun show(host: AppCompatActivity, clip: LocalVideo, profiles: List<String>,
             onDeleted: () -> Unit, onMoved: () -> Unit) {
        val labels = arrayOf("Delete", "Share", "Play in another app", "Copy file name",
            "Move to profile")
        MaterialAlertDialogBuilder(host)
            .setTitle(clip.caption.ifBlank { clip.name })
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> confirm(host, clip, onDeleted)
                    1 -> share(host, clip)
                    2 -> openOutside(host, clip)
                    3 -> copy(host, clip)
                    4 -> move(host, clip, profiles, onMoved)
                }
            }
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
            .setMessage("${clip.name}\n\nIt is removed from Download/TikTokGrab/${clip.author}. This cannot be undone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                if (LocalVideos.delete(host, clip)) onDeleted()
                else note(host, "Could not delete — the file is gone or protected")
            }
            .show()
    }

    private fun share(host: AppCompatActivity, clip: LocalVideo) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, clip.uri)
            putExtra(Intent.EXTRA_TEXT, clip.caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { host.startActivity(Intent.createChooser(intent, "Share")) }
            .onFailure { note(host, "Cannot share this file") }
    }

    private fun openOutside(host: AppCompatActivity, clip: LocalVideo) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(clip.uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { host.startActivity(intent) }.onFailure { note(host, "No player for this file") }
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
