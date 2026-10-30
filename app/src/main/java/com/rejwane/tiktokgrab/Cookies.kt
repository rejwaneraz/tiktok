package com.rejwane.tiktokgrab

import android.webkit.CookieManager

/** TikTok session cookies live in the WebView; we only need them as a header string. */
object Cookies {

    private const val ORIGIN = "https://www.tiktok.com"

    fun enable() = CookieManager.getInstance().setAcceptCookie(true)

    fun header(): String = CookieManager.getInstance().getCookie(ORIGIN) ?: ""

    fun hasLogin(): Boolean = header().split(";").any { it.trim().startsWith("sessionid") }

    fun flush() = CookieManager.getInstance().flush()

    /** Forgets the whole TikTok session. */
    fun signOut() = CookieManager.getInstance().run {
        removeAllCookies(null)
        flush()
    }

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

    // region Instagram

    /**
     * Instagram lives in the same cookie jar as TikTok, and the two are never mixed: the media reader
     * is anonymous on purpose (a session attached to the crawler read makes Instagram take the media
     * payload back off the page), and the only thing the Instagram session is good for is the browser
     * path — the profile's list of posts, which stops at twelve without it.
     */
    const val ORIGIN_INSTAGRAM = "https://www.instagram.com"

    fun hasInstagramLogin(): Boolean =
        (CookieManager.getInstance().getCookie(ORIGIN_INSTAGRAM) ?: "")
            .split(";").any { it.trim().startsWith("sessionid") }

    /** TikTok's own sign-out clears everything; this one must not take the TikTok login with it. */
    fun signOutInstagram() {
        val manager = CookieManager.getInstance()
        val dead = "expires=Thu, 01 Jan 1970 00:00:00 GMT"
        listOf("sessionid", "sessionid_ss", "sidcc", "ds_user_id", "csrftoken", "mid", "datr",
            "ig_did", "ig_nrcb", "rur", "wd", "dpr", "ps_l", "ps_n").forEach { name ->
            // Both shapes: the login page's cookies are set for the whole .instagram.com domain, while
            // ones imported from a PC export land as host-only on www.instagram.com — clearing only the
            // domain form would leave an imported session alive behind a "logged out" label.
            manager.setCookie(ORIGIN_INSTAGRAM, "$name=; $dead; path=/; domain=.instagram.com")
            manager.setCookie(ORIGIN_INSTAGRAM, "$name=; $dead; path=/")
        }
        manager.flush()
    }
    // endregion
}
