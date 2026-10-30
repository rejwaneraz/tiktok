package com.rejwane.tiktokgrab

import android.net.Uri
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
 * The online preview: a clip that is still on TikTok's CDN. It carries the same rail as the offline
 * feed so the two screens feel like one app. Every control here is local — the numbers beside the
 * icons are TikTok's, the heart and the save are ours.
 */
class PlayerAdapter(
    private val clips: List<Clip>,
    private val listener: Listener
) : RecyclerView.Adapter<PlayerAdapter.Holder>() {

    interface Listener {
        fun onProfile(author: String)
        /** Toggles our own heart; returns whether it is on after the tap. */
        fun onLike(clip: Clip): Boolean
        fun onComment(clip: Clip)
        fun onSave(clip: Clip)
        fun onShare(clip: Clip)
    }

    private val holders = HashMap<Int, Holder>()

    /** ViewPager2 never fires onPageSelected for the first page, so the adapter tracks it itself. */
    var selected = -1

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val video: android.widget.VideoView = view.findViewById(R.id.video)
        val pauseIcon: TextView = view.findViewById(R.id.pauseIcon)
        val bar: ProgressBar = view.findViewById(R.id.bar)
        val caption: TextView = view.findViewById(R.id.caption)
        val meta: TextView = view.findViewById(R.id.meta)
        val avatar: ImageView = view.findViewById(R.id.pAvatar)
        val follow: ImageView = view.findViewById(R.id.pFollow)
        val like: ImageView = view.findViewById(R.id.pLike)
        val likeCount: TextView = view.findViewById(R.id.pLikeCount)
        val comment: ImageView = view.findViewById(R.id.pComment)
        val commentCount: TextView = view.findViewById(R.id.pCommentCount)
        val mark: ImageView = view.findViewById(R.id.pMark)
        val markCount: TextView = view.findViewById(R.id.pMarkCount)
        val share: ImageView = view.findViewById(R.id.pShare)
        val shareCount: TextView = view.findViewById(R.id.pShareCount)
        var boundPosition = -1
        private var wantsPlay = false
        private var ready = false
        private var current: Clip? = null
        private var owner: Listener? = null
        private val tapper = GestureDetector(itemView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    // Only a tap that never became a double tap pauses, so a heart never stops the video.
                    if (ready) {
                        if (video.isPlaying) pause() else start()
                    }
                    return true
                }

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    val clip = current ?: return true
                    val who = owner ?: return true
                    if (Likes.isLiked(itemView.context, clip.id)) return true
                    if (who.onLike(clip)) paintLike(clip, true)
                    heart(event.x, event.y)
                    return true
                }
            })

        fun bind(clip: Clip, owner: Listener) {
            this.owner = owner
            current = clip
            ready = false
            val context = itemView.context
            caption.text = clip.title.ifBlank { clip.page }
            meta.text = buildString {
                if (clip.stat.plays > 0) append(GridAdapter.short(clip.stat.plays)).append(" views")
                if (clip.duration > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(GridAdapter.clock(clip.duration))
                }
                if (clip.bytes > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(GridAdapter.mb(clip.bytes))
                }
            }

            Avatars.into(avatar, Authors.avatarUri(context, clip.author), context)
            avatar.setOnClickListener { owner.onProfile(clip.author) }
            follow.visibility = if (Follows.isFollowing(context, clip.author)) View.GONE else View.VISIBLE
            follow.setOnClickListener {
                if (Follows.toggle(context, clip.author)) follow.visibility = View.GONE
            }

            likeCount.text = short(clip.stat.likes)
            commentCount.text = short(clip.stat.comments)
            markCount.text = short(clip.stat.collects)
            shareCount.text = "Share"
            paintLike(clip, Likes.isLiked(context, clip.id))
            paintSave(clip)
            like.setOnClickListener {
                paintLike(clip, owner.onLike(clip))
            }
            comment.setOnClickListener { owner.onComment(clip) }
            mark.setOnClickListener {
                owner.onSave(clip)
                paintSave(clip)
            }
            share.setOnClickListener { owner.onShare(clip) }

            // A photo post in the same list has no stream; it is shown as a caption, not a crash.
            if (clip.play.isBlank()) {
                pauseIcon.visibility = View.GONE
                meta.text = "Photo post — open it from the library"
                return
            }
            video.setOnPreparedListener { player ->
                ready = true
                player.isLooping = true
                if (wantsPlay) start()
            }
            video.setOnErrorListener { _, _, _ ->
                caption.text = "Cannot play — tap again"
                true
            }
            val headers = if (clip.page.contains("instagram.com"))
                mapOf("User-Agent" to Http.UA, "Referer" to InstagramApi.REFERER)
            else mapOf(
                "Cookie" to Cookies.header(),
                "User-Agent" to Http.UA,
                "Referer" to "https://www.tiktok.com/"
            )
            video.setVideoURI(Uri.parse(clip.play), headers)
            pauseIcon.visibility = View.VISIBLE
        }

        private fun short(value: Long) = if (value > 0) GridAdapter.short(value) else ""

        private fun paintLike(clip: Clip, on: Boolean) {
            val color = itemView.context.getColor(if (on) R.color.accent else R.color.on_video)
            like.setColorFilter(color)
            likeCount.setTextColor(color)
        }

        private fun paintSave(clip: Clip) {
            val saved = clip.id in Downloader.doneIds(itemView.context)
            // The slot is a download arrow here, so "done" reads green rather than a bookmark yellow.
            val color = itemView.context.getColor(if (saved) R.color.selected else R.color.on_video)
            mark.setColorFilter(color)
            markCount.setTextColor(color)
        }

        /** TikTok's double tap: a heart pops where the finger landed and the clip is hearted. */
        private fun heart(x: Float, y: Float) {
            val pop = TextView(itemView.context).apply {
                text = "\u2764"
                textSize = 58f
                setTextColor(itemView.context.getColor(R.color.accent))
                translationX = x - 40f
                translationY = y - 60f
            }
            (itemView as FrameLayout).addView(pop)
            pop.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f).setDuration(650L).withEndAction {
                (itemView as FrameLayout).removeView(pop)
            }.start()
        }

        fun watch() {
            itemView.setOnTouchListener { _, event ->
                tapper.onTouchEvent(event)
                false
            }
        }

        fun start() {
            wantsPlay = true
            if (ready) {
                if (!video.isPlaying) video.start()
                pauseIcon.visibility = View.GONE
            }
        }

        fun pause() {
            wantsPlay = false
            if (ready && video.isPlaying) video.pause()
            pauseIcon.visibility = View.VISIBLE
        }

        fun refreshBar() {
            val total = video.duration
            if (ready && total > 0) bar.progress = video.currentPosition * 100 / total
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_player, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        clips.getOrNull(position)?.let { holder.bind(it, listener) }
        holder.boundPosition = position
        holders.entries.removeAll { it.value === holder && it.key != position }
        holders[position] = holder
        holder.watch()
        if (position == selected) holder.start()
        holder.itemView.setOnLongClickListener { false }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.pause()
        holder.boundPosition = -1
        holders.entries.removeAll { it.value === holder }
    }

    override fun getItemCount() = clips.size

    fun playAt(position: Int) {
        selected = position
        holders.entries.toList().forEach { (pos, holder) ->
            if (pos == position) holder.start() else holder.pause()
        }
    }

    fun pauseAll() = holders.values.toList().forEach { it.pause() }

    fun tick(position: Int) = holders[position]?.refreshBar()
}
