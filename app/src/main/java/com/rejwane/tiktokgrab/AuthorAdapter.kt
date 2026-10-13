package com.rejwane.tiktokgrab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import java.io.File

/** One folder under TikTokGrab, shown as a profile. */
data class AuthorRow(
    val user: String,
    val nickname: String,
    val count: Int,
    val bytes: Long,
    val avatar: File,
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

/** Cached avatar file if there is one, the plain ring otherwise — never a network wait on the UI thread. */
object Avatars {

    fun into(target: ImageView, file: File, context: android.content.Context) {
        if (file.length() > 0) {
            target.load(file) {
                crossfade(false)
                placeholder(R.drawable.avatar_ring)
                transformations(CircleCropTransformation())
            }
        } else {
            target.setImageResource(R.drawable.avatar_ring)
        }
    }
}
