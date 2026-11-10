package com.jqc.webplayer.core

import java.net.URI

object MediaUrlClassifier {
    private val blockedSuffixes = listOf(
        ".ts", ".m4s", ".aac", ".mp3", ".vtt", ".key",
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".css", ".js", ".json",
    )
    private val blockedFragments = listOf(
        "/ad/", "/ads/", "advert", "doubleclick", "googlesyndication", "/beacon", "analytics",
    )
    private val blockedHostPrefixes = listOf(
        "ads.", "ad.", "adserver.", "analytics.", "track.",
    )
    private val playHints = listOf("play", "stream", "video", "media", "hls")

    fun detectType(rawUrl: String): StreamType? {
        val url = rawUrl.trim()
        if (url.length > 4096) return null
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") -> StreamType.HLS
            path.endsWith(".mp4") -> StreamType.MP4
            path.endsWith(".webm") -> StreamType.WEBM
            else -> null
        }
    }

    fun classify(
        rawUrl: String,
        pageUrl: String?,
        source: SniffSource,
        now: Long = System.currentTimeMillis(),
    ): MediaCandidate? {
        val url = rawUrl.trim()
        val type = detectType(url) ?: return null
        val lower = url.lowercase()
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (blockedSuffixes.any { path.endsWith(it) }) return null
        if (blockedFragments.any { lower.contains(it) }) return null
        val host = hostOf(url) ?: return null
        if (blockedHostPrefixes.any { host.startsWith(it, ignoreCase = true) }) return null
        return MediaCandidate(
            url = url,
            type = type,
            host = host,
            qualityLabel = null,
            discoveredAt = now,
            source = source,
        )
    }

    fun rank(candidates: List<MediaCandidate>, pageHost: String?): List<MediaCandidate> {
        val deduped = LinkedHashMap<String, MediaCandidate>()
        candidates.forEach { deduped[it.url] = it }
        return deduped.values.sortedWith(
            compareByDescending<MediaCandidate> { score(it, pageHost) }
                .thenByDescending { it.discoveredAt }
        )
    }

    fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (e: Exception) {
        null
    }

    private fun score(candidate: MediaCandidate, pageHost: String?): Int {
        var score = when (candidate.type) {
            StreamType.HLS -> 40
            StreamType.MP4 -> 30
            StreamType.WEBM -> 25
        }
        if (pageHost != null && candidate.host.equals(pageHost, ignoreCase = true)) score += 3
        val path = candidate.url.substringBefore('?').lowercase()
        if (playHints.any { path.contains(it) }) score += 2
        return score
    }
}
