package com.rejwane.tiktokgrab

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The download queue owns its own scope, so leaving the screen — or the app — does not cancel a
 * batch. A screen only listens; it may come and go while the work keeps going.
 *
 * This is what "pasted a link, pressed download, went to another app" needs: the loop is not tied to
 * an activity's lifecycle any more.
 */
object Downloads {

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        val percent: Int = 0,
        val failed: Int = 0,
        val savedId: String = "",
        val lastSaved: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var job: Job? = null
    @Volatile var state = State()
        private set

    /** Clips that failed in the last batch, so the Retry button has something to retry. */
    @Volatile var failedClips: List<Clip> = emptyList()
        private set

    /** Set by whichever screen is on top; null while none is. */
    @Volatile private var listener: ((State) -> Unit)? = null

    /** The notification is redrawn at most every 10%, not on every progress tick. */
    private var lastNote = -1

    /** Kept so the progress notification can be updated without a screen. */
    @Volatile private var app: Context? = null

    fun isRunning() = job?.isActive == true

    /** Attach or detach the visible screen. Immediately reports the current state when attaching. */
    fun watch(next: ((State) -> Unit)?) {
        listener = next
        next?.let { fn -> main.post { fn(state) } }
    }

    private fun publish(next: State) {
        state = next
        val bucket = next.done * 100 + next.percent / 10
        if (bucket != lastNote) {
            lastNote = bucket
            app?.let { ctx -> DownloadService.progress(ctx, next.done, next.total) }
        }
        val fn = listener ?: return
        main.post { fn(next) }
    }

    fun stop() {
        if (job?.isActive != true) return
        job?.cancel()
        job = null
        publish(state.copy(running = false, current = "", percent = 0))
    }

    fun start(context: Context, clips: List<Clip>) {
        if (clips.isEmpty() || isRunning()) return
        val application = context.applicationContext
        app = application
        failedClips = emptyList()
        lastNote = -1
        DownloadService.start(application, clips.size)
        publish(State(running = true, total = clips.size))
        job = scope.launch {
            var done = 0
            var failed = 0
            var savedId = ""
            var lastSaved = ""
            val broken = ArrayList<Clip>()
            try {
                for (clip in clips) {
                    val title = clip.title.ifBlank { clip.id }
                    publish(state.copy(current = title, percent = 0))
                    try {
                        val result = Downloader.save(application, clip) { percent ->
                            publish(state.copy(current = title, percent = percent.toInt()))
                        }
                        if (!result.skipped) {
                            savedId = result.clipId
                            lastSaved = result.path
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        broken.add(clip)
                        failedClips = broken.toList()
                    }
                    done++
                    publish(state.copy(done = done, failed = failed, savedId = savedId, lastSaved = lastSaved, percent = 0))
                }
                publish(State(running = false, done = done, total = clips.size, failed = failed,
                    savedId = savedId, lastSaved = lastSaved))
            } finally {
                DownloadService.stop(application)
            }
        }
    }
}
