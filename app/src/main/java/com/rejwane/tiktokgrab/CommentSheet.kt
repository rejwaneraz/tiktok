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

        /** One comment's pictures landed: read them off-main, then repaint only that row. */
        fun refresh(commentId: String) {
            val adapter = rows ?: return
            val at = adapter.indexOf(commentId)
            val comment = adapter.commentAt(at) ?: return
            host.lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) {
                    Comments.pictures(host, video.clipId, comment)
                }
                adapter.putPictures(commentId, found)
                adapter.notifyItemChanged(at)
            }
        }

        /**
         * Comments and the pictures already on disk come from the folder, so they are read off the
         * frame thread — a sheet that lists its rows while the disk answers is a sheet that stutters.
         */
        fun paint(read: Pair<List<Comment>, Map<String, List<android.net.Uri>>>, message: String = "") {
            val comments = read.first
            val adapter = CommentAdapter(comments, read.second) { comment ->
                // Pictures are the only part of a comment that costs real data, so they come down
                // when the reader asks for them and not a moment before.
                host.lifecycleScope.launch {
                    val got = withContext(Dispatchers.IO) {
                        Comments.downloadPhotos(host, video.clipId, comment)
                    }
                    if (got) refresh(comment.id)
                }
            }
            rows = adapter
            list.adapter = adapter
            val shown = if (total > 0) GridAdapter.short(total) else comments.size.toString()
            title.text = "$shown ${host.getString(R.string.comments_suffix)}"
            note.text = message
            note.visibility = if (message.isBlank()) View.GONE else View.VISIBLE

            // A picture is part of reading the comment, so it comes down when the sheet opens —
            // every picture still missing, once, and never during a batch fetch. A comment that got
            // two of its four before the signal went must still be topped up.
            val pending = comments.filter {
                it.photoUrls.isNotEmpty() &&
                    read.second[it.id].orEmpty().size < it.photoUrls.size
            }
            if (pending.isNotEmpty()) {
                host.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        pending.forEach { comment ->
                            if (Comments.downloadPhotos(host, video.clipId, comment)) {
                                withContext(Dispatchers.Main) { refresh(comment.id) }
                            }
                        }
                    }
                }
            }
        }

        host.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { Comments.load(host, video.clipId) }
            when {
                saved.isNotEmpty() -> paint(saved to pictures(host, video.clipId, saved))
                video.clipId.isBlank() ->
                    paint(saved to pictures(host, video.clipId, saved), host.getString(R.string.comments_no_id))
                else -> {
                    paint(saved to pictures(host, video.clipId, saved),
                        host.getString(R.string.comments_loading))
                    val answered = runCatching { pull(video) }.getOrDefault(false)
                    val kept = withContext(Dispatchers.IO) { Comments.load(host, video.clipId) }
                    paint(kept to pictures(host, video.clipId, kept), when {
                        kept.isNotEmpty() -> ""
                        answered -> host.getString(R.string.comments_none)
                        else -> host.getString(R.string.comments_unreachable)
                    })
                }
            }
        }
        dialog.show()
    }

    /** The comments plus every picture of theirs already on disk, read together off the frame thread. */
    private suspend fun pictures(context: Context, clipId: String,
                                 comments: List<Comment>): Map<String, List<android.net.Uri>> =
        withContext(Dispatchers.IO) {
            comments.associate { it.id to Comments.pictures(context, clipId, it) }
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
    photos: Map<String, List<android.net.Uri>>,
    private val onPhoto: (Comment) -> Unit
) : RecyclerView.Adapter<CommentAdapter.Holder>() {

    // Held as a mutable copy: a picture that lands while the sheet is open has to reach its row.
    private val photos = HashMap(photos)

    fun indexOf(commentId: String) = items.indexOfFirst { it.id == commentId }

    fun commentAt(index: Int): Comment? = items.getOrNull(index)

    /** The tapped picture has landed on disk: remember it and let the caller repaint the row. */
    fun putPictures(commentId: String, uris: List<android.net.Uri>) {
        photos[commentId] = uris
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
                ViewerQueue.urls = listOf(uri.toString())
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
        // Read from the folder when the sheet opened, not per row: the map was filled off the frame
        // thread, and a picture that lands afterwards repaints its own row.
        val saved = photos[comment.id].orEmpty()
        // One slot per picture the comment carries: the ones on disk show, the ones still missing
        // show as a tappable square, so a partly-downloaded comment never hides its own rest.
        val slots = maxOf(saved.size, comment.photoUrls.size)
        if (slots == 0) {
            holder.photosScroll.visibility = View.GONE
        } else {
            holder.photosScroll.visibility = View.VISIBLE
            (0 until slots).forEach { i ->
                holder.photos.addView(picture(context, saved.getOrNull(i), comment))
            }
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
