package com.rejwane.tiktokgrab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class LibraryAdapter(private val items: List<LibraryItem>, private val onOpen: (LibraryItem) -> Unit) :
    RecyclerView.Adapter<LibraryAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val check: CheckBox = view.findViewById(R.id.check)
        val savedBadge: TextView = view.findViewById(R.id.savedBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.check.visibility = View.GONE
        holder.savedBadge.visibility = View.VISIBLE
        holder.root.setBackgroundResource(R.drawable.card_bg)
        holder.thumb.load(item.thumb) { crossfade(false) }
        holder.root.setOnClickListener { onOpen(item) }
    }
}
