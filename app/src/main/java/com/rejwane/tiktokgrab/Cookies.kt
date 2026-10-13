package com.rejwane.tiktokgrab

import android.webkit.CookieManager

/** TikTok session cookies live in the WebView; we only need them as a header string. */
object Cookies {

    private const val ORIGIN = "https://www.tiktok.com"

    fun enable() = CookieManager.getInstance().setAcceptCookie(true)

    fun header(): String = CookieManager.getInstance().getCookie(ORIGIN) ?: ""

    fun hasLogin(): Boolean = header().split(";").any { it.trim().startsWith("sessionid") }

    fun flush() = CookieManager.getInstance().flush()

    /**
     * TikTok hands back a fresh msToken on every response. Only that one is stored — replacing
     * the other session cookies breaks the login.
     */
    fun update(setCookies: List<String>) {
        val manager = CookieManager.getInstance()
        var changed = false
        for (raw in setCookies) {
            val pair = raw.substringBefore(';').trim()
            if (pair.startsWith("msToken=") && pair.length > 8) {
                manager.setCookie(ORIGIN, pair)
                changed = true
            }
        }
        if (changed) manager.flush()
    }
}
