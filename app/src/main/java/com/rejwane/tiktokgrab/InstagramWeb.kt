package com.rejwane.tiktokgrab

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * The one thing a plain HTTP request cannot do for Instagram: name the posts on a profile.
 *
 * Measured 2026-10-30 — a profile read over HTTP as a crawler carries 41 media records but no post
 * links and no video addresses, while the same profile in a real browser holds the links, and a
 * logged-in browser keeps loading more as it scrolls (12 → 42 → 138 in a minute). So this reads the
 * links out of a WebView and hands them to [InstagramApi], which asks for each post anonymously.
 *
 * It is invisible: a 2 x 2 view at alpha 0, exactly like the TikTok reader. No desktop user agent —
 * an Android WebView claiming to be Windows Chrome fails Instagram's own browser check, and nothing
 * loads at all.
 */
class InstagramWeb(private val activity: Activity) {

    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var busy = false
    @Volatile private var gone = false

    /** Scroll, collect, report. Runs in the page so the DOM does the work, not the network. */
    private val scanJs = """
        (function() {
          if (!window.__igSeen) { window.__igSeen = {}; }
          var links = document.querySelectorAll('a[href]');
          for (var i = 0; i < links.length; i++) {
            var h = links[i].getAttribute('href') || '';
            var m = h.match(new RegExp('/(p|reel)/([A-Za-z0-9_-]{11})'));
            if (m) { window.__igSeen[m[2]] = h.indexOf('/reel/') >= 0 ? 'r' : 'p'; }
          }
          window.scrollTo(0, document.body.scrollHeight);
          return JSON.stringify(window.__igSeen);
        })()
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private fun view(): WebView {
        if (gone) throw kotlinx.coroutines.CancellationException("this screen was closed")
        web?.let { return it }
        val built = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                // Instagram tries to open its own app; swallow anything that is not a web address.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url?.scheme ?: return true
                    return scheme != "http" && scheme != "https"
                }
            }
            alpha = 0f
            // A window detached between the check and here has no content view to hold this.
            val holder = runCatching {
                activity.findViewById<ViewGroup>(android.R.id.content)
            }.getOrNull()
            runCatching { holder?.addView(this, ViewGroup.LayoutParams(2, 2)) }
        }
        web = built
        return built
    }

    /**
     * Post links on one profile, as pairs of (code, "p" or "r"). The page hands them in whatever order
     * its grid happens to hold, which for a profile is newest first until Instagram decides otherwise.
     *
     * Instagram rebuilds its grid as you scroll and drops the tiles that leave the screen, so the set
     * is kept in the page itself and read back after each scroll. The walk stops on its own once the
     * page stops producing — an account that is not answering costs one page load, not a minute.
     */
    suspend fun profileCodes(user: String, want: Int = 60): List<Pair<String, String>> {
        if (user.isBlank()) return emptyList()
        busy = true
        return try {
            withContext(Dispatchers.Main) {
                view().loadUrl("https://www.instagram.com/$user/")
            }
            val deadline = System.currentTimeMillis() + 70_000
            var last = 0
            var stalled = 0
            var latest: List<Pair<String, String>> = emptyList()
            while (System.currentTimeMillis() < deadline && !gone) {
                delay(1300)
                val read = runCatching { JSONObject(decode(eval(scanJs))) }.getOrNull()
                val codes = read?.let { table ->
                    table.keys().asSequence().map { it to table.optString(it, "p") }.toList()
                }.orEmpty()
                if (codes.isNotEmpty()) latest = codes
                if (latest.size >= want) break
                if (latest.size == last) stalled++ else stalled = 0
                last = latest.size
                // Nothing new for three rounds: the grid stopped loading (a logged-out browser is held
                // at twelve posts). An empty page is given six rounds to appear at all, and then it is
                // called what it is — a profile this reader cannot list, not a walk to keep paying for.
                if (stalled >= (if (last > 0) 3 else 6)) break
            }
            latest
        } finally {
            busy = false
        }
    }

    private fun decode(raw: String): String = when {
        raw.isBlank() || raw == "null" -> "{}"
        else -> runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault(raw)
    }

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        val target = web ?: return@withContext ""
        suspendCancellableCoroutine { cont ->
            target.evaluateJavascript(js) { value ->
                val text = when {
                    value == null || value == "null" -> ""
                    else -> runCatching { JSONArray("[$value]").getString(0) }.getOrDefault(value)
                }
                if (cont.isActive) cont.resume(text)
            }
            main.postDelayed({ if (cont.isActive) cont.resume("") }, 15000)
        }
    }

    fun destroy() {
        gone = true
        val current = web ?: return
        web = null
        (current.parent as? ViewGroup)?.removeView(current)
        runCatching { current.stopLoading() }
        if (busy) main.postDelayed({ runCatching { current.destroy() } }, 800)
        else runCatching { current.destroy() }
    }
}
