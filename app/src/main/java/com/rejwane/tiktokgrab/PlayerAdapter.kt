package com.rejwane.tiktokgrab

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlayerAdapter(private val clips: List<Clip>) : RecyclerView.Adapter<PlayerAdapter.Holder>() {

    private val holders = HashMap<Int, Holder>()

    /** ViewPager2 never fires onPageSelected for the first page, so the adapter tracks it itself. */
    var selected = -1

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val video: android.widget.VideoView = view.findViewById(R.id.video)
        val pauseIcon: TextView = view.findViewById(R.id.pauseIcon)
        val bar: ProgressBar = view.findViewById(R.id.bar)
        val caption: TextView = view.findViewById(R.id.caption)
        val meta: TextView = view.findViewById(R.id.meta)
        var boundPosition = -1
        private var wantsPlay = false
        private var ready = false

        fun bind(clip: Clip) {
            ready = false
            caption.text = clip.title.ifBlank { clip.page }
            meta.text = buildString {
                append(GridAdapter.short(clip.views)).append(" views")
                append(" · ").append(GridAdapter.clock(clip.duration))
                if (clip.bytes > 0) append(" · ").append(GridAdapter.mb(clip.bytes))
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
            video.setVideoURI(
                Uri.parse(clip.play),
                mapOf(
                    "Cookie" to Cookies.header(),
                    "User-Agent" to Http.UA,
                    "Referer" to "https://www.tiktok.com/"
                )
            )
            pauseIcon.visibility = View.VISIBLE
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
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_player, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        clips.getOrNull(position)?.let { holder.bind(it) }
        holder.boundPosition = position
        holders[position] = holder
        if (position == selected) holder.start()
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
