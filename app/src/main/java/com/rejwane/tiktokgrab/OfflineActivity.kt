package com.rejwane.tiktokgrab

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.view.MotionEvent
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The offline half of the app, shaped like TikTok: a For You shuffle, an Explore search, and one
 * profile per folder under TikTokGrab with the same header, tabs and grid. Everything reads files
 * already on the phone — the only network use is pulling an author's picture.
 */
class OfflineActivity : AppCompatActivity(), OfflineAdapter.Listener {

    private var videos = listOf<LocalVideo>()
    private var feed = listOf<LocalVideo>()
    private var adapter: OfflineAdapter? = null
    private var opened: String? = null
    private var position = 0
    private var section = FEED
    private var feedTab = FOR_YOU
    private var tab = VIDEOS
    private var order = NEWEST
    private var scope = ALL
    private var browser: TikTokWeb? = null

    private val tick = Handler(Looper.getMainLooper())

    private val pager by lazy { findViewById<ViewPager2>(R.id.offlinePager) }
    private val counter by lazy { findViewById<TextView>(R.id.offlineCounter) }
    private val empty by lazy { findViewById<TextView>(R.id.offlineEmpty) }
    private val feedRoot by lazy { findViewById<View>(R.id.feedRoot) }
    private val exploreRoot by lazy { findViewById<View>(R.id.exploreRoot) }
    private val exploreQuery by lazy { findViewById<EditText>(R.id.exploreQuery) }
    private val exploreChips by lazy { findViewById<LinearLayout>(R.id.exploreChips) }
    private val exploreGrid by lazy { findViewById<RecyclerView>(R.id.exploreGrid) }
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
        profileGrid.layoutManager = GridLayoutManager(this, 3)
        exploreGrid.layoutManager = GridLayoutManager(this, 3)
        pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        pager.registerOnPageChangeCallback(callback)

