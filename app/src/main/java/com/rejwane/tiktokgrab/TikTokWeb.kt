package com.rejwane.tiktokgrab

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
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
 * plain HTTP cannot do: read a profile page once, and let the video page fetch its own comments.
 *
 * The comments are copied out of the page rather than requested by us — the reply is already signed,
 * and nothing is ever downloaded on the WebView's own thread, which is what used to kill the app.
 */
class TikTokWeb(private val activity: Activity) {

    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private val seen = LinkedHashSet<String>()

    /** True while the page is working for us, which is exactly when it must not be torn down. */
    @Volatile private var busy = false

    @SuppressLint("SetJavaScriptEnabled")
    private fun view(): WebView = web ?: WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // The desktop page is the one that asks for comments the way we can read them.
        settings.userAgentString = Http.UA
        webViewClient = object : WebViewClient() {
            // TikTok tries to jump into its own app (snssdk1233://…). Swallow it: opening the app
            // would yank the user out of ours, and the failed load would also wipe this page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url?.scheme ?: return true
                return scheme != "http" && scheme != "https"
            }

            // First JavaScript commit of the document: our hook runs before any of TikTok's own code.
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                main.post { hook() }
            }

            // Only a note is taken here — answering a request from this thread means blocking on the
            // network, and a WebView destroyed while blocked crashes the whole process in native code.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url?.toString() ?: return null
                if (url.contains("comment") && seen.size < 120) seen.add(url.take(320))
                return null
            }
        }
        // An unattached WebView may never render, so the page's own data would never appear.
        alpha = 0f
        activity.findViewById<ViewGroup>(android.R.id.content).addView(this, ViewGroup.LayoutParams(2, 2))
        web = this
    }

    /**
     * Wraps the page's own fetch and XHR so every comment reply is left where we can read it. It is
     * posted to the main thread and safe to repeat: the second run does nothing.
     */
    private fun hook() {
        main.post { runCatching { web?.evaluateJavascript(HOOK, null) } }
    }

    /**
     * Opens one video page and waits while it loads its own comments. Returns how many were kept.
     * The page is never answered by us, so this only costs the load itself.
     */
    suspend fun captureComments(pageUrl: String, clipId: String, want: Int = Comments.LIMIT): Int {
        busy = true
        return try {
            gather(pageUrl, clipId, want)
        } finally {
            busy = false
        }
    }

    private suspend fun gather(pageUrl: String, clipId: String, want: Int): Int {
        var kept = 0
        withContext(Dispatchers.Main) { view().loadUrl(pageUrl) }
        hook()
        delay(3000)
        val deadline = System.currentTimeMillis() + CAPTURE_SECONDS * 1000L
        var still = 0
        var last = -1
        eval(OPEN_COMMENTS)
        while (System.currentTimeMillis() < deadline) {
            hook()
            val drained = drain(clipId)
            if (drained >= 0) kept = drained
            if (kept >= want) break
            if (kept == last) still++ else { still = 0; last = kept }
            when {
                still == 1 -> eval(OPEN_COMMENTS)
                still in 2..8 -> eval(SCROLL_COMMENTS)
                else -> break
            }
            delay(1500)
        }
        val left = drain(clipId)
        if (left >= 0) kept = left
        // Nothing signed ever arrived, so fall back to what the page drew on screen.
        if (kept == 0) kept = scrape(clipId)
        val log = seen.joinToString("\n")
        withContext(Dispatchers.IO) { Store.writeText(activity, LOGS, "weblog.txt", log) }
        return kept
    }

    /** Takes the replies the page has fetched since the last look and keeps them as our own file. */
    private suspend fun drain(clipId: String): Int {
        val raw = eval("JSON.stringify((window.__c||[]).splice(0,2))")
        if (raw.isBlank()) return -1
        val batch = runCatching { JSONArray(raw) }.getOrNull() ?: return -1
        if (batch.length() == 0) return -1
        var total = -1
        withContext(Dispatchers.IO) {
            total = Comments.count(activity, clipId)
            for (i in 0 until batch.length()) {
                val body = batch.optString(i)
                if (body.length > 40) total = Comments.absorb(activity, clipId, body)
            }
        }
        return total
    }

    /** Last resort: the comments the page drew but never asked for over an API we could see. */
    private suspend fun scrape(clipId: String): Int {
        val raw = eval(SCRAPE)
        if (raw.isBlank() || raw.length < 20) return 0
        var total = 0
        withContext(Dispatchers.IO) { total = Comments.absorbDom(activity, clipId, raw) }
        return total
    }

    fun url(): String = web?.url ?: ""

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        // Never rebuild a WebView the screen has already thrown away — the old one is not ours again.
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
        web = null
        (current.parent as? ViewGroup)?.removeView(current)
        runCatching { current.stopLoading() }
        // A WebView torn down while it is still fetching dies in native code and takes the app with
        // it, so a busy one is only unhooked here and left to the system.
        if (!busy) runCatching { current.destroy() }
    }

    private companion object {
        const val LOGS = "logs"
        const val CAPTURE_SECONDS = 45L

        val HOOK = """(function(){
          if(window.__c)return 1;window.__c=[];
          var of=window.fetch;
          if(of)window.fetch=function(){var a=arguments;
            return of.apply(this,a).then(function(r){try{
              var u=(a[0]&&a[0].url)||String(a[0]);
              if(u.indexOf('comment/list')>=0&&window.__c.length<60)r.clone().text().then(function(b){
                if(b&&b.length>40)window.__c.push(b)});
            }catch(e){}return r;});};
          var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open=function(m,u){this.__u=u;return oo.apply(this,arguments);};
          XMLHttpRequest.prototype.send=function(){var x=this;
            x.addEventListener('load',function(){try{
              if(String(x.__u||'').indexOf('comment/list')>=0&&window.__c.length<60&&x.responseText)
                window.__c.push(x.responseText);
            }catch(e){}});
            return os.apply(this,arguments);};
          return 1;})()""".trimIndent()

        val OPEN_COMMENTS = """(function(){
          var s=['[data-e2e="comment-icon"]','[data-e2e="comments-comment-textarea"]',
                 '[data-e2e="comment-button"]','[data-e2e="comment-list"]'];
          for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e){e.click();return 'ok';}}
          return '';})()""".trimIndent()

        val SCROLL_COMMENTS = """(function(){
          var e=document.querySelector('[data-e2e="comment-list"]');
          if(!e){var all=document.querySelectorAll('div');
            for(var i=0;i<all.length;i++){
              if(all[i].scrollHeight>all[i].clientHeight+200&&all[i].clientHeight>200){e=all[i];break;}}}
          if(!e){window.scrollBy(0,700);return 'win';}
          e.scrollTop=e.scrollTop+e.clientHeight;return 'ok';})()""".trimIndent()

        val SCRAPE = """(function(){
          function txt(e){return e?(e.textContent||'').trim():'';}
          var out=[],seen={};
          var items=document.querySelectorAll('[data-e2e="comment-item"],[data-e2e="comment-card"]');
          for(var i=0;i<items.length&&out.length<60;i++){
            var it=items[i];
            var text=txt(it.querySelector('[data-e2e="comment-content"],[data-e2e="comment-desc"],[data-e2e="comment-text"]'));
            if(!text)continue;
            var name=txt(it.querySelector('[data-e2e="comment-user-name"],[data-e2e="comment-author"]'))||
                     txt(it.querySelector('a[href^="/@"]'));
            var likes=txt(it.querySelector('[data-e2e="comment-like-count"],[data-e2e="comment-likes"]'));
            var whenEl=it.querySelector('[datetime]');
            var imgs=it.getElementsByTagName('img'),pic='';
            for(var j=0;j<imgs.length;j++){var s=imgs[j].currentSrc||imgs[j].src||'';
              if(s.indexOf('data:')!==0&&s.length>40){pic=s;break;}}
            if(seen[name+text])continue;seen[name+text]=1;
            out.push({n:name,t:text.slice(0,600),l:likes,w:whenEl?whenEl.getAttribute('datetime'):'',p:pic});
          }
          return JSON.stringify(out);})()""".trimIndent()
    }
}
