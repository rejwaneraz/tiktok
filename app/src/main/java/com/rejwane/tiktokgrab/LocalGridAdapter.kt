package com.rejwane.tiktokgrab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Saved videos as a grid. `flat` is the TikTok profile look — edge to edge, no card, no author tag —
 * while the Library tab keeps the padded cards because it mixes authors together.
 */
class LocalGridAdapter(
    private val items: List<LocalVideo>,
    private val flat: Boolean = false,
    private val onOpen: (LocalVideo) -> Unit,
    private val onMenu: (LocalVideo) -> Unit
) : RecyclerView.Adapter<LocalGridAdapter.Holder>() {

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val thumb: ImageView = view.findViewById(if (flat) R.id.gridThumb else R.id.libraryThumb)
        val badge: TextView = view.findViewById(if (flat) R.id.gridViews else R.id.libraryDuration)
        val author: TextView? = if (flat) null else view.findViewById(R.id.libraryAuthor)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(if (flat) R.layout.item_grid else R.layout.item_library, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val clip = items[position]
        holder.author?.text = "@${clip.author}"
        LocalThumbs.into(holder.thumb, holder.badge, clip, holder.root.context)
        holder.root.setOnClickListener { onOpen(clip) }
        holder.root.setOnLongClickListener {
            onMenu(clip)
            true
        }
    }
}
