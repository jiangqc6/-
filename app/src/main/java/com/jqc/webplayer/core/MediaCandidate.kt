package com.jqc.webplayer.core

enum class SniffSource { REQUEST, JS, PAGE_SOURCE, VIDEO_ELEMENT }

data class MediaCandidate(
    val url: String,
    val type: StreamType,
    val host: String,
    val qualityLabel: String? = null,
    val discoveredAt: Long,
    val source: SniffSource,
)
