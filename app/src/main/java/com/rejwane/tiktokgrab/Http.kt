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
}
