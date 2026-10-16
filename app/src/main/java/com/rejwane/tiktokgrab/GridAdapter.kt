package com.rejwane.tiktokgrab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class GridAdapter(
    private val onOpen: (Int) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<GridAdapter.Holder>() {

    private val items = ArrayList<Clip>()
    private val selected = HashSet<String>()
    private val saved = HashSet<String>()
    var selectMode = false
        private set

    fun submit(list: List<Clip>, alreadySaved: Set<String>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        saved.clear()
        saved.addAll(alreadySaved)
        selectMode = false
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun enterSelect(id: String) {
        selectMode = true
        selected.add(id)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun exitSelect() {
        selectMode = false
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun tickAll(on: Boolean) {
        selected.clear()
        if (on) items.forEach { selected.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    /** The useful one for repeat visits: everything TikTok shows that is not on the phone yet. */
    fun selectUnsaved(): Int {
        selectMode = true
        selected.clear()
        items.filter { it.id !in saved }.forEach { selected.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged()
        return selected.size
    }

    fun markSaved(id: String) {
        saved.add(id)
        indexOf(id).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    fun indexOf(id: String) = items.indexOfFirst { it.id == id }

    fun clipAt(position: Int): Clip? = items.getOrNull(position)

    fun clips(): List<Clip> = items.toList()

    fun count() = items.size

    fun selectedClips(): List<Clip> = items.filter { it.id in selected }

    fun selectedCount() = selected.size

    fun selectedBytes() = items.filter { it.id in selected }.sumOf { it.bytes }

    fun unsavedCount() = items.count { it.id !in saved }

    fun total() = items.size

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
        val clip = items[position]
        val on = clip.id in selected
        holder.root.setBackgroundResource(if (on) R.drawable.card_bg_selected else R.drawable.card_bg)
        holder.thumb.load(clip.thumb) { crossfade(false) }
        holder.check.visibility = if (selectMode) View.VISIBLE else View.GONE
        holder.check.isChecked = on
        holder.savedBadge.visibility = if (clip.id in saved) View.VISIBLE else View.GONE
        holder.root.setOnClickListener {
            if (selectMode) {
                if (!selected.add(clip.id)) selected.remove(clip.id)
                notifyItemChanged(holder.bindingAdapterPosition)
                onSelectionChanged()
            } else {
                onOpen(holder.bindingAdapterPosition)
            }
        }
        holder.root.setOnLongClickListener {
            if (!selectMode) enterSelect(clip.id) else holder.root.performClick()
            true
        }
    }

    companion object {
        fun clock(seconds: Int) = "${seconds / 60}:${"%02d".format(seconds % 60)}"

        fun short(n: Long) = when {
            n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
            n >= 1_000 -> "%.1fk".format(n / 1000.0)
            else -> n.toString()
        }

        /** TikTok's own shorthand: 7m, 19h, 1d, 2w, 1y. */
        fun ago(seconds: Long): String {
            if (seconds <= 0) return ""
            val age = (System.currentTimeMillis() / 1000 - seconds).coerceAtLeast(0)
            val day = 86400L
            return when {
                age < 60 -> "${age}s ago"
                age < 3600 -> "${age / 60}m ago"
                age < day -> "${age / 3600}h ago"
                age < day * 30 -> "${age / day}d ago"
                age < day * 365 -> "${age / (day * 30)}w ago"
                else -> "${age / (day * 365)}y ago"
            }
        }

        fun mb(bytes: Long) = when {
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1000.0)
            else -> "$bytes B"
        }
    }
}
