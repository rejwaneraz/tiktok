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
import kotlin.coroutines.resume

/**
 * Only used to read the profile page once per account — TikTok's bot check lets the real browser
 * through, while the video API itself works over plain HTTP (see Http.kt). Comments never came this
 * way, so they no longer do: see Comments.fetch.
 */
class TikTokWeb(private val activity: Activity) {

    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    /** True while a page is loading, which is exactly when the WebView must not be torn down. */
    @Volatile private var busy = false

    @SuppressLint("SetJavaScriptEnabled")
    private fun view(): WebView = web ?: WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = Http.UA
        webViewClient = object : WebViewClient() {
            // TikTok tries to jump into its own app (snssdk1233://…). Swallow it: opening the app
            // would yank the user out of ours, and the failed load would also wipe this page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url?.scheme ?: return true
                return scheme != "http" && scheme != "https"
            }
        }
        // An unattached WebView may never render, so the page's own data would never appear.
        alpha = 0f
        activity.findViewById<ViewGroup>(android.R.id.content).addView(this, ViewGroup.LayoutParams(2, 2))
        web = this
    }

    fun url(): String = web?.url ?: ""

    suspend fun openProfile(user: String) {
        busy = true
        try {
            withContext(Dispatchers.Main) { view().loadUrl("https://www.tiktok.com/@$user") }
            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline) {
                // The previous profile stays in the DOM while a load fails, so never trust stale data.
                if (url().contains("/@$user", true) && pageData().isNotBlank()) return
                delay(800)
            }
            throw RuntimeException("Could not open TikTok page (URL: ${url()}) — check internet/VPN")
        } finally {
            busy = false
        }
    }

    suspend fun pageData(): String =
        eval("(document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__')||{}).textContent||''")

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        // Never rebuild a WebView the screen has already thrown away.
        val target = web ?: return@withContext ""
        suspendCancellableCoroutine { cont ->
            target.evaluateJavascript(js) { value ->
                val decoded = when {
                    value == null || value == "null" -> ""
                    else -> runCatching { JSONArray("[$value]").getString(0) }.getOrDefault(value)
                }
                if (cont.isActive) cont.resume(decoded)
            }
            main.postDelayed({ if (cont.isActive) cont.resume("") }, 15000)
        }
    }

    fun destroy() {
        val current = web ?: return
        web = null
        (current.parent as? ViewGroup)?.removeView(current)
        runCatching { current.stopLoading() }
        // A WebView torn down while it is still loading dies in native code and takes the app with
        // it, so a busy one is only unhooked here and left to the system.
        if (!busy) runCatching { current.destroy() }
    }
}
