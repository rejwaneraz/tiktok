package com.rejwane.tiktokgrab

import android.os.SystemClock
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
import kotlin.math.abs
import kotlin.math.max

/**
 * The swipe feed, laid out the way TikTok does it: caption and sound bottom-left, the action rail
 * down the right edge. Local files only — no cookies, no user agent, nothing that needs a network.
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
        fun onSwipe(toFollowing: Boolean)
    }

    private val holders = HashMap<Int, Holder>()

    /** ViewPager2 skips onPageSelected for page 0, so the adapter starts that one itself. */
    var selected = -1

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val video: android.widget.VideoView = view.findViewById(R.id.localVideo)
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
        private var wantsPlay = false
        private var ready = false
        private var expanded = false
        private var current: LocalVideo? = null
        private var lastBurst = 0L
        private var downX = 0f
        private var downY = 0f
        private var strayed = 0f
        private val tapper = GestureDetector(itemView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(event: MotionEvent): Boolean {
                    current?.let { burst(it, event.x, event.y) }
                    return true
                }
            })

        fun bind(clip: LocalVideo, listener: Listener) {
            val context = itemView.context
            val info = Authors.get(context, clip.author)
            current = clip
            ready = false
            expanded = false
            author.text = "@${info.display(clip.author)}"
            meta.text = "${clip.day()} · ${clip.size()}"
            caption.maxLines = 2
            caption.text = clip.caption
            more.visibility = if (clip.caption.length > 68) View.VISIBLE else View.GONE
            more.text = "See more"
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

        /**
         * The page root is the view that actually owns the touch stream — VideoView declines it and
         * ViewPager2 is only a wrapper around its own list — so both the double tap and the
         * sideways tab swipe have to be read here, not on the pager.
         *
         * A CANCEL means the list has taken the gesture to move to the next video, so it is never a
         * sideways swipe, and the drag must stay near flat to count as one at all.
         */
        fun watch(listener: Listener) {
            itemView.setOnTouchListener { _, event ->
                tapper.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x; downY = event.y; strayed = 0f
                    }
                    MotionEvent.ACTION_MOVE ->
                        strayed = max(strayed, abs(event.y - downY))
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - downX
                        val edge = 130 * itemView.resources.displayMetrics.density
                        if (abs(dx) > edge && strayed < 45 * itemView.resources.displayMetrics.density)
                            listener.onSwipe(dx > 0)
                    }
                    else -> Unit
                }
                false
            }
        }

        /** TikTok's double tap: the video is hearted and a heart pops where the finger landed. */
        private fun burst(clip: LocalVideo, x: Float, y: Float) {
            lastBurst = SystemClock.uptimeMillis()
            if (!Likes.isLiked(itemView.context, clip)) {
                Likes.toggle(itemView.context, clip)
                paint(clip)
            }
            val heart = TextView(itemView.context).apply {
                text = "❤"
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

        fun onTap() {
            if (SystemClock.uptimeMillis() - lastBurst < 400) return
            playOrPause()
        }

        /** Hearts and bookmarks are ours; the numbers beside them are TikTok's own. */
        private fun paint(clip: LocalVideo) {
            val context = itemView.context
            val liked = Likes.isLiked(context, clip)
            tint(like, likeCount, liked, R.color.accent)
            tint(mark, markCount, Collection.has(context, clip), R.color.cyan)
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
            if (ready) {
                video.start()
                pauseIcon.visibility = View.GONE
            }
        }

        fun pause() {
            wantsPlay = false
            if (ready && video.isPlaying) video.pause()
            pauseIcon.visibility = View.VISIBLE
        }

        fun playOrPause() {
            if (ready && video.isPlaying) pause() else start()
        }

        fun refreshBar() {
            val total = video.duration
            if (ready && total > 0) bar.progress = video.currentPosition * 100 / total
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_offline, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        clips.getOrNull(position)?.let { holder.bind(it, listener) }
        holder.boundPosition = position
        holders[position] = holder
        if (position == selected) holder.start()
        holder.itemView.setOnLongClickListener {
            listener.onLongPress(holder.bindingAdapterPosition)
            true
        }
        holder.itemView.setOnClickListener { holder.onTap() }
        holder.watch(listener)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.pause()
        if (holder.boundPosition >= 0) holders.remove(holder.boundPosition)
    }

    override fun getItemCount() = clips.size

    fun playAt(position: Int) {
        selected = position
        holders.forEach { (pos, holder) -> if (pos == position) holder.start() else holder.pause() }
    }

    /**
     * A tab change that keeps the pager and its players: only the list underneath moves. Rebuilding
     * the adapter instead is what made the swipe jerk and restart the video.
     */
    fun swap(list: List<LocalVideo>, at: Int) {
        clips = list
        selected = at
        pauseAll()
        holders.clear()
        notifyDataSetChanged()
    }

    fun pauseAll() = holders.values.toList().forEach { it.pause() }

    fun tick(position: Int) = holders[position]?.refreshBar()
}
