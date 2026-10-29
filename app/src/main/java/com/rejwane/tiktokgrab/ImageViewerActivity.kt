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
import java.io.InputStream

/**
 * Tapping a picture opens it full size; tapping anywhere closes it again. A picture that only lives
 * inside the app can be handed to the phone from here — a deliberate copy into Pictures, not the
 * hidden library file, so it shows up in a gallery the way a person expects.
 *
 * A photo post from the listing is not on the phone yet, so it is shown from its own address and
 * saved the same way when asked. Tapping a thumbnail previews it; it never downloads by itself.
 */
class ImageViewerActivity : AppCompatActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_viewer)
        val local = ViewerQueue.uri
        val remote = ViewerQueue.remote
        if (local == null && remote.isBlank()) {
            finish()
            return
        }
        val title = ViewerQueue.title
        val count = ViewerQueue.count

        findViewById<View>(R.id.viewerRoot).setOnClickListener { finish() }
        findViewById<TextView>(R.id.viewerName).text =
            if (count > 1) "$title · $count pictures" else title
        val image = findViewById<ImageView>(R.id.viewerImage)
        if (local != null) image.load(local) else image.load(remote)
        findViewById<TextView>(R.id.viewerSave).setOnClickListener {
            if (local != null) save(local) else saveRemote(remote)
        }
        ViewerQueue.uri = null
        ViewerQueue.remote = ""
        ViewerQueue.count = 1
    }

    private fun save(picture: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { writeToPictures { contentResolver.openInputStream(picture) } }
                    .getOrDefault(false)
            }
            toast(ok)
        }
    }

    private fun saveRemote(url: String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File.createTempFile("grab", ".jpg")
                    try {
                        if (!Http.save(url, temp)) return@runCatching false
                        writeToPictures { temp.inputStream() }
                    } finally {
                        temp.delete()
                    }
                }.getOrDefault(false)
            }
            toast(ok)
        }
    }

    private fun toast(ok: Boolean) = Toast.makeText(
        this,
        if (ok) "Saved to Pictures/TikTokGrab" else "Could not save this picture",
        Toast.LENGTH_SHORT
    ).show()

    /** One writer for both cases: a local file and a picture fetched from its address. */
    private fun writeToPictures(open: () -> InputStream?): Boolean {
        val name = "tiktokgrab_${System.currentTimeMillis()}.jpg"
        open()?.use { source ->
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
    /** A picture already on the phone. */
    var uri: Uri? = null

    /** A picture that is still online — a photo post opened straight from the listing. */
    var remote: String = ""
    var title: String = ""
    var count: Int = 1
}
