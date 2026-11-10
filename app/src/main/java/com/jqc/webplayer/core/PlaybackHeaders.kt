package com.jqc.webplayer.core

import java.net.URI

object PlaybackHeaders {
    fun build(
        pageUrl: String,
        userAgent: String,
        videoCookies: String?,
        pageCookies: String?,
    ): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        headers["User-Agent"] = userAgent
        headers["Accept"] = "*/*"
        if (pageUrl.isNotBlank()) headers["Referer"] = pageUrl
        originOf(pageUrl)?.let { headers["Origin"] = it }

        val cookies = (listOfNotNull(videoCookies, pageCookies))
            .flatMap { it.split(";") }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .distinct()
        if (cookies.isNotEmpty()) headers["Cookie"] = cookies.joinToString("; ")

        return headers
    }

    fun originOf(pageUrl: String): String? = try {
        val uri = URI(pageUrl)
        val host = uri.host
        if (host.isNullOrBlank()) null else buildString {
            append(uri.scheme)
            append("://")
            append(host)
            if (uri.port > 0) append(":").append(uri.port)
        }
    } catch (e: Exception) {
        null
    }
}
