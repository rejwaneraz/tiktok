package com.rejwane.tiktokgrab

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The offline half of the app. For You shuffles everything that is on the phone, and every folder
 * under TikTokGrab is a profile of its own. Nothing here reads the network except an avatar pull.
 */
class OfflineActivity : AppCompatActivity() {

    private var videos = listOf<LocalVideo>()
    private var feed = listOf<LocalVideo>()
    private var adapter: OfflineAdapter? = null
    private var opened: String? = null
    private var position = 0
    private var section = FEED

    private val tick = Handler(Looper.getMainLooper())

    private val pager by lazy { findViewById<ViewPager2>(R.id.offlinePager) }
    private val counter by lazy { findViewById<TextView>(R.id.offlineCounter) }
    private val empty by lazy { findViewById<TextView>(R.id.offlineEmpty) }
    private val feedRoot by lazy { findViewById<View>(R.id.feedRoot) }
    private val authorList by lazy { findViewById<RecyclerView>(R.id.authorList) }
    private val profileRoot by lazy { findViewById<View>(R.id.profileRoot) }
    private val profileGrid by lazy { findViewById<RecyclerView>(R.id.profileGrid) }

    private val pulse = object : Runnable {
        override fun run() {
            adapter?.tick(position)
            tick.postDelayed(this, 400)
        }
    }

    private val callback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(selected: Int) {
            position = selected
            counter.text = "${selected + 1} / ${feed.size}"
            adapter?.playAt(selected)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_offline)
        authorList.layoutManager = LinearLayoutManager(this)
        profileGrid.layoutManager = GridLayoutManager(this, columns())
        pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        pager.registerOnPageChangeCallback(callback)

        findViewById<View>(R.id.navBack).setOnClickListener { finish() }
        findViewById<View>(R.id.navHome).setOnClickListener { showForYou() }
        findViewById<View>(R.id.navProfile).setOnClickListener { showAuthors() }
        findViewById<TextView>(R.id.profilePlay).setOnClickListener {
            opened?.let { show(videosOf(it), 0) }
        }

        videos = LocalVideos.scan(this)
        val startUri = OfflineQueue.startUri
        OfflineQueue.startUri = null
        val list = LocalVideos.forYou(this, videos)
        show(list, list.indexOfFirst { it.uri == startUri })
        fetchAvatars()
    }

    private fun columns(): Int {
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (dp / 120).toInt().coerceIn(3, 5)
    }

    private fun videosOf(author: String) = videos.filter { it.author == author }

    private fun showForYou() = show(LocalVideos.forYou(this, videos), 0)

    /** The swipe feed over one list, starting where asked (a negative start just means the top). */
    private fun show(list: List<LocalVideo>, start: Int) {
        section = FEED
        feed = list
        position = if (list.isEmpty()) 0 else start.coerceIn(0, list.size - 1)
        adapter = OfflineAdapter(feed) { at -> feed.getOrNull(at)?.let { ask(it) } }.also {
            it.selected = position
            pager.adapter = it
        }
        if (position > 0) pager.setCurrentItem(position, false)
        counter.text = if (feed.isEmpty()) "0 / 0" else "${position + 1} / ${feed.size}"
        empty.visibility = if (feed.isEmpty()) View.VISIBLE else View.GONE
        draw()
        adapter?.playAt(position)
    }

    private fun showAuthors() {
        section = AUTHORS
        authorList.adapter = AuthorAdapter(rows()) { showProfile(it) }
        draw()
    }

    private fun rows(): List<AuthorRow> =
        videos.groupBy { it.author }.entries
            .sortedBy { it.key }
            .sortedByDescending { it.value.size }
            .map { entry ->
                val info = Authors.get(this, entry.key)
                AuthorRow(
                    user = entry.key,
                    nickname = info.display(entry.key),
                    count = entry.value.size,
                    bytes = entry.value.sumOf { it.bytes },
                    avatar = Authors.avatarFile(this, entry.key),
                    stats = info.statsNote()
                )
            }

    private fun showProfile(author: String) {
        section = PROFILE
        opened = author
        val info = Authors.get(this, author)
        val list = videosOf(author)
        findViewById<TextView>(R.id.profileName).text = info.display(author)
        findViewById<TextView>(R.id.profileHandle).text = "@$author"
        val stats = info.statsNote()
        findViewById<TextView>(R.id.profileStats).text = if (stats.isBlank())
            "${list.size} saved · ${GridAdapter.mb(list.sumOf { it.bytes })}" else stats
        Avatars.into(findViewById<ImageView>(R.id.profileAvatar),
            Authors.avatarFile(this, author), this)
        profileGrid.adapter = LocalGridAdapter(list, onOpen = { show(list, list.indexOf(it)) },
            onMenu = { ask(it) })
        draw()
    }

    private fun draw() {
        feedRoot.visibility = if (section == FEED) View.VISIBLE else View.GONE
        authorList.visibility = if (section == AUTHORS) View.VISIBLE else View.GONE
        profileRoot.visibility = if (section == PROFILE) View.VISIBLE else View.GONE
        if (section != FEED) adapter?.pauseAll()
    }

    private fun ask(clip: LocalVideo) {
        LocalMenu.show(this, clip, videos.map { it.author }.distinct(),
            onDeleted = {
                videos = videos.filterNot { it.uri == clip.uri }
                refresh()
            },
            onMoved = {
                videos = LocalVideos.scan(this)
                refresh()
            })
    }

    /** Keeps whatever screen is open, only re-pointed at the new set of files. */
    private fun refresh() {
        when (section) {
            FEED -> show(feed.filter { kept -> videos.any { it.uri == kept.uri } }, position)
            AUTHORS -> showAuthors()
            else -> (opened ?: videos.firstOrNull()?.author)?.let { showProfile(it) }
        }
    }

    /** One small picture per author, pulled once and then kept for offline use. */
    private fun fetchAvatars() {
        val missing = videos.map { it.author }.distinct().filter { !Authors.hasAvatar(this, it) }
        if (missing.isEmpty()) return
        lifecycleScope.launch {
            val got = withContext(Dispatchers.IO) {
                missing.count { Authors.fetchAvatar(this@OfflineActivity, it) }
            }
            if (got > 0) refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        tick.postDelayed(pulse, 400)
        if (section == FEED) adapter?.playAt(position)
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

    private companion object {
        const val FEED = 0
        const val AUTHORS = 1
        const val PROFILE = 2
    }
}

/** The tapped file, handed over in memory — LocalVideo is not Parcelable and needs no internet. */
object OfflineQueue {
    var startUri: Uri? = null
}
