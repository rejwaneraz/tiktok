package com.rejwane.tiktokgrab

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load

/** Tapping a profile picture opens it full size; tapping anywhere closes it again. */
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
        ViewerQueue.uri = null
    }
}

object ViewerQueue {
    var uri: Uri? = null
    var title: String = ""
}
