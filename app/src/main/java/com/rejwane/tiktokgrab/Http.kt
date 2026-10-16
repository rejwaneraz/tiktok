package com.rejwane.tiktokgrab

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Plain HTTP with the session cookies — TikTok's video API answers this fine. */
object Http {

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", UA)
        .header("Cookie", Cookies.header())
        .header("Referer", "https://www.tiktok.com/")
        .header("Accept", "application/json, text/plain, */*")
        .build()

    fun get(url: String): String {
        client.newCall(request(url)).execute().use { response ->
            Cookies.update(response.headers("Set-Cookie"))
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw RuntimeException("TikTok HTTP ${response.code}")
            if (body.isBlank()) throw RuntimeException("TikTok returned nothing — login or session problem")
            if (body.trimStart().startsWith("<")) throw RuntimeException("got a page instead of JSON — session expired")
            return body
        }
    }

    /** Where a vm.tiktok / vt.tiktok short link actually lands. */
    fun finalUrl(url: String): String =
        client.newCall(request(url)).execute().use { response -> response.request.url.toString() }

    /** Headers OkHttp must own itself, or the reply arrives compressed/we send a wrong length. */
    private val hopByHop =
        setOf("host", "connection", "content-length", "transfer-encoding", "accept-encoding")

    /**
     * Replays a request the WebView itself was asked to make — same URL, same headers, so any
     * signature TikTok put in that URL stays valid for us too.
     */
    fun getBytes(url: String, headers: Map<String, String>): ByteArray? = runCatching {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            val name = key.lowercase()
            if (name !in hopByHop && name != "cookie" && value.isNotBlank()) builder.header(key, value)
        }
        // Our own jar is the one the user signed in to; the WebView's copy may not have it.
        val cookie = Cookies.header().ifBlank {
            headers.entries.firstOrNull { it.key.equals("cookie", true) }?.value.orEmpty()
        }
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()
        }
    }.getOrNull()

    /** Pulls one small binary — an author's avatar — straight onto disk. */
    fun save(url: String, dest: java.io.File): Boolean {
        val call = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(call).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            dest.parentFile?.mkdirs()
            body.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            return dest.length() > 0
        }
    }
}
