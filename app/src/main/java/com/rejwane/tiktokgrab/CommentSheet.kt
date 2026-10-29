package com.rejwane.tiktokgrab

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The comments sheet, filled from the file we saved at download time — so it works with no signal.
 * Nothing is ever posted back to TikTok; hearts here are ours alone.
 */
object CommentSheet {

    fun show(host: AppCompatActivity, video: LocalVideo, known: Long = -1L,
             pull: suspend (LocalVideo) -> Boolean) {
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

        val total = if (known >= 0L) known else ClipStats.of(host, video.clipId).comments

        var rows: CommentAdapter? = null

        fun paint(comments: List<Comment>, message: String = "") {
            val shown = if (total > 0) GridAdapter.short(total) else comments.size.toString()
            title.text = "$shown ${host.getString(R.string.comments_suffix)}"
            note.text = message
            note.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            val adapter = CommentAdapter(comments, video.clipId) { comment ->
                // Pictures are the only part of a comment that costs real data, so they come down
                // when the reader asks for them and not a moment before.
                host.lifecycleScope.launch {
                    val got = withContext(Dispatchers.IO) {
                        Comments.downloadPhotos(host, video.clipId, comment)
                    }
                    if (got) rows?.photoReady(comment.id)
                }
            }
            rows = adapter
            list.adapter = adapter
            // A picture is part of reading the comment, so it comes down when the sheet opens —
            // missing ones only, once, and never during a batch fetch.
            val pending = Comments.pendingPictures(host, video.clipId, comments)
            if (pending.isNotEmpty()) {
                host.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        pending.forEach { comment ->
                            if (Comments.downloadPhotos(host, video.clipId, comment)) {
                                withContext(Dispatchers.Main) { rows?.photoReady(comment.id) }
                            }
                        }
                    }
                }
            }
        }

        val saved = Comments.load(host, video.clipId)
        when {
            saved.isNotEmpty() -> paint(saved)
            video.clipId.isBlank() -> paint(saved, host.getString(R.string.comments_no_id))
            else -> {
                paint(saved, host.getString(R.string.comments_loading))
                host.lifecycleScope.launch {
                    val answered = runCatching { pull(video) }.getOrDefault(false)
                    val kept = Comments.load(host, video.clipId)
                    paint(kept, when {
                        kept.isNotEmpty() -> ""
                        answered -> host.getString(R.string.comments_none)
                        else -> host.getString(R.string.comments_unreachable)
                    })
                }
            }
        }
        dialog.show()
    }

    /**
     * The online preview has no file yet, so the same sheet is opened over a clip id — its own
     * comment count comes from the listing that put the clip on screen.
     */
    fun show(host: AppCompatActivity, clipId: String, title: String, known: Long,
             pull: suspend (String) -> Boolean) {
        if (clipId.isBlank()) return
        val stub = LocalVideo(android.net.Uri.EMPTY, title, title, clipId, "", 0L, 0L, 0L)
        show(host, stub, known) { video -> pull(video.clipId) }
    }
}

class CommentAdapter(
    private val items: List<Comment>,
    private val clipId: String,
    private val onPhoto: (Comment) -> Unit
) : RecyclerView.Adapter<CommentAdapter.Holder>() {

    /** The tapped picture has landed on disk: repaint just that row. */
    fun photoReady(commentId: String) {
        val at = items.indexOfFirst { it.id == commentId }
        if (at >= 0) notifyItemChanged(at)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val row: View = view
        val avatar: ImageView = view.findViewById(R.id.cmtAvatar)
        val name: TextView = view.findViewById(R.id.cmtName)
        val text: TextView = view.findViewById(R.id.cmtText)
        val photosScroll: android.widget.HorizontalScrollView = view.findViewById(R.id.cmtPhotosScroll)
        val photos: android.widget.LinearLayout = view.findViewById(R.id.cmtPhotos)
        val time: TextView = view.findViewById(R.id.cmtTime)
        val replies: TextView = view.findViewById(R.id.cmtReplies)
        val like: ImageView = view.findViewById(R.id.cmtLike)
        val likes: TextView = view.findViewById(R.id.cmtLikes)
    }

    /** One thumbnail per picture the comment carries; a missing one is the download button. */
    private fun picture(context: Context, uri: android.net.Uri?, comment: Comment): ImageView {
        val density = context.resources.displayMetrics.density
        val size = (104 * density).toInt()
        val view = ImageView(context)
        view.layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
            marginEnd = (8 * density).toInt()
        }
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setBackgroundResource(R.drawable.card_bg)
        if (uri != null) {
            view.load(uri) { crossfade(false) }
            view.setOnClickListener {
                ViewerQueue.uri = uri
                    ViewerQueue.remote = ""
                    ViewerQueue.count = 1
                ViewerQueue.title = comment.name
                context.startActivity(Intent(context, ImageViewerActivity::class.java))
            }
        } else {
            view.setImageResource(R.drawable.ic_grid)
            view.setOnClickListener { onPhoto(comment) }
        }
        return view
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
        // The reader has no reply endpoint (it answers 404), so this is a count, not a door: it is
        // written without "View" and is not clickable, because nothing would happen.
        holder.replies.text = "${comment.replies} replies"
        holder.replies.visibility = if (comment.replies > 0) View.VISIBLE else View.GONE
        holder.likes.text = if (comment.likes > 0) GridAdapter.short(comment.likes) else ""

        holder.photos.removeAllViews()
        // Read from the folder, not from the JSON: a picture downloaded a second ago must show up
        // without waiting for the comment file to be written again.
        val saved = Comments.pictures(context, clipId, comment)
        when {
            saved.isNotEmpty() -> {
                holder.photosScroll.visibility = View.VISIBLE
                saved.forEach { uri -> holder.photos.addView(picture(context, uri, comment)) }
            }
            comment.photoUrls.isNotEmpty() -> {
                holder.photosScroll.visibility = View.VISIBLE
                comment.photoUrls.forEach { _ -> holder.photos.addView(picture(context, null, comment)) }
            }
            else -> holder.photosScroll.visibility = View.GONE
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
