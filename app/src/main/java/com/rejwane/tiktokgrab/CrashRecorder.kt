package com.rejwane.tiktokgrab

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A WebView mistake often dies in native code and leaves nothing on screen but "app stopped". The
 * last stack trace is written next to the other records in Download, so the reason can be read on
 * the phone itself instead of guessed at.
 */
object CrashRecorder {

    private const val FOLDER = "logs"
    private const val FILE = "crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Store.writeText(context.applicationContext, FOLDER, FILE, dump(thread.name, error))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash, or empty when the app has not fallen over since it was installed. */
    fun text(context: Context): String = Store.readText(context, FOLDER, FILE).orEmpty()

    fun forget(context: Context) {
        Store.writeText(context, FOLDER, FILE, "")
    }

    private fun dump(thread: String, error: Throwable): String {
        val trace = StringWriter()
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 5) {
            trace.append(if (depth == 0) "" else "Caused by: ")
            cause.printStackTrace(PrintWriter(trace))
            cause = cause.cause
            depth++
        }
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
            "\nthread: " + thread +
            "\nandroid: " + Build.VERSION.RELEASE + " api " + Build.VERSION.SDK_INT +
            "\n" + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + trace
    }
}