        // TikTok moves between the top tabs sideways. A fling needs speed nobody uses with a
        // thumb, so the drag itself is measured: past 90dp sideways and the tab changes.
        val edge = 90 * resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        pager.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                }
                MotionEvent.ACTION_UP -> if (section == FEED) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > edge && abs(dx) > abs(dy) * 1.6f) pickFeedTab(if (dx > 0) FOLLOWING else FOR_YOU)
                }
            }
            false
        }

        findViewById<View>(R.id.navHome).setOnClickListener { showFeed() }
        findViewById<View>(R.id.navExplore).setOnClickListener { showExplore() }
        findViewById<View>(R.id.navAdd).setOnClickListener { grabLink() }
        findViewById<View>(R.id.navMe).setOnClickListener { showAuthors() }

        findViewById<View>(R.id.tabForYou).setOnClickListener { pickFeedTab(FOR_YOU) }
        findViewById<View>(R.id.tabFollowing).setOnClickListener { pickFeedTab(FOLLOWING) }
        findViewById<View>(R.id.tabSearch).setOnClickListener { showExplore() }

        findViewById<View>(R.id.profileAvatar).setOnClickListener { openPicture() }
        findViewById<TextView>(R.id.profileFollow).setOnClickListener {
            opened?.let { Follows.toggle(this, it); paintFollow() }
        }
        findViewById<TextView>(R.id.profileHi).setOnClickListener { openUrl(tiktokProfile()) }
        findViewById<TextView>(R.id.profileMore).setOnClickListener { overflow() }
        findViewById<View>(R.id.tabVideos).setOnClickListener { pickTab(VIDEOS) }
        findViewById<View>(R.id.tabShuffle).setOnClickListener { pickTab(SHUFFLE) }
        findViewById<View>(R.id.tabLiked).setOnClickListener { pickTab(LIKED) }
        findViewById<TextView>(R.id.tabSortCaret).setOnClickListener { pickOrder() }

        exploreQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = fillExplore()
        })
        buildChips()

        videos = LocalVideos.scan(this)
        val startUri = OfflineQueue.startUri
        OfflineQueue.startUri = null
        val list = LocalVideos.forYou(this, videos)
        show(list, list.indexOfFirst { it.uri == startUri })
        fetchAvatars()
    }

    // region the feed

    private fun feedSource() =
        if (feedTab == FOLLOWING) videos.filter { Follows.isFollowing(this, it.author) } else videos

    private fun showFeed() = show(LocalVideos.forYou(this, feedSource()), 0)

    private fun pickFeedTab(which: Int) {
        feedTab = which
        show(LocalVideos.forYou(this, feedSource()), 0)
    }

    private fun paintFeedTabs() {
        styleTab(R.id.tabForYou, feedTab == FOR_YOU)
        styleTab(R.id.tabFollowing, feedTab == FOLLOWING)
    }

    private fun styleTab(id: Int, on: Boolean) {
        val view = findViewById<TextView>(id)
        view.setTypeface(view.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        view.setTextColor(getColor(if (on) R.color.on_video else R.color.on_video_muted))
    }

    /** The swipe feed over one list; a negative start just means the top. */
    private fun show(list: List<LocalVideo>, start: Int) {
        section = FEED
        feed = list
        position = if (list.isEmpty()) 0 else start.coerceIn(0, list.size - 1)
        adapter = OfflineAdapter(feed, this).also {
            it.selected = position
            pager.adapter = it
        }
        if (position > 0) pager.setCurrentItem(position, false)
        counter.text = if (feed.isEmpty()) "" else "${position + 1} / ${feed.size}"
        empty.setText(if (feedTab == FOLLOWING && Follows.all(this).isEmpty())
            R.string.follow_someone else R.string.offline_empty)
        empty.visibility = if (feed.isEmpty()) View.VISIBLE else View.GONE
        paintFeedTabs()
        draw()
        adapter?.playAt(position)
    }

    override fun onLongPress(index: Int) {
        feed.getOrNull(index)?.let { ask(it) }
    }

    override fun onOpenProfile(author: String) = showProfile(author)

    /** Comments open inside the app, from the file we saved the last time they were fetched. */
    override fun onComments(video: LocalVideo) = CommentSheet.show(this, video) { pull -> pullComments(pull) }

    /** One page load, and the page hands over its own signed comment responses as it goes. */
    private suspend fun pullComments(video: LocalVideo): Int {
        if (video.clipId.isBlank() || !Cookies.hasLogin()) return 0
        val page = "https://www.tiktok.com/@${video.author}/video/${video.clipId}"
        val web = browser ?: TikTokWeb(this).also { browser = it }
        val got = web.captureComments(page, video.clipId)
        Backup.snapshot(this)
        return got
    }

    override fun onShare(video: LocalVideo) = LocalMenu.share(this, video)

    private fun tiktokProfile(author: String = opened ?: "") = "https://www.tiktok.com/@$author"

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /** The profile picture, full size — or TikTok itself when we never got the file. */
    private fun openPicture() {
        val author = opened ?: return
        val picture = Authors.avatarUri(this, author, big = true)
        if (picture == null) {
            openUrl(tiktokProfile(author))
            return
        }
        ViewerQueue.uri = picture
        ViewerQueue.title = Authors.get(this, author).display(author)
        startActivity(Intent(this, ImageViewerActivity::class.java))
    }

    // endregion

    // region explore

    private fun showExplore() {
        section = EXPLORE
        draw()
        fillExplore()
    }

    private fun buildChips() {
        exploreChips.removeAllViews()
        val chips = listOf(
            "All" to ALL, "Liked" to LIKED_TAB, "Collection" to COLLECTED, "Following" to FOLLOWED
        )
        chips.forEach { (name, which) ->
            exploreChips.addView(chip(name, scope == which) {
                scope = which
                buildChips()
                fillExplore()
            })
        }
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(if (on) R.color.on_accent else R.color.muted))
        textSize = 13f
        setBackgroundResource(if (on) R.drawable.btn_accent else R.drawable.pill_bg)
        setPadding(30, 14, 30, 14)
        isSingleLine = true
        setOnClickListener { onClick() }
    }

    private fun exploreList(): List<LocalVideo> {
        var list = when (scope) {
            LIKED_TAB -> Likes.liked(this, videos)
            COLLECTED -> Collection.inCollection(this, videos)
            FOLLOWED -> videos.filter { Follows.isFollowing(this, it.author) }
            else -> videos
        }
        val query = exploreQuery.text.toString().trim().lowercase()
        if (query.isNotBlank()) list = list.filter { video ->
            video.caption.lowercase().contains(query) ||
                video.author.lowercase().contains(query) ||
                Authors.get(this, video.author).nickname.lowercase().contains(query)
        }
        return list
    }

    private fun fillExplore() {
        if (section != EXPLORE) return
        val list = exploreList()
        exploreGrid.adapter = LocalGridAdapter(list, flat = true,
            onOpen = { show(list, list.indexOf(it)) },
            onMenu = { ask(it) })
    }

    // endregion

    // region profiles

    private fun showAuthors() {
        section = AUTHORS
        authorList.adapter = AuthorAdapter(rows()) { showProfile(it) }
        draw()
    }

    /** Followed authors first, then by how many videos are saved. */
    private fun rows(): List<AuthorRow> =
        videos.groupBy { it.author }.entries
            .sortedBy { it.key }
            .sortedByDescending { it.value.size }
            .sortedByDescending { Follows.isFollowing(this, it.key) }
            .map { entry ->
                val info = Authors.get(this, entry.key)
                AuthorRow(
                    user = entry.key,
                    nickname = info.display(entry.key),
                    count = entry.value.size,
                    bytes = entry.value.sumOf { it.bytes },
                    avatar = Authors.avatarUri(this, entry.key),
                    stats = info.statsNote()
                )
            }

    private fun showProfile(author: String) {
        section = PROFILE
        opened = author
        val info = Authors.get(this, author)
        findViewById<TextView>(R.id.profileName).text = info.display(author)
        findViewById<TextView>(R.id.profileHandle).text = "@$author"
        val bio = findViewById<TextView>(R.id.profileBio)
        bio.text = info.signature
        bio.visibility = if (info.signature.isBlank()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.profileFollowing).text = number(info.following)
        findViewById<TextView>(R.id.profileFollowers).text = number(info.followers)
        findViewById<TextView>(R.id.profileLikes).text = number(info.likes)
        Avatars.into(findViewById<ImageView>(R.id.profileAvatar),
            Authors.avatarUri(this, author, big = true), this)
        paintFollow()
        paintTabs()
        fillGrid()
        draw()
    }

    private fun number(value: Long) = if (value > 0) GridAdapter.short(value) else "\u2013"

    private fun paintFollow() {
        val author = opened ?: return
        val button = findViewById<TextView>(R.id.profileFollow)
        val on = Follows.isFollowing(this, author)
        button.text = getString(if (on) R.string.following_now else R.string.follow)
        button.setBackgroundResource(if (on) R.drawable.btn_outline else R.drawable.btn_accent)
        button.setTextColor(getColor(if (on) R.color.text else R.color.on_accent))
    }

    private fun overflow() {
        val author = opened ?: return
        val labels = arrayOf("Play all", "Shuffle play", "Open this profile on TikTok", "Delete all videos here")
        MaterialAlertDialogBuilder(this)
            .setTitle("@$author")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> { tab = VIDEOS; paintTabs(); fillGrid(); show(profileList(), 0) }
                    1 -> { tab = SHUFFLE; paintTabs(); fillGrid(); show(profileList(), 0) }
                    2 -> openUrl(tiktokProfile(author))
                    3 -> deleteAll(author)
                }
            }
            .show()
    }

    private fun deleteAll(author: String) {
        val list = videosOf(author)
        if (list.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${list.size} videos?")
            .setMessage("Every file in Download/TikTokGrab/$author is removed. This cannot be undone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete all") { _, _ ->
                lifecycleScope.launch {
                    val gone = withContext(Dispatchers.IO) {
                        list.filter { LocalVideos.delete(this@OfflineActivity, it) }.map { it.uri }
                    }
                    videos = videos.filterNot { it.uri in gone }
                    refresh()
                }
            }
            .show()
    }

    private fun pickTab(which: Int) {
        tab = which
        paintTabs()
        fillGrid()
    }

    private fun paintTabs() {
        underline(R.id.tabVideosLine, tab == VIDEOS)
        underline(R.id.tabShuffleLine, tab == SHUFFLE)
        underline(R.id.tabLikedLine, tab == LIKED)
    }

    private fun underline(id: Int, on: Boolean) {
        findViewById<View>(id).visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    private fun pickOrder() {
        val names = arrayOf("Newest first", "Oldest first", "Most played", "Caption A\u2013Z")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(names, order) { dialog, which ->
                order = which
                dialog.dismiss()
                fillGrid()
            }
            .show()
    }

    private fun videosOf(author: String) = videos.filter { it.author == author }

    /** What the profile grid shows: all, shuffled, or only the ones hearted. */
    private fun profileList(): List<LocalVideo> {
        val mine = videosOf(opened ?: return emptyList())
        return when (tab) {
            SHUFFLE -> LocalVideos.forYou(this, mine)
            LIKED -> Likes.liked(this, mine)
            else -> sorted(mine)
        }
    }

    private fun sorted(list: List<LocalVideo>) = when (order) {
        OLD -> list.sortedBy { it.addedAt }
        PLAYED -> list.sortedByDescending { ClipStats.of(this, it.clipId).plays }
        CAPTION -> list.sortedBy { it.caption.lowercase() }
        else -> list.sortedByDescending { it.addedAt }
    }

    private fun fillGrid() {
        if (opened == null) return
        val list = profileList()
        profileGrid.adapter = LocalGridAdapter(list, flat = true,
            onOpen = { show(list, list.indexOf(it)) },
            onMenu = { ask(it) })
    }

    // endregion

    private fun grabLink() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.CLIP_EXTRA, true))
    }

    private fun draw() {
        feedRoot.visibility = if (section == FEED) View.VISIBLE else View.GONE
        exploreRoot.visibility = if (section == EXPLORE) View.VISIBLE else View.GONE
        authorList.visibility = if (section == AUTHORS) View.VISIBLE else View.GONE
        profileRoot.visibility = if (section == PROFILE) View.VISIBLE else View.GONE
        navLabel(R.id.navHomeLabel, section == FEED)
        navLabel(R.id.navExploreLabel, section == EXPLORE)
        navLabel(R.id.navMeLabel, section == AUTHORS || section == PROFILE)
        if (section != FEED) adapter?.pauseAll()
    }

    private fun navLabel(id: Int, on: Boolean) {
        findViewById<TextView>(id).setTextColor(getColor(if (on) R.color.text else R.color.muted))
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
            EXPLORE -> fillExplore()
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
        videos = LocalVideos.scan(this)
        tick.postDelayed(pulse, 400)
        if (section == FEED) adapter?.playAt(position) else refresh()
    }

    override fun onPause() {
        tick.removeCallbacks(pulse)
        adapter?.pauseAll()
        Backup.snapshot(this)
        super.onPause()
    }

    override fun onDestroy() {
        adapter = null
        browser?.destroy()
        browser = null
        super.onDestroy()
    }

    private companion object {
        const val FEED = 0
        const val EXPLORE = 1
        const val AUTHORS = 2
        const val PROFILE = 3

        const val FOR_YOU = 0
        const val FOLLOWING = 1

        const val VIDEOS = 0
        const val SHUFFLE = 1
        const val LIKED = 2

        const val NEWEST = 0
        const val OLD = 1
        const val PLAYED = 2
        const val CAPTION = 3

        const val ALL = 0
        const val LIKED_TAB = 1
        const val COLLECTED = 2
        const val FOLLOWED = 3
    }
}

/** The tapped file, handed over in memory — LocalVideo is not Parcelable and needs no internet. */
object OfflineQueue {
    var startUri: Uri? = null
}
