package com.rejwane.tiktokgrab

import android.content.Context

/**
 * A cookie only proves it was once saved. When it stops working the app looked just as "signed in"
 * as before and blamed the internet, so what is kept here is what TikTok's own listing did last.
 */
object Session {

    private const val PREF = "session"

    fun good(context: Context) = write(context, true, "")

    fun bad(context: Context, reason: String) = write(context, false, reason.take(120))

    private fun write(context: Context, ok: Boolean, reason: String) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("ok", ok).putLong("at", System.currentTimeMillis()).putString("why", reason).apply()

    /** One honest line for the Settings row. */
    fun label(context: Context): String {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!Cookies.hasLogin()) return "Not signed in"
        val at = prefs.getLong("at", 0L)
        if (at == 0L) return "Not checked yet"
        val ago = GridAdapter.ago(at / 1000)
        if (prefs.getBoolean("ok", false)) return "OK · $ago"
        val why = prefs.getString("why", "").orEmpty()
        return when {
            why.contains("session") || why.contains("login") || why.contains("HTTP 4") -> "Expired · log in again"
            why.contains("internet") || why.contains("resolve") || why.contains("Failed to connect") ->
                "No signal · $ago"
            why.isNotBlank() -> "Last read failed · $ago"
            else -> "Unknown · $ago"
        }
    }

    fun why(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString("why", "").orEmpty()
}
