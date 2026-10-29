package com.rejwane.tiktokgrab

import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Every picture of a post, full size, swiped sideways — whether it is a comment's picture, a profile
 * avatar, or a slideshow post that carries fourteen of them.
 *
 * Nothing is downloaded by opening this: Save is a deliberate copy into Pictures, and for a picture
 * that is still online it is fetched once and written there.
 */
class ImageViewerActivity : AppCompatActivity() {

    private var urls: List<String> = emptyList()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_viewer)
        urls = ViewerQueue.urls
        if (urls.isEmpty()) {
            finish()
            return
        }
        val title = ViewerQueue.title
        val pager = findViewById<ViewPager2>(R.id.viewerPager)
        val counter = findViewById<TextView>(R.id.viewerCount)
        pager.adapter = Pages(urls)
        findViewById<TextView>(R.id.viewerName).text = title

        fun paint(index: Int) {
            counter.text = if (urls.size > 1) "${index + 1} / ${urls.size}" else ""
        }
        paint(pager.currentItem)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = paint(position)
        })
        findViewById<TextView>(R.id.viewerClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.viewerSave).setOnClickListener { save(pager.currentItem) }
        ViewerQueue.urls = emptyList()
    }

    private fun save(index: Int) {
        val url = urls.getOrNull(index) ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (url.startsWith("http")) {
                        val temp = File.createTempFile("grab", ".jpg")
                        try {
                            if (!Http.save(url, temp)) return@runCatching false
                            writeToPictures { temp.inputStream() }
                        } finally {
                            temp.delete()
                        }
                    } else {
                        writeToPictures { contentResolver.openInputStream(Uri.parse(url)) }
                    }
                }.getOrDefault(false)
            }
            Toast.makeText(
                this@ImageViewerActivity,
                if (ok) "Saved to Pictures/TikTokGrab" else "Could not save this picture",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

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

    /** One page per picture; the pager is what makes a slideshow swipe. */
    private class Pages(private val urls: List<String>) : RecyclerView.Adapter<Pages.Page>() {

        class Page(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Page {
            val image = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }
            return Page(image)
        }

        override fun getItemCount() = urls.size

        override fun onBindViewHolder(holder: Page, position: Int) {
            holder.image.load(urls[position])
        }
    }
}

object ViewerQueue {
    /** Everything the viewer should show; one entry for a comment picture, many for a slideshow. */
    var urls: List<String> = emptyList()
    var title: String = ""
}
