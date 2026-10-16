package com.rejwane.tiktokgrab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * The swipe feed, laid out the way TikTok does it: caption and sound bottom-left, the action rail
 * down the right edge. Local files only — no cookies, no user agent, nothing that needs a network.
 */
class OfflineAdapter(
    private val clips: List<LocalVideo>,
    private val listener: Listener
) : RecyclerView.Adapter<OfflineAdapter.Holder>() {

    interface Listener {
        fun onLongPress(index: Int)
        fun onOpenProfile(author: String)
        fun onOpenPage(video: LocalVideo)
        fun onShare(video: LocalVideo)
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

        fun bind(clip: LocalVideo, listener: Listener) {
            val context = itemView.context
            val info = Authors.get(context, clip.author)
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

            Avatars.into(avatar, Authors.avatarFile(context, clip.author), context)
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
            comment.setOnClickListener { listener.onOpenPage(clip) }
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
        holder.itemView.setOnClickListener { holder.playOrPause() }
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

    fun pauseAll() = holders.values.toList().forEach { it.pause() }

    fun tick(position: Int) = holders[position]?.refreshBar()
}
