package com.rejwane.tiktokgrab

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
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

    /** The tag chip the user picked in Explore; empty means no tag filter. */
    private var tag = ""

    // One gesture state machine for the whole feed; see onDrag.
    private var dragMode = DRAG_NONE
    private var dragFromX = 0f
    private var dragFromY = 0f
    private var dragWidth = 1f

    // The same machine again for a profile, which has to be read before its own children.
    private var profileMode = DRAG_NONE
    private var profileFromX = 0f
    private var profileFromY = 0f
    private var profileWidth = 1f

    /** True while a list swap is re-seating the pager, so its own callbacks cannot fight us. */
    private var swapping = false

    private val tick = Handler(Looper.getMainLooper())

    private val pager by lazy { findViewById<ViewPager2>(R.id.offlinePager) }
    private val counter by lazy { findViewById<TextView>(R.id.offlineCounter) }
    private val empty by lazy { findViewById<TextView>(R.id.offlineEmpty) }
    private val feedRoot by lazy { findViewById<View>(R.id.feedRoot) }
    private val exploreRoot by lazy { findViewById<View>(R.id.exploreRoot) }
    private val exploreQuery by lazy { findViewById<EditText>(R.id.exploreQuery) }
    private val exploreChips by lazy { findViewById<LinearLayout>(R.id.exploreChips) }
    private val exploreTags by lazy { findViewById<LinearLayout>(R.id.exploreTags) }
    private val exploreTagsScroll by lazy { findViewById<View>(R.id.exploreTagsScroll) }
    private val exploreGrid by lazy { findViewById<RecyclerView>(R.id.exploreGrid) }
    private val authorList by lazy { findViewById<RecyclerView>(R.id.authorList) }
    private val profileRoot by lazy { findViewById<View>(R.id.profileRoot) }
    private val profileGrid by lazy { findViewById<RecyclerView>(R.id.profileGrid) }

    /**
     * One adapter per grid, reused — rebuilding it on every keystroke and resume is what dragged.
     * The type is written out because the click handler asks the same adapter for its list.
     */
    private val exploreAdapter: LocalGridAdapter by lazy {
        LocalGridAdapter(
            emptyList(), flat = true,
            onOpen = { video ->
                val list = exploreAdapter.snapshot()
                val at = list.indexOfFirst { it.uri == video.uri }
                if (at >= 0) show(list, at)
            },
            onMenu = { ask(it) }
        )
    }
    private val profileAdapter: LocalGridAdapter by lazy {
        LocalGridAdapter(
            emptyList(), flat = true,
            onOpen = { video ->
                val list = profileAdapter.snapshot()
                val at = list.indexOfFirst { it.uri == video.uri }
                if (at >= 0) show(list, at)
            },
            onMenu = { ask(it) }
        )
    }

    /** Typing in Explore waits a moment, so a long library is filtered once instead of per letter. */
    private val searchTick = Handler(Looper.getMainLooper())
    private val searchRun = Runnable { fillExplore() }

    private val pulse = object : Runnable {
        override fun run() {
            adapter?.tick(position)
            tick.postDelayed(this, 400)
        }
    }

    private val callback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(selected: Int) {
            // A list swap can make the pager report page 0 before we have re-seated it; taking that
            // seriously would jump the feed back to the first video after every tab change.
            if (swapping && selected != adapter?.selected) return
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

        findViewById<View>(R.id.navHome).setOnClickListener { showFeed() }
        findViewById<View>(R.id.navExplore).setOnClickListener { showExplore() }
        findViewById<View>(R.id.navAdd).setOnClickListener { grabLink() }
        findViewById<View>(R.id.navMe).setOnClickListener { showAuthors() }

        findViewById<View>(R.id.tabForYou).setOnClickListener { switchTab(FOR_YOU) }
        findViewById<View>(R.id.tabFollowing).setOnClickListener { switchTab(FOLLOWING) }
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
            override fun afterTextChanged(s: Editable?) {
                searchTick.removeCallbacks(searchRun)
                searchTick.postDelayed(searchRun, 250)
            }
        })
        buildChips()

        val startUri = OfflineQueue.startUri
        OfflineQueue.startUri = null
        // The folder is read off the main thread, so the shell draws before the list is ready.
        lifecycleScope.launch {
            videos = LocalVideos.scanOf(this@OfflineActivity)
            // A fresh roll every time the feed opens, the way TikTok does it; tab switches reuse it.
            val list = LocalVideos.forYou(this@OfflineActivity, videos, fresh = true)
            show(list, list.indexOfFirst { it.uri == startUri })
            fetchAvatars()
        }
    }

    // region the feed

    private fun feedSource() =
        if (feedTab == FOLLOWING) videos.filter { Follows.isFollowing(this, it.author) } else videos

    private fun showFeed() {
        if (section == FEED) return
        val next = pinned(LocalVideos.forYou(this, feedSource()))
        backToFeed(next.first, next.second)
    }

    /** TikTok's own sideways order: Following, For You, then the profile of what is playing. */
    private fun slots() = listOf(SLOT_FOLLOWING, SLOT_FORYOU, SLOT_PROFILE)

    private fun slotNow() = when {
        section == PROFILE -> SLOT_PROFILE
        feedTab == FOLLOWING -> SLOT_FOLLOWING
        else -> SLOT_FORYOU
    }

    /**
     * One state machine for every touch on the feed, and no decision is left to the page.
     *
     * The vertical pager used to take a sideways drag away with an ACTION_CANCEL as soon as the
     * finger drifted a few dp downwards, which is why the swipe answered only sometimes. Now the
     * page holds the gesture on DOWN, decides on the first real movement, and hands it back the
     * moment the drag is vertical — so a page turn and a screen swipe never fight.
     */
    override fun onDrag(event: MotionEvent, page: View): Boolean {
        val slop = 9f * resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = DRAG_UNDECIDED
                dragFromX = event.x
                dragFromY = event.y
                dragWidth = page.width.toFloat().coerceAtLeast(1f)
                page.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - dragFromX
                val dy = event.y - dragFromY
                if (dragMode == DRAG_UNDECIDED) {
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        dragMode = DRAG_VERTICAL
                        page.parent?.requestDisallowInterceptTouchEvent(false)
                    } else if (abs(dx) > slop && abs(dx) > abs(dy) * 1.15f) {
                        dragMode = DRAG_HORIZONTAL
                        pager.animate().cancel()
                    }
                }
                if (dragMode == DRAG_HORIZONTAL) {
                    pager.translationX = dx
                    pager.alpha = 1f - (abs(dx) / dragWidth) * 0.22f
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragMode == DRAG_HORIZONTAL) {
                    val dx = event.x - dragFromX
                    if (abs(dx) > dragWidth * 0.25f) finishDrag(dx < 0) else settleDrag()
                }
                dragMode = DRAG_NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragMode == DRAG_HORIZONTAL) settleDrag()
                dragMode = DRAG_NONE
            }
        }
        return dragMode == DRAG_HORIZONTAL
    }

    private fun finishDrag(forward: Boolean) {
        val out = if (forward) -dragWidth else dragWidth
        pager.animate().translationX(out).alpha(0.65f).setDuration(130L).withEndAction {
            pager.translationX = -out
            pager.alpha = 0.65f
            stepSlot(forward)
            pager.animate().translationX(0f).alpha(1f).setDuration(170L).start()
        }.start()
    }

    private fun settleDrag() {
        pager.animate().translationX(0f).alpha(1f).setDuration(150L).start()
    }

    // region sideways on a profile

    /**
     * A profile is a scroll view full of buttons, so a page's touch listener never sees the drag.
     * Reading it in dispatchTouchEvent happens before any child, and once the drag is clearly
     * sideways the activity takes the rest of the gesture — that is why going back used to fail.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (section == PROFILE && profileTouch(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun profileTouch(ev: MotionEvent): Boolean {
        val slop = 9f * resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                profileMode = DRAG_UNDECIDED
                profileFromX = ev.x
                profileFromY = ev.y
                profileWidth = profileRoot.width.toFloat().coerceAtLeast(1f)
                profileRoot.animate().cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - profileFromX
                val dy = ev.y - profileFromY
                if (profileMode == DRAG_UNDECIDED) {
                    if (abs(dy) > slop && abs(dy) > abs(dx)) profileMode = DRAG_VERTICAL
                    else if (abs(dx) > slop && abs(dx) > abs(dy) * 1.15f) profileMode = DRAG_HORIZONTAL
                }
                if (profileMode == DRAG_HORIZONTAL) {
                    profileRoot.translationX = dx
                    profileRoot.alpha = 1f - (abs(dx) / profileWidth) * 0.22f
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (profileMode == DRAG_HORIZONTAL) {
                    val dx = ev.x - profileFromX
                    if (abs(dx) > profileWidth * 0.25f) finishProfileDrag(dx < 0) else settleProfileDrag()
                    profileMode = DRAG_NONE
                    return true
                }
                profileMode = DRAG_NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (profileMode == DRAG_HORIZONTAL) {
                    settleProfileDrag()
                    profileMode = DRAG_NONE
                    return true
                }
                profileMode = DRAG_NONE
            }
        }
        return false
    }

    private fun finishProfileDrag(forward: Boolean) {
        if (section != PROFILE) return
        val target = nextSlot(forward)
        val out = if (forward) -profileWidth else profileWidth
        profileRoot.animate().translationX(out).alpha(0.6f).setDuration(130L).withEndAction {
            profileRoot.translationX = 0f
            profileRoot.alpha = 1f
            backToFeed()
            applySlot(target)
        }.start()
    }

    private fun settleProfileDrag() {
        profileRoot.animate().translationX(0f).alpha(1f).setDuration(150L).start()
    }

    // endregion

    private fun stepSlot(forward: Boolean) = applySlot(nextSlot(forward))

    private fun nextSlot(forward: Boolean): Int {
        val order = slots()
        val at = order.indexOf(slotNow()).coerceAtLeast(0)
        val step = if (forward) 1 else order.size - 1
        return order[(at + step) % order.size]
    }

    private fun applySlot(slot: Int) {
        when (slot) {
            SLOT_PROFILE -> {
                // Stepping in, the profile is whoever made the clip that is playing — never whoever
                // was opened last time.
                val author = feed.getOrNull(position)?.author ?: opened
                if (author != null) showProfile(author) else switchTab(FOR_YOU)
            }
            SLOT_FOLLOWING -> switchTab(FOLLOWING)
            else -> switchTab(FOR_YOU)
        }
    }

    /** The top tabs and a sideways drag both land here, and neither may throw you back to video one. */
    private fun switchTab(which: Int) {
        if (which == feedTab && section == FEED) return
        feedTab = which
        val next = pinned(LocalVideos.forYou(this, feedSource()))
        if (section != FEED) backToFeed(next.first, next.second) else swapTo(next.first, next.second)
    }

    /**
     * The clip that is playing keeps its page index in the new list, so the pager does not move at
     * all and the page that already holds it is never rebound. Only a clip that left the list (an
     * unfollowed author) starts again from the top.
     */
    private fun pinned(list: List<LocalVideo>): Pair<List<LocalVideo>, Int> {
        if (list.isEmpty()) return list to 0
        val clip = feed.getOrNull(position)
        val at = clip?.let { playing -> list.indexOfFirst { it.uri == playing.uri } } ?: -1
        if (at < 0) return list to 0
        val target = position.coerceIn(0, list.size - 1)
        val n = list.size
        if (at == target) return list to target
        // Ring from the current clip outwards, then rotate it back so that clip lands on `target`.
        val ring = ArrayList<LocalVideo>(n)
        ring.addAll(list.subList(at, n))
        ring.addAll(list.subList(0, at))
        val rotated = ArrayList<LocalVideo>(n)
        for (i in 0 until n) rotated.add(ring[(i - target + n) % n])
        return rotated to target
    }

    /** Changes what the feed holds without dropping the pager, so the video keeps playing. */
    private fun swapTo(list: List<LocalVideo>, index: Int) {
        val held = adapter ?: return show(list, index)
        feed = list
        position = if (list.isEmpty()) 0 else index.coerceIn(0, list.size - 1)
        swapping = true
        held.swap(list, position)
        counter.text = if (list.isEmpty()) "" else "${position + 1} / ${list.size}"
        empty.setText(if (feedTab == FOLLOWING && Follows.all(this).isEmpty())
            R.string.follow_someone else R.string.offline_empty)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        paintFeedTabs()
        // Seat the pager after the adapter has taken the new list, then make sure the clip that was
        // playing is the one holding the screen.
        pager.post {
            if (list.isNotEmpty() && pager.currentItem != position) pager.setCurrentItem(position, false)
            swapping = false
            held.playAt(position)
        }
    }

    /** Coming back from Explore, Me or a profile keeps the feed, its page and its player as they were. */
    private fun backToFeed(list: List<LocalVideo> = feed, at: Int = position) {
        section = FEED
        draw()
        swapTo(list, at)
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
        swapping = false
        pager.translationX = 0f
        pager.alpha = 1f
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

    /** Comments open inside the app: the saved file first, one small pull when there is none. */
    override fun onComments(video: LocalVideo) = CommentSheet.show(this, video) { pull -> pullComments(pull) }

    private suspend fun pullComments(video: LocalVideo): Boolean = withContext(Dispatchers.IO) {
        val got = Comments.fetch(this@OfflineActivity, video.clipId, video.isPhoto)
        Backup.snapshot(this@OfflineActivity)
        !got.failed
    }

    override fun onShare(video: LocalVideo) = LocalMenu.share(this, video)

    /** A slideshow is one clip holding many pictures: the tap hands the whole set to the viewer. */
    override fun onOpenPhotos(video: LocalVideo) {
        val urls = (if (video.photos.isNotEmpty()) video.photos else listOf(video.uri)).map { it.toString() }
        ViewerQueue.urls = urls
        ViewerQueue.title = video.caption.ifBlank { "Photo post" }
        startActivity(Intent(this, ImageViewerActivity::class.java))
    }

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
        ViewerQueue.urls = listOf(picture.toString())
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

    /**
     * The library tags itself: every clip is matched against the word table in Tags, and a tag only
     * becomes a chip when at least two clips carry it — one clip is not a category.
     */
    /**
     * A clip belongs to whatever collections the user put it in — nothing is guessed from a caption
     * any more. One clip can sit in as many collections as it deserves.
     */
    private fun labelsOf(video: LocalVideo): List<String> =
        ClipTags.of(this, ClipTags.key(video.clipId, video.name))

    /** Collections are made by hand, so even one clip is a real chip here. */
    private fun tagCounts(): List<Pair<String, Int>> =
        ClipTags.known(this)
            .map { name -> name to videos.count { video -> name in labelsOf(video) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(8)

    private fun buildTagChips() {
        exploreTags.removeAllViews()
        val counts = tagCounts()
        if (counts.isEmpty()) {
            exploreTagsScroll.visibility = View.GONE
            tag = ""
            return
        }
        if (tag.isNotBlank() && counts.none { it.first == tag }) tag = ""
        exploreTagsScroll.visibility = View.VISIBLE
        val gap = (8 * resources.displayMetrics.density).toInt()
        counts.forEach { (label, count) ->
            val view = chip("$label $count", tag == label) {
                tag = if (tag == label) "" else label
                buildTagChips()
                fillExplore()
            }
            view.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = gap }
            exploreTags.addView(view)
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
        if (tag.isNotBlank()) list = list.filter { video -> tag in labelsOf(video) }
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
        buildTagChips()
        exploreAdapter.submit(exploreList())
        if (exploreGrid.adapter !== exploreAdapter) exploreGrid.adapter = exploreAdapter
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
        profileRoot.alpha = 0f
        profileRoot.animate().alpha(1f).setDuration(140L).start()
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
        profileAdapter.submit(profileList())
        if (profileGrid.adapter !== profileAdapter) profileGrid.adapter = profileAdapter
    }

    // endregion

    private fun grabLink() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.CLIP_EXTRA, true))
    }

    private fun draw() {
        // The feed stays behind every other screen: hiding it was what stopped the video and made
        // coming back feel like a restart. Explore, Me and a profile are opaque, so nothing leaks.
        feedRoot.visibility = View.VISIBLE
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
                lifecycleScope.launch {
                    videos = LocalVideos.scanOf(this@OfflineActivity)
                    refresh()
                }
            })
    }

    /** Keeps whatever screen is open, only re-pointed at the new set of files. */
    private fun refresh() {
        when (section) {
            // The feed keeps its adapter: a download landing or a clip being deleted must not
            // rebuild every page, which is what restarted the video that was already playing.
            FEED -> swapTo(feed.filter { kept -> videos.any { it.uri == kept.uri } }, position)
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
        tick.postDelayed(pulse, 400)
        // Resume first, then look for new files off the main thread: only a real change redraws.
        if (section == FEED) adapter?.playAt(position) else refresh()
        rescan()
    }

    /**
     * A download finishing on another screen, or a file removed by a manager, is the only reason the
     * list changes while this screen is alive. The read happens on IO, and the screens are left alone
     * when nothing moved — which is most of the time.
     */
    private fun rescan() {
        lifecycleScope.launch {
            val fresh = LocalVideos.scanOf(this@OfflineActivity)
            val before = videos.map { it.uri }
            val after = fresh.map { it.uri }
            videos = fresh
            if (before != after) refresh()
        }
    }

    override fun onPause() {
        tick.removeCallbacks(pulse)
        adapter?.pauseAll()
        Backup.snapshot(this)
        super.onPause()
    }

    /** Back goes to the feed first, which keeps the clip playing instead of closing the app. */
    @Deprecated("The system back button is the third way out of a profile")
    override fun onBackPressed() {
        if (section != FEED) {
            backToFeed()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        searchTick.removeCallbacks(searchRun)
        adapter = null
        super.onDestroy()
    }

    private companion object {
        const val FEED = 0
        const val EXPLORE = 1
        const val AUTHORS = 2
        const val PROFILE = 3

        // One gesture state machine: undecided until the first real movement.
        const val DRAG_NONE = 0
        const val DRAG_UNDECIDED = 1
        const val DRAG_VERTICAL = 2
        const val DRAG_HORIZONTAL = 3

        // The three screens a sideways drag walks between, kept apart from the section numbers above.
        const val SLOT_FOLLOWING = 10
        const val SLOT_FORYOU = 11
        const val SLOT_PROFILE = 12

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
