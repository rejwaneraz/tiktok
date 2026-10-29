package com.rejwane.tiktokgrab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Saved videos as a grid. `flat` is the TikTok profile look — edge to edge, no card, no author tag —
 * while the Library tab keeps the padded cards because it mixes authors together.
 *
 * One adapter per grid, reused: rebuilding it on every resume, keystroke or tab switch is what made a
 * large profile feel heavy and drop taps while it was being replaced.
 */
class LocalGridAdapter(
    initial: List<LocalVideo>,
    private val flat: Boolean = false,
    var selectMode: Boolean = false,
    private val selected: MutableSet<String> = HashSet(),
    private val onOpen: (LocalVideo) -> Unit,
    private val onMenu: (LocalVideo) -> Unit,
    private val onToggled: () -> Unit = {}
) : RecyclerView.Adapter<LocalGridAdapter.Holder>() {

    private val items = ArrayList<LocalVideo>(initial)

    fun submit(list: List<LocalVideo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun snapshot(): List<LocalVideo> = items.toList()

    fun indexOf(video: LocalVideo) = items.indexOfFirst { it.uri == video.uri }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val thumb: ImageView = view.findViewById(if (flat) R.id.gridThumb else R.id.libraryThumb)
        val badge: TextView = view.findViewById(if (flat) R.id.gridViews else R.id.libraryDuration)
        val author: TextView? = if (flat) null else view.findViewById(R.id.libraryAuthor)
        val check: CheckBox? = if (flat) null else view.findViewById(R.id.libraryCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
        LayoutInflater.from(parent.context)
            .inflate(if (flat) R.layout.item_grid else R.layout.item_library, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val clip = items[position]
        holder.author?.text = "@${clip.author}"
        LocalThumbs.into(holder.thumb, holder.badge, clip, holder.root.context)
        val key = ClipTags.key(clip.clipId, clip.name)
        val ticked = selectMode && key in selected
        holder.root.setBackgroundResource(
            if (ticked) R.drawable.card_bg_selected else R.drawable.card_bg
        )
        holder.check?.visibility = if (selectMode) View.VISIBLE else View.GONE
        holder.check?.isChecked = ticked
        holder.root.setOnClickListener {
            if (selectMode) {
                if (!selected.add(key)) selected.remove(key)
                notifyItemChanged(position)
                onToggled()
            } else {
                onOpen(clip)
            }
        }
        holder.root.setOnLongClickListener {
            if (selectMode) {
                holder.root.performClick()
            } else {
                onMenu(clip)
            }
            true
        }
    }
}
