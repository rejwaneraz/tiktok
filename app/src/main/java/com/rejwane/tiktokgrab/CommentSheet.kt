package com.rejwane.tiktokgrab

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

/**
 * The comments sheet, filled from the file we saved at download time — so it works with no signal.
 * Nothing is ever posted back to TikTok; hearts here are ours alone.
 */
object CommentSheet {

    fun show(host: AppCompatActivity, video: LocalVideo, pull: suspend (LocalVideo) -> Int) {
        val view = LayoutInflater.from(host).inflate(R.layout.sheet_comments, null)
        val title = view.findViewById<TextView>(R.id.commentsTitle)
        val list = view.findViewById<RecyclerView>(R.id.commentsList)
        val note = view.findViewById<TextView>(R.id.commentsNote)
        list.layoutManager = LinearLayoutManager(host)

        val dialog = BottomSheetDialog(host)
        dialog.setContentView(view)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.peekHeight = (host.resources.displayMetrics.heightPixels * 0.75f).toInt()
        view.findViewById<TextView>(R.id.commentsClose).setOnClickListener { dialog.dismiss() }

        val total = ClipStats.of(host, video.clipId).comments

        fun paint(comments: List<Comment>, message: String = "") {
            val shown = if (total > 0) GridAdapter.short(total) else comments.size.toString()
            title.text = "$shown ${host.getString(R.string.comments_suffix)}"
            note.text = message
            note.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            list.adapter = CommentAdapter(comments)
        }

        val saved = Comments.load(host, video.clipId)
        when {
            saved.isNotEmpty() -> paint(saved)
            video.clipId.isBlank() -> paint(saved, host.getString(R.string.comments_no_id))
            !Cookies.hasLogin() -> paint(saved, host.getString(R.string.comments_need_login))
            else -> {
                paint(saved, host.getString(R.string.comments_loading))
                host.lifecycleScope.launch {
                    val got = runCatching { pull(video) }.getOrDefault(0)
                    val kept = Comments.load(host, video.clipId)
                    paint(kept, if (got == 0 && kept.isEmpty()) host.getString(R.string.comments_none) else "")
                }
            }
        }
        dialog.show()
    }
}

class CommentAdapter(private val items: List<Comment>) : RecyclerView.Adapter<CommentAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val row: View = view
        val avatar: ImageView = view.findViewById(R.id.cmtAvatar)
        val name: TextView = view.findViewById(R.id.cmtName)
        val text: TextView = view.findViewById(R.id.cmtText)
        val photo: ImageView = view.findViewById(R.id.cmtPhoto)
        val time: TextView = view.findViewById(R.id.cmtTime)
        val replies: TextView = view.findViewById(R.id.cmtReplies)
        val like: ImageView = view.findViewById(R.id.cmtLike)
        val likes: TextView = view.findViewById(R.id.cmtLikes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val comment = items[position]
        val context = holder.row.context
        holder.name.text = comment.name
        holder.text.text = comment.text
        holder.time.text = comment.ago()
        holder.avatar.setImageResource(Names.drawable(Names.guess(comment.name)))
        holder.replies.text = "View ${comment.replies} replies"
        holder.replies.visibility = if (comment.replies > 0) View.VISIBLE else View.GONE
        holder.likes.text = if (comment.likes > 0) GridAdapter.short(comment.likes) else ""

        val picture = if (comment.photo.isBlank()) null else Store.find(context, "photos", comment.photo)
        if (picture != null) {
            holder.photo.visibility = View.VISIBLE
            holder.photo.load(picture) { crossfade(false) }
        } else {
            holder.photo.visibility = View.GONE
        }

        fun paintHeart(on: Boolean) {
            holder.like.alpha = if (on) 1f else 0.55f
            holder.like.setColorFilter(context.getColor(if (on) R.color.accent else R.color.muted))
            holder.likes.setTextColor(context.getColor(if (on) R.color.accent else R.color.muted))
        }
        paintHeart(CommentLikes.liked(context, comment.id))
        holder.like.setOnClickListener {
            paintHeart(CommentLikes.toggle(context, comment.id))
        }
    }
}

/** Hearts on other people's comments, kept on this phone only. */
object CommentLikes {

    private const val PREF = "comment_likes"
    private const val KEY = "ids"

    private fun ids(context: Context) = context.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
        .getStringSet(KEY, emptySet()) ?: emptySet()

    fun liked(context: Context, id: String) = id.isNotEmpty() && id in ids(context)

    fun toggle(context: Context, id: String): Boolean {
        val now = HashSet(ids(context))
        val on = if (id in now) {
            now.remove(id); false
        } else {
            now.add(id); true
        }
        context.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, now).apply()
        return on
    }
}
