package com.jqc.webplayer.core

import java.net.URI

/**
 * 页面身份键：用于历史去重与观看进度匹配。
 *
 * 只去掉已知的跟踪参数（utm_*、spm、fbclid 等），保留 id、ep、page 这类
 * 真正决定内容的参数，以及 #/episode 这类前端路由。剩余参数排序，保证同一页
 * 不同顺序/不同分享渠道得到同一个键。
 */
object PageIdentity {
    private val trackingKeys = setOf(
        "spm", "scm", "fbclid", "gclid", "msclkid", "yclid", "_ga", "mc_cid", "mc_eid",
    )

    fun key(rawUrl: String): String? {
        val normalized = UrlNormalizer.normalize(rawUrl) ?: return null
        val uri = try {
            URI(normalized)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
        val port = if (uri.port > 0 && !defaultPort) ":${uri.port}" else ""
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"

        val keptParams = (uri.rawQuery ?: "")
            .split("&")
            .filter { it.isNotBlank() }
            .filterNot { isTracking(it.substringBefore("=")) }
            .sorted()
        val query = if (keptParams.isEmpty()) "" else keptParams.joinToString("&", prefix = "?")
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""

        return buildString {
            append(scheme).append("://").append(host).append(port).append(path)
            append(query)
            append(fragment)
        }
    }

    private fun isTracking(rawKey: String): Boolean {
        val key = rawKey.lowercase()
        return key.startsWith("utm_") || key in trackingKeys
    }
}
