package com.rejwane.tiktokgrab

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

/** One folder under TikTokGrab, shown as a profile. */
data class AuthorRow(
    val user: String,
    val nickname: String,
    val count: Int,
    val bytes: Long,
    val avatar: Uri?,
    val stats: String
) {
    fun name() = nickname.ifBlank { user }

    fun note() = buildString {
        append(count).append(if (count == 1) " video" else " videos")
        append(" · ").append(GridAdapter.mb(bytes))
        if (stats.isNotBlank()) append(" · ").append(stats)
    }
}

class AuthorAdapter(
    private val rows: List<AuthorRow>,
    private val onOpen: (String) -> Unit
) : RecyclerView.Adapter<AuthorAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val avatar: ImageView = view.findViewById(R.id.authorAvatar)
        val name: TextView = view.findViewById(R.id.authorName)
        val handle: TextView = view.findViewById(R.id.authorHandle)
        val note: TextView = view.findViewById(R.id.authorNote)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_author, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.name.text = row.name()
        holder.handle.text = "@${row.user}"
        holder.note.text = row.note()
        Avatars.into(holder.avatar, row.avatar, holder.root.context)
        holder.root.setOnClickListener { onOpen(row.user) }
    }
}

/** The cached picture when we have one, the plain ring otherwise — never a wait on the UI thread. */
object Avatars {

    fun into(target: ImageView, avatar: Uri?, context: Context) {
        if (avatar != null) {
            target.load(avatar) {
                crossfade(false)
                placeholder(R.drawable.avatar_ring)
                transformations(CircleCropTransformation())
                // The largest circle this is drawn in is about 96 dp; the stored picture is bigger.
                size(400)
            }
        } else {
            target.setImageResource(R.drawable.avatar_ring)
        }
    }
}
