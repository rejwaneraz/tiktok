package com.rejwane.tiktokgrab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Covers come out of the files themselves, so this tab shows something even with no network. */
class LocalGridAdapter(
    private val items: List<LocalVideo>,
    private val onOpen: (LocalVideo) -> Unit,
    private val onMenu: (LocalVideo) -> Unit
) : RecyclerView.Adapter<LocalGridAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val thumb: ImageView = view.findViewById(R.id.libraryThumb)
        val duration: TextView = view.findViewById(R.id.libraryDuration)
        val author: TextView = view.findViewById(R.id.libraryAuthor)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_library, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val clip = items[position]
        holder.author.text = "@${clip.author}"
        LocalThumbs.into(holder.thumb, holder.duration, clip, holder.root.context)
        holder.root.setOnClickListener { onOpen(clip) }
        holder.root.setOnLongClickListener {
            onMenu(clip)
            true
        }
    }
}
