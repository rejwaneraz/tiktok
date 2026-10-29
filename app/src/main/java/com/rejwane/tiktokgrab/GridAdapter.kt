package com.rejwane.tiktokgrab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.Locale

class GridAdapter(
    private val onOpen: (Int) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<GridAdapter.Holder>() {

    /** Latest is the order TikTok hands them in; Popular is the same set by the play count we hold. */
    enum class Order { LATEST, POPULAR }

    private val raw = ArrayList<Clip>()
    private val items = ArrayList<Clip>()
    private val selected = HashSet<String>()
    private val saved = HashSet<String>()
    var selectMode = false
        private set
    var order = Order.LATEST
        private set

    private fun arrange() {
        items.clear()
        items.addAll(
            if (order == Order.POPULAR) raw.sortedWith(
                compareByDescending<Clip> { it.stat.plays }
                    .thenByDescending { it.stat.likes }
                    .thenByDescending { it.stat.comments }
            ) else raw
        )
    }

    /**
     * Always re-arranges, even when the order is already the one asked for: a list that arrived
     * while the setting was remembered can be unsorted, and an early return left it that way.
     */
    fun setOrder(next: Order) {
        order = next
        arrange()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    /** What the first cover holds after a sort — printed in the status line as proof. */
    fun topPlays(): Long = items.maxOfOrNull { it.stat.plays } ?: 0L

    fun submit(list: List<Clip>, alreadySaved: Set<String>) {
        raw.clear()
        raw.addAll(list)
        arrange()
        selected.clear()
        saved.clear()
        saved.addAll(alreadySaved)
        selectMode = false
        notifyDataSetChanged()
        onSelectionChanged()
    }

    /**
     * A later page arriving while the grid is already on screen. Appending keeps the scroll position
     * and everything already ticked, so a long listing stays usable while it streams in.
     */
    fun append(list: List<Clip>) {
        if (list.isEmpty()) return
        raw.addAll(list)
        if (order == Order.POPULAR) {
            // Sorted view: the new page has to find its own place, so the whole grid is re-ordered.
            arrange()
            notifyDataSetChanged()
        } else {
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }
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
        val playsBadge: TextView = view.findViewById(R.id.playsBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val clip = items[position]
        val on = clip.id in selected
        holder.root.setBackgroundResource(if (on) R.drawable.card_bg_selected else R.drawable.card_bg)
        holder.thumb.load(clip.thumb) {
            crossfade(false)
            // The cell is about 120 dp wide: decoding TikTok's full cover for it is wasted time.
            size(360)
        }
        holder.check.visibility = if (selectMode) View.VISIBLE else View.GONE
        holder.check.isChecked = on
        holder.savedBadge.visibility = if (clip.id in saved) View.VISIBLE else View.GONE
        // TikTok puts the play count on the cover; here it also proves what Popular sorted by.
        holder.playsBadge.text = if (clip.stat.plays > 0) "▶ ${short(clip.stat.plays)}" else "▶ —"
        holder.playsBadge.visibility = View.VISIBLE
        holder.root.setOnClickListener {
            // `position` is captured at bind time: bindingAdapterPosition turns -1 once the holder is
            // recycled, and a tap that lands in that window used to do nothing at all.
            if (selectMode) {
                if (!selected.add(clip.id)) selected.remove(clip.id)
                notifyItemChanged(position)
                onSelectionChanged()
            } else {
                onOpen(position)
            }
        }
        holder.root.setOnLongClickListener {
            if (!selectMode) enterSelect(clip.id) else holder.root.performClick()
            true
        }
    }

    companion object {
        // Formatted in English on purpose: the whole UI is English, so a phone set to a locale that
        // writes decimals with a comma must not turn "1.5M" into "1,5M".
        fun clock(seconds: Int) = "${seconds / 60}:${"%02d".format(Locale.US, seconds % 60)}"

        fun short(n: Long) = when {
            n >= 1_000_000 -> "%.1fM".format(Locale.US, n / 1_000_000.0)
            n >= 1_000 -> "%.1fk".format(Locale.US, n / 1000.0)
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
            bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(Locale.US, bytes / 1000.0)
            else -> "$bytes B"
        }
    }
}
