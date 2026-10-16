package com.rejwane.tiktokgrab

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * TikTok's bot check lets a real browser through, so this hidden WebView is used for the two things
 * plain HTTP cannot do: read a profile page once, and let the page fetch its own signed comments.
 */
class TikTokWeb(private val activity: Activity) {

    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var watching: String? = null
    @Volatile private var captured = 0

    @SuppressLint("SetJavaScriptEnabled")
    private fun view(): WebView = web ?: WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = object : WebViewClient() {
            // TikTok tries to jump into its own app (snssdk1233://…). Swallow it: opening the app
            // would yank the user out of ours, and the failed load would also wipe this page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url?.scheme ?: return true
                return scheme != "http" && scheme != "https"
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                intercept(request)
        }
        // An unattached WebView may never render, so the page's own data would never appear.
        alpha = 0f
        activity.findViewById<ViewGroup>(android.R.id.content).addView(this, ViewGroup.LayoutParams(2, 2))
        web = this
    }

    /**
     * The page's own comment request already carries TikTok's signature, so it is replayed with the
     * very same headers and the answer is copied before it goes back to the page.
     */
    private fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        if (!url.contains("api/comment/list")) return null
        val clip = watching ?: Comments.clipIdIn(url)
        if (clip.isBlank()) return null
        val bytes = Http.getBytes(url, request.requestHeaders.orEmpty()) ?: return emptyResponse()
        captured = Comments.absorb(activity, clip, String(bytes, Charsets.UTF_8))
        return WebResourceResponse("application/json", "utf-8", bytes.inputStream())
    }

    private fun emptyResponse() =
        WebResourceResponse("application/json", "utf-8", ByteArray(0).inputStream())

    /** Opens one video page and waits while it loads its own comments. Returns how many were kept. */
    suspend fun captureComments(pageUrl: String, clipId: String, want: Int = Comments.LIMIT): Int {
        watching = clipId
        captured = 0
        withContext(Dispatchers.Main) { view().loadUrl(pageUrl) }
        val deadline = System.currentTimeMillis() + 50000
        var nudged = false
        var last = 0
        var still = 0
        while (System.currentTimeMillis() < deadline) {
            delay(1500)
            if (captured >= want) break
            if (!nudged && captured == 0 && System.currentTimeMillis() > deadline - 41000) {
                nudged = true
                eval(OPEN_COMMENTS)
            }
            if (captured == last) still++ else { still = 0; last = captured }
            if (still > 0 && still % 3 == 0) eval(SCROLL_COMMENTS)
            if (still > 12) break
        }
        watching = null
        return captured
    }

    fun url(): String = web?.url ?: ""

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            view().evaluateJavascript(js) { value ->
                val decoded = when {
                    value == null || value == "null" -> ""
                    else -> runCatching { JSONArray("[$value]").getString(0) }.getOrDefault(value)
                }
                if (cont.isActive) cont.resume(decoded)
            }
            main.postDelayed({ if (cont.isActive) cont.resume("") }, 15000)
        }
    }

    suspend fun openProfile(user: String) {
        withContext(Dispatchers.Main) { view().loadUrl("https://www.tiktok.com/@$user") }
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            // The previous profile stays in the DOM while a load fails, so never trust stale data.
            if (url().contains("/@$user", true) && pageData().isNotBlank()) return
            delay(800)
        }
        throw RuntimeException("Could not open TikTok page (URL: ${url()}) — check internet/VPN")
    }

    suspend fun pageData(): String =
        eval("(document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__')||{}).textContent||''")

    fun destroy() {
        val current = web ?: return
        (current.parent as? ViewGroup)?.removeView(current)
        current.destroy()
        web = null
    }

    private companion object {
        val OPEN_COMMENTS = """(function(){
            var s=['[data-e2e="comment-icon"]','[data-e2e="comments-comment-textarea"]',
                   '[data-e2e="comment-button"]','[data-e2e="comment-list"]'];
            for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e){e.click();return 'ok'}}
            return ''})()""".trimIndent()

        val SCROLL_COMMENTS = """(function(){
            var e=document.querySelector('[data-e2e="comment-list"]');
            if(!e){var all=document.querySelectorAll('div');
              for(var i=0;i<all.length;i++){
                if(all[i].scrollHeight>all[i].clientHeight+200&&all[i].clientHeight>200){e=all[i];break}}}
            if(!e)return '';e.scrollTop=e.scrollHeight;return 'ok'})()""".trimIndent()
    }
}
