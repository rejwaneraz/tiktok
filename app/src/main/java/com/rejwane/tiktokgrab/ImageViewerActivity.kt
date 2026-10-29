package com.rejwane.tiktokgrab

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tapping a picture opens it full size; tapping anywhere closes it again. A picture that only lives
 * inside the app can be handed to the phone from here — a deliberate copy into Pictures, not the
 * hidden library file, so it shows up in a gallery the way a person expects.
 */
class ImageViewerActivity : AppCompatActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_viewer)
        val picture = ViewerQueue.uri
        if (picture == null) {
            finish()
            return
        }
        findViewById<View>(R.id.viewerRoot).setOnClickListener { finish() }
        findViewById<TextView>(R.id.viewerName).text = ViewerQueue.title
        findViewById<ImageView>(R.id.viewerImage).load(picture)
        findViewById<TextView>(R.id.viewerSave).setOnClickListener { save(picture) }
        ViewerQueue.uri = null
    }

    private fun save(picture: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { copyToPictures(picture) }.getOrDefault(false)
            }
            Toast.makeText(
                this@ImageViewerActivity,
                if (ok) "Saved to Pictures/TikTokGrab" else "Could not save this picture",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun copyToPictures(picture: Uri): Boolean {
        val name = "tiktokgrab_${System.currentTimeMillis()}.jpg"
        contentResolver.openInputStream(picture)?.use { source ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TikTokGrab")
                }
                val target = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                contentResolver.openOutputStream(target)?.use { out -> source.copyTo(out) } ?: return false
                return true
            }
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "TikTokGrab"
            ).apply { mkdirs() }
            File(dir, name).outputStream().use { out -> source.copyTo(out) }
            return true
        }
        return false
    }
}

object ViewerQueue {
    var uri: Uri? = null
    var title: String = ""
}
