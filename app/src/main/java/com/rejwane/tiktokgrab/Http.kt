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
        // A whole request has to end sometime. Without this, a server that trickles one byte a
        // minute keeps a worker — and a listing walk — open forever.
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", UA)
        .header("Referer", "https://www.tiktok.com/")
        .header("Accept", "application/json, text/plain, */*")
        // The session belongs to TikTok alone: a short link that hops through another host must not
        // carry it there.
        .apply { if (url.contains("tiktok.com")) header("Cookie", Cookies.header()) }
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

    /** A JSON answer from a service that needs neither TikTok's session nor its page signature. */
    fun openGet(url: String): String {
        val call = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept", "application/json").build()
        client.newCall(call).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
            return response.body?.string() ?: ""
        }
    }

    /** Where a vm.tiktok / vt.tiktok short link actually lands. */
    fun finalUrl(url: String): String =
        client.newCall(request(url)).execute().use { response -> response.request.url.toString() }

    /** Pulls one small binary — an author's avatar or a comment's picture — straight onto disk. */
    fun save(url: String, dest: java.io.File): Boolean {
        val call = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://www.tiktok.com/")
            .build()
        client.newCall(call).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            // A login page or an error page is text. Saved as a .jpg it becomes a picture that never
            // goes away, because the app then finds a file where it expects one.
            val type = body.contentType()?.type
            if (type == "text") return false
            dest.parentFile?.mkdirs()
            body.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            return dest.length() > 0
        }
    }
}
