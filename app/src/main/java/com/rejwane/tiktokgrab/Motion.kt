package com.rejwane.tiktokgrab

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView

/**
 * Everything that moves in this app goes through here, for one reason: the phone has to be able to
 * say "stop moving". Animation scale 0 is that request, and a hand-drawn animation that ignores it
 * is a bug the user cannot work around.
 *
 * The timings and curves live in res/values/motion.xml and res/interpolator, so a change is a file
 * edit and not a hunt through the screens.
 */
object Motion {

    /** -1 means not read yet; the setting is only looked up once per process. */
    private var allowed = -1

    fun on(context: Context): Boolean {
        if (allowed < 0) {
            val scale = runCatching {
                Settings.Global.getFloat(
                    context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
                )
            }.getOrDefault(1f)
            allowed = if (scale > 0f) 1 else 0
        }
        return allowed == 1
    }

    /** A duration from motion.xml, or none at all when the phone asked for no motion. */
    fun ms(context: Context, key: Int): Long =
        if (on(context)) context.resources.getInteger(key).toLong() else 0L

    /**
     * A pane coming into view: it rises a short way and fades, with the overshoot that makes it look
     * poured rather than pasted.
     */
    fun enter(view: View) {
        val context = view.context
        if (!on(context)) {
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.alpha = 0f
        view.translationY = dp(view, 14f)
        view.scaleX = 0.97f
        view.scaleY = 0.97f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ms(context, R.integer.dur_slow))
            .setInterpolator(overshoot(context))
            .start()
    }

    /** A small thing being switched on: it grows past its size once and settles. */
    fun pop(view: View) {
        val context = view.context
        if (!on(context)) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.scaleX = 0.72f
        view.scaleY = 0.72f
        view.animate().scaleX(1f).scaleY(1f)
            .setDuration(ms(context, R.integer.dur_spring))
            .setInterpolator(overshoot(context))
            .start()
    }

    /** Sliding a marker from one place to another, which is how the tab bar shows what is open. */
    fun slide(view: View, toX: Float) {
        val context = view.context
        if (!on(context)) {
            view.translationX = toX
            return
        }
        view.animate().translationX(toX)
            .setDuration(ms(context, R.integer.dur_spring))
            .setInterpolator(overshoot(context))
            .start()
    }

    /**
     * The cover frame that stands in for a video whose first frame has not been decoded yet. It has
     * to leave quietly, or the change from picture to film reads as a jump.
     */
    fun dissolve(view: View) {
        val context = view.context
        if (!on(context) || view.alpha < 1f) {
            view.visibility = View.GONE
            return
        }
        view.animate().alpha(0f).setDuration(ms(context, R.integer.dur_crossfade))
            .withEndAction { view.visibility = View.GONE }.start()
    }

    /**
     * A list that has just been filled: the cells come in one after another instead of all in the
     * same frame. RecyclerView replays this on every bulk change, so a re-sort moves too.
     */
    fun replay(list: RecyclerView) {
        if (on(list.context)) list.scheduleLayoutAnimation()
    }

    private fun overshoot(context: Context) =
        AnimationUtils.loadInterpolator(context, R.interpolator.grab_overshoot)

    private fun dp(view: View, value: Float) = value * view.resources.displayMetrics.density
}
