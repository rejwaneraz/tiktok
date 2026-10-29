package com.rejwane.tiktokgrab

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * The swipe feed, laid out the way TikTok does it: caption and sound bottom-left, the action rail
 * down the right edge. Local files only — no cookies, no user agent, nothing that needs a network.
 *
 * The page does not decide anything about gestures any more. It reports every touch to the activity,
 * which owns one state machine for the whole feed; that is what stopped a tab change from rebuilding
 * the list and restarting the video.
 */
class OfflineAdapter(
    private var clips: List<LocalVideo>,
    private val listener: Listener
) : RecyclerView.Adapter<OfflineAdapter.Holder>() {

    interface Listener {
        fun onLongPress(index: Int)
        fun onOpenProfile(author: String)
        fun onComments(video: LocalVideo)
        fun onShare(video: LocalVideo)

        /** Every horizontal or vertical decision is made in one place: returns true when we took the drag. */
        fun onDrag(event: MotionEvent, page: View): Boolean
    }

    private val holders = HashMap<Int, Holder>()

    /** ViewPager2 skips onPageSelected for page 0, so the adapter starts that one itself. */
    var selected = -1

    init {
        setHasStableIds(true)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val video: android.widget.VideoView = view.findViewById(R.id.localVideo)
        val photo: ImageView = view.findViewById(R.id.localPhoto)
        val pauseIcon: TextView = view.findViewById(R.id.localPause)
        val bar: ProgressBar = view.findViewById(R.id.localBar)
        val author: TextView = view.findViewById(R.id.localAuthor)
        val caption: TextView = view.findViewById(R.id.localCaption)
        val more: TextView = view.findViewById(R.id.localMore)
        val meta: TextView = view.findViewById(R.id.localMeta)
        val sound: TextView = view.findViewById(R.id.localSound)
        val avatar: ImageView = view.findViewById(R.id.railAvatar)
        val follow: ImageView = view.findViewById(R.id.railFollow)
        val like: ImageView = view.findViewById(R.id.railLike)
        val likeCount: TextView = view.findViewById(R.id.railLikeCount)
        val comment: ImageView = view.findViewById(R.id.railComment)
        val commentCount: TextView = view.findViewById(R.id.railCommentCount)
        val mark: ImageView = view.findViewById(R.id.railMark)
        val markCount: TextView = view.findViewById(R.id.railMarkCount)
        val share: ImageView = view.findViewById(R.id.railShare)
        val shareCount: TextView = view.findViewById(R.id.railShareCount)
        var boundPosition = -1
        var shows: String = ""
        private var wantsPlay = false
        private var ready = false
        private var expanded = false
        private var current: LocalVideo? = null
        private val tapper = GestureDetector(itemView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    // Fires only when the second tap never came, which is what stops a double tap
                    // from pausing the video it is meant to heart.
                    playOrPause()
                    return true
                }

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    current?.let { burst(it, event.x, event.y) }
                    return true
                }
            })

        fun bind(clip: LocalVideo, listener: Listener) {
            val context = itemView.context
            val info = Authors.get(context, clip.author)
            // The same clip coming back after a tab swap must not be loaded a second time: the
            // player keeps its position and the video never restarts.
            val same = current?.uri == clip.uri
            current = clip
            shows = clip.uri.toString()
            if (same) return paintEverything(clip, info, listener, reuse = true)

            ready = false
            wantsPlay = false
            expanded = false
            caption.maxLines = 2
            paintEverything(clip, info, listener, reuse = false)
            // A photo post has no player: the picture is the whole page.
            video.visibility = if (clip.isPhoto) View.GONE else View.VISIBLE
            photo.visibility = if (clip.isPhoto) View.VISIBLE else View.GONE
            if (clip.isPhoto) {
                photo.load(clip.uri) { crossfade(false) }
                ready = true
                pauseIcon.visibility = View.GONE
                return
            }
            video.setOnPreparedListener { mp ->
                ready = true
                mp.isLooping = true
                if (video.duration > 0) {
                    meta.text = "${clip.day()} · ${GridAdapter.clock(video.duration / 1000)} · ${clip.size()}"
                }
                if (wantsPlay) start()
            }
            video.setOnErrorListener { _, _, _ ->
                meta.text = "Cannot play this file"
                true
            }
            video.setVideoURI(clip.uri)
            pauseIcon.visibility = View.VISIBLE
        }

        private fun paintEverything(clip: LocalVideo, info: AuthorInfo, listener: Listener, reuse: Boolean) {
            val context = itemView.context
            author.text = "@${info.display(clip.author)}"
            val length = if (ready && video.duration > 0) " · ${GridAdapter.clock(video.duration / 1000)}" else ""
            meta.text = "${clip.day()}$length · ${clip.size()}"
            if (!reuse) {
                caption.maxLines = 2
                more.text = "See more"
                more.visibility = if (clip.caption.length > 68) View.VISIBLE else View.GONE
            }
            caption.text = clip.caption
            caption.setOnClickListener { toggle() }
            more.setOnClickListener { toggle() }

            val stat = ClipStats.of(context, clip.clipId)
            val line = listOf(stat.sound, stat.soundAuthor).filter { it.isNotBlank() }.joinToString(" - ")
            sound.text = if (line.isBlank()) "" else "\u266b $line"
            sound.visibility = if (line.isBlank()) View.GONE else View.VISIBLE
            likeCount.text = count(stat.likes)
            commentCount.text = count(stat.comments)
            markCount.text = count(stat.collects)
            shareCount.text = "Share"

            Avatars.into(avatar, Authors.avatarUri(context, clip.author), context)
            avatar.setOnClickListener { listener.onOpenProfile(clip.author) }
            follow.visibility = if (Follows.isFollowing(context, clip.author)) View.GONE else View.VISIBLE
            follow.setOnClickListener {
                if (Follows.toggle(context, clip.author)) follow.visibility = View.GONE
            }
            paint(clip)
            like.setOnClickListener {
                Likes.toggle(context, clip)
                paint(clip)
            }
            mark.setOnClickListener {
                Collection.toggle(context, clip)
                paint(clip)
            }
            comment.setOnClickListener { listener.onComments(clip) }
            share.setOnClickListener { listener.onShare(clip) }
        }

        /** The page reports touches; the activity decides. Never a click here, or a drag becomes a tap. */
        fun watch(listener: Listener) {
            itemView.setOnTouchListener { page, event ->
                tapper.onTouchEvent(event)
                listener.onDrag(event, page)
            }
        }

        /** TikTok's double tap: the video is hearted and a heart pops where the finger landed. */
        private fun burst(clip: LocalVideo, x: Float, y: Float) {
            if (!Likes.isLiked(itemView.context, clip)) {
                Likes.toggle(itemView.context, clip)
                paint(clip)
            }
            val heart = TextView(itemView.context).apply {
                text = "\u2764"
                textSize = 58f
                setTextColor(context.getColor(R.color.accent))
                translationX = x - 40f
                translationY = y - 60f
            }
            (itemView as FrameLayout).addView(heart)
            heart.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f).setDuration(650L).withEndAction {
                (itemView as FrameLayout).removeView(heart)
            }.start()
        }

        /** Hearts and bookmarks are ours; the numbers beside them are TikTok's own. */
        private fun paint(clip: LocalVideo) {
            val context = itemView.context
            val liked = Likes.isLiked(context, clip)
            tint(like, likeCount, liked, R.color.accent)
            tint(mark, markCount, Collection.has(context, clip), R.color.saved)
        }

        private fun tint(icon: ImageView, label: TextView, on: Boolean, color: Int) {
            icon.setColorFilter(itemView.context.getColor(if (on) color else R.color.on_video))
            label.setTextColor(itemView.context.getColor(if (on) color else R.color.on_video))
        }

        private fun count(value: Long) = if (value > 0) GridAdapter.short(value) else ""

        private fun toggle() {
            expanded = !expanded
            caption.maxLines = if (expanded) Int.MAX_VALUE else 2
            more.text = if (expanded) "See less" else "See more"
            more.visibility = if (caption.text.length > 68) View.VISIBLE else View.GONE
        }

        fun start() {
            wantsPlay = true
            if (current?.isPhoto == true) return
            if (ready) {
                if (!video.isPlaying) video.start()
                pauseIcon.visibility = View.GONE
            }
        }

        fun pause() {
            wantsPlay = false
            if (current?.isPhoto == true) return
            if (ready && video.isPlaying) video.pause()
            pauseIcon.visibility = View.VISIBLE
        }

        fun playOrPause() {
            if (current?.isPhoto == true) return
            if (ready && video.isPlaying) pause() else start()
        }

        fun refreshBar() {
            if (current?.isPhoto == true) return
            val total = video.duration
            if (ready && total > 0) bar.progress = video.currentPosition * 100 / total
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_offline, parent, false))

    override fun getItemId(position: Int): Long {
        val clip = clips.getOrNull(position) ?: return RecyclerView.NO_ID
        return clip.uri.toString().hashCode().toLong()
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val clip = clips.getOrNull(position) ?: return
        holder.bind(clip, listener)
        holder.boundPosition = position
        holders.entries.removeAll { it.value === holder && it.key != position }
        holders[position] = holder
        if (position == selected) holder.start()
        holder.itemView.setOnLongClickListener {
            val at = holder.bindingAdapterPosition
            if (at != RecyclerView.NO_POSITION) listener.onLongPress(at)
            true
        }
        // No click listener: the detector's single-tap is what toggles play, so a double tap never
        // fires a pause on its way to the heart.
        holder.watch(listener)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.pause()
        holder.boundPosition = -1
        holders.entries.removeAll { it.value === holder }
    }

    override fun getItemCount() = clips.size

    fun clipAt(position: Int) = clips.getOrNull(position)

    fun playAt(position: Int) {
        selected = position
        holders.entries.toList().forEach { (pos, holder) ->
            if (pos == position) holder.start() else holder.pause()
        }
    }

    /**
     * A tab change that keeps the pager and its players: the list underneath moves, the page that
     * holds the current clip stays bound to the same holder, so nothing is reloaded and nothing jumps.
     */
    fun swap(list: List<LocalVideo>, at: Int) {
        // A reordered list that still holds the same clips in the same place is not a change at all:
        // not notifying keeps every page, and therefore the player, exactly as it was.
        val unchanged = list.size == clips.size && list.indices.all { list[it].uri == clips[it].uri }
        clips = list
        selected = at
        if (!unchanged) notifyDataSetChanged()
    }

    fun pauseAll() = holders.values.toList().forEach { it.pause() }

    fun tick(position: Int) = holders[position]?.refreshBar()
}
