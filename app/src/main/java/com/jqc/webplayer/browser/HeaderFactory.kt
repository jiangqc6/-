package com.jqc.webplayer.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.jqc.webplayer.core.PlaybackHeaders

object HeaderFactory {
    fun build(context: Context, pageUrl: String, videoUrl: String, userAgent: String? = null): Map<String, String> {
        val ua = userAgent ?: WebSettings.getDefaultUserAgent(context).replace("; wv", "")
        val cookieManager = CookieManager.getInstance()
        val videoCookies = runCatching { cookieManager.getCookie(videoUrl) }.getOrNull()
        val pageCookies = runCatching { cookieManager.getCookie(pageUrl) }.getOrNull()
        return PlaybackHeaders.build(pageUrl, ua, videoCookies, pageCookies)
    }
}
