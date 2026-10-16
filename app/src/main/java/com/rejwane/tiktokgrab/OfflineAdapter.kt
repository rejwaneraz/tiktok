package com.rejwane.tiktokgrab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Local files only — no cookies, no user agent, nothing that needs a network. */
class OfflineAdapter(
    private val clips: List<LocalVideo>,
    private val onLongPress: (Int) -> Unit
) : RecyclerView.Adapter<OfflineAdapter.Holder>() {

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
        val like: TextView = view.findViewById(R.id.localLike)
        val plays: TextView = view.findViewById(R.id.localPlays)
        var boundPosition = -1
        private var wantsPlay = false
        private var ready = false
        private var expanded = false

        fun bind(clip: LocalVideo) {
            ready = false
            expanded = false
            author.text = "@${clip.author}"
            meta.text = "${clip.day()} · ${clip.size()}"
            caption.maxLines = 2
            caption.text = clip.caption
            more.visibility = if (clip.caption.length > 68) View.VISIBLE else View.GONE
            more.text = "See more"
            caption.setOnClickListener { toggle() }
            more.setOnClickListener { toggle() }
            paint(clip)
            like.setOnClickListener {
                Likes.toggle(itemView.context, clip)
                paint(clip)
            }
            video.setOnPreparedListener { player ->
                ready = true
                player.isLooping = true
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

        /** Heart is ours; the play count is TikTok's own, kept from when the list was read. */
        private fun paint(clip: LocalVideo) {
            val liked = Likes.isLiked(itemView.context, clip)
            like.text = if (liked) "♥" else "♡"
            like.setTextColor(itemView.context.getColor(if (liked) R.color.accent else R.color.on_video))
            val views = Authors.viewCount(itemView.context, clip.clipId)
            plays.text = if (views > 0) "▶ ${GridAdapter.short(views)}" else ""
            plays.visibility = if (views > 0) View.VISIBLE else View.GONE
        }

        private fun toggle() {
            expanded = !expanded
            caption.maxLines = if (expanded) Int.MAX_VALUE else 2
            more.text = if (expanded) "See less" else "See more"
            more.visibility = if (clipTooLong()) View.VISIBLE else View.GONE
        }

        private fun clipTooLong() = caption.text.length > 68

        fun playOrPause() {
            if (ready && video.isPlaying) pause() else start()
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

        fun refreshBar() {
            val total = video.duration
            if (ready && total > 0) bar.progress = video.currentPosition * 100 / total
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_offline, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        clips.getOrNull(position)?.let { holder.bind(it) }
        holder.boundPosition = position
        holders[position] = holder
        if (position == selected) holder.start()
        holder.itemView.setOnLongClickListener {
            onLongPress(holder.bindingAdapterPosition)
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
