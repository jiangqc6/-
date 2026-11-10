package com.jqc.webplayer.core

import java.net.URI
import java.net.URISyntaxException

object UrlNormalizer {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096) return null
        val withScheme = if (schemeRegex.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        val lower = withScheme.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return try {
            val uri = URI(withScheme)
            if (uri.host.isNullOrBlank()) null else withScheme
        } catch (e: URISyntaxException) {
            null
        }
    }
}
