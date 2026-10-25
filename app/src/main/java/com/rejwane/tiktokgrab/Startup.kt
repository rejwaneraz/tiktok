package com.rejwane.tiktokgrab

import android.os.SystemClock

/**
 * The few numbers that say whether the app actually starts faster. There is no logcat on the phone,
 * so Settings shows these instead of asking for a cable.
 */
object Startup {

    /** Set on the first line of Application.onCreate. */
    var beganAt = 0L

    /** How long the last library scan took, on whichever screen ran it. */
    var scanMs = 0L

    /** Application.onCreate → first frame of the main screen. */
    var firstFrameMs = 0L

    fun now() = SystemClock.elapsedRealtime()
}
