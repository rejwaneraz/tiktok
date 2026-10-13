package com.rejwane.tiktokgrab

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** TikTok-style vertical feed: swipe up/down to move between videos, ⬇ saves the current one. */
class PlayerActivity : AppCompatActivity() {

    private var adapter: PlayerAdapter? = null
    private var position = 0
    private var saveTask: Job? = null
    private val tick = Handler(Looper.getMainLooper())
    private val counter by lazy { findViewById<TextView>(R.id.counter) }
    private val saveBtn by lazy { findViewById<TextView>(R.id.saveBtn) }
    private val saveLabel by lazy { findViewById<TextView>(R.id.saveLabel) }
    private val status by lazy { findViewById<TextView>(R.id.playerStatus) }

    private val pulse = object : Runnable {
        override fun run() {
            adapter?.tick(position)
            tick.postDelayed(this, 400)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_player)

        val clips = PlayerQueue.clips
        if (clips.isEmpty()) {
            finish()
            return
        }
        position = PlayerQueue.start.coerceIn(0, clips.size - 1)

        adapter = PlayerAdapter(clips).also {
            it.selected = position
            pager().adapter = it
        }
        pager().orientation = ViewPager2.ORIENTATION_VERTICAL
        pager().registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(selected: Int) {
                position = selected
                counter.text = "${selected + 1} / ${clips.size}"
                adapter?.playAt(selected)
                refreshSaveLabel(clips.getOrNull(selected))
            }
        })
        findViewById<TextView>(R.id.close).setOnClickListener { finish() }
        saveBtn.setOnClickListener { clips.getOrNull(position)?.let { download(it) } }

        if (position > 0) pager().setCurrentItem(position, false)
        counter.text = "${position + 1} / ${clips.size}"
        adapter?.playAt(position)
        refreshSaveLabel(clips.getOrNull(position))
    }

    private fun pager() = findViewById<ViewPager2>(R.id.pager)

    private fun refreshSaveLabel(clip: Clip?) {
        if (clip == null) return
        when {
            clip.id in Downloader.doneIds(this) -> {
                saveBtn.text = "✓"
                saveLabel.text = "Saved"
            }
            saveTask?.isActive == true -> saveLabel.text = "Saving…"
            else -> {
                saveBtn.text = "⬇"
                saveLabel.text = getString(R.string.save)
            }
        }
    }

    private fun download(clip: Clip) {
        if (clip.id in Downloader.doneIds(this)) {
            status.text = "Already downloaded"
            return
        }
        if (clip.play.isBlank()) {
            status.text = "Not a video"
            return
        }
        saveTask = lifecycleScope.launch {
            try {
                val result = Downloader.save(this@PlayerActivity, clip) { percent ->
                    status.text = "Downloading… ${percent.toInt()}%"
                }
                status.text = if (result.skipped) "Already downloaded" else "Saved → ${result.path}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Failed: ${e.message}"
            } finally {
                saveTask = null
                refreshSaveLabel(clip)
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
        saveTask?.cancel()
        adapter = null
        PlayerQueue.clear()
        super.onDestroy()
    }
}

/**
 * Passing the whole list through an Intent would need Parcelable plumbing for every field, so the
 * grid hands it over in memory — only ever read straight after startActivity.
 */
object PlayerQueue {
    var clips: List<Clip> = emptyList()
    var start: Int = 0

    fun clear() {
        clips = emptyList()
        start = 0
    }
}
