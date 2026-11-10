package com.jqc.webplayer.core

import java.net.URI

data class HlsVariant(
    val url: String,
    val width: Int?,
    val height: Int?,
    val bandwidth: Long,
)

object HlsManifestParser {
    private val resolutionRegex = Regex("RESOLUTION=(\\d+)x(\\d+)")
    private val bandwidthRegex = Regex("BANDWIDTH=(\\d+)")

    fun isMaster(text: String): Boolean =
        text.lineSequence().any { it.trim().startsWith("#EXT-X-STREAM-INF") }

    fun parseMaster(text: String, baseUrl: String): List<HlsVariant> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val result = mutableListOf<HlsVariant>()
        var width: Int? = null
        var height: Int? = null
        var bandwidth = 0L
        var awaitingUri = false

        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = line.substringAfter(':', "")
                val resolution = resolutionRegex.find(attrs)
                width = resolution?.groupValues?.get(1)?.toIntOrNull()
                height = resolution?.groupValues?.get(2)?.toIntOrNull()
                bandwidth = bandwidthRegex.find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                awaitingUri = true
            } else if (!line.startsWith("#") && awaitingUri) {
                val resolved = resolve(baseUrl, line)
                if (resolved != null) {
                    result += HlsVariant(resolved, width, height, bandwidth)
                }
                awaitingUri = false
            }
        }
        return result
    }

    fun qualityLabel(variants: List<HlsVariant>): String? =
        variants.maxByOrNull { it.height ?: 0 }?.height?.let { "${it}P" }

    private fun resolve(baseUrl: String, reference: String): String? = try {
        URI(baseUrl).resolve(reference).toString()
    } catch (e: Exception) {
        null
    }
}
