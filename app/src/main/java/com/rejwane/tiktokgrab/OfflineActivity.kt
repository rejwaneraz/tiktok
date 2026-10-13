package com.rejwane.tiktokgrab

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2

/**
 * The saved videos as a swipe feed: author chips up top, caption that opens out on tap, long press
 * for delete/share. Reads only what already sits in Download/TikTokGrab, so it works fully offline.
 */
class OfflineActivity : AppCompatActivity() {

    private var all = listOf<LocalVideo>()
    private var shown = listOf<LocalVideo>()
    private var adapter: OfflineAdapter? = null
    private var author = ""
    private var position = 0
    private val tick = Handler(Looper.getMainLooper())

    private val pager by lazy { findViewById<ViewPager2>(R.id.offlinePager) }
    private val counter by lazy { findViewById<TextView>(R.id.offlineCounter) }
    private val chips by lazy { findViewById<LinearLayout>(R.id.offlineChips) }
    private val empty by lazy { findViewById<TextView>(R.id.offlineEmpty) }

    private val pulse = object : Runnable {
        override fun run() {
            adapter?.tick(position)
            tick.postDelayed(this, 400)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_offline)
        findViewById<TextView>(R.id.offlineClose).setOnClickListener { finish() }
        rebuild(OfflineQueue.startUri)
        OfflineQueue.startUri = null
    }

    /** startUri keeps the video you tapped on first when the list is re-read. */
    private fun rebuild(startUri: Uri?) {
        val scroll = findViewById<View>(R.id.offlineChipScroll)
        all = LocalVideos.scan(this)
        if (all.isEmpty()) {
            pager.visibility = View.GONE
            scroll.visibility = View.GONE
            counter.text = "0 / 0"
            empty.visibility = View.VISIBLE
            return
        }
        pager.visibility = View.VISIBLE
        scroll.visibility = View.VISIBLE
        empty.visibility = View.GONE
        buildChips()
        show(if (author.isEmpty()) all else all.filter { it.author == author }, startUri)
    }

    private fun show(list: List<LocalVideo>, startUri: Uri?) {
        shown = list
        if (shown.isEmpty()) {
            adapter = null
            pager.adapter = null
            counter.text = "0 / 0"
            return
        }
        position = shown.indexOfFirst { it.uri == startUri }.takeIf { it >= 0 } ?: 0
        adapter = OfflineAdapter(shown) { ask(it) }.also {
            it.selected = position
            pager.adapter = it
        }
        pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        pager.unregisterOnPageChangeCallback(callback)
        pager.registerOnPageChangeCallback(callback)
        if (position > 0) pager.setCurrentItem(position, false)
        counter.text = "${position + 1} / ${shown.size}"
        adapter?.playAt(position)
    }

    private val callback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(selected: Int) {
            position = selected
            counter.text = "${selected + 1} / ${shown.size}"
            adapter?.playAt(selected)
        }
    }

    private fun buildChips() {
        chips.removeAllViews()
        // stable two-pass sort: alphabetical, then by how many videos each author has
        val groups = all.groupingBy { it.author }.eachCount().entries
            .sortedBy { it.key }
            .sortedByDescending { it.value }
        chips.addView(chip("All", author.isEmpty())) {
            author = ""
            show(all, null)
        }
        groups.forEach { (name, count) ->
            chips.addView(chip("$name · $count", author == name)) {
                author = name
                show(all.filter { it.author == name }, null)
            }
        }
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(if (on) R.color.text else R.color.muted))
        textSize = 13f
        setBackgroundResource(if (on) R.drawable.chip_bg_on else R.drawable.chip_bg)
        setPadding(30, 12, 30, 12)
        isSingleLine = true
        setOnClickListener { onClick() }
    }

    private fun ask(at: Int) {
        shown.getOrNull(at)?.let { clip ->
            LocalMenu.show(this, clip) {
                all = all.filterNot { it.uri == clip.uri }
                rebuild(null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick.postDelayed(pulse, 400)
        adapter?.playAt(position)
    }

    override fun onPause() {
        tick.removeCallbacks(pulse)
        adapter?.pauseAll()
        super.onPause()
    }

    override fun onDestroy() {
        adapter = null
        super.onDestroy()
    }
}

/** The tapped file, handed over in memory — LocalVideo is not Parcelable and needs no internet. */
object OfflineQueue {
    var startUri: Uri? = null
}
