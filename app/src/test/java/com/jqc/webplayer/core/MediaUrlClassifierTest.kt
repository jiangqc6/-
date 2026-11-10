package com.jqc.webplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUrlClassifierTest {
    private val page = "https://m.example.com/play/1"

    @Test
    fun classifiesHlsMp4AndWebm() {
        assertEquals(StreamType.HLS, MediaUrlClassifier.classify("https://cdn.example.com/a/index.m3u8", page, SniffSource.REQUEST, 1L)?.type)
        assertEquals(StreamType.MP4, MediaUrlClassifier.classify("https://cdn.example.com/a/v.mp4", page, SniffSource.REQUEST, 1L)?.type)
        assertEquals(StreamType.WEBM, MediaUrlClassifier.classify("https://cdn.example.com/a/v.webm", page, SniffSource.REQUEST, 1L)?.type)
    }

    @Test
    fun ignoresQueryStringWhenDetectingType() {
        val c = MediaUrlClassifier.classify("https://cdn.example.com/a/index.m3u8?token=abc", page, SniffSource.JS, 1L)
        assertEquals(StreamType.HLS, c?.type)
        assertEquals("cdn.example.com", c?.host)
    }

    @Test
    fun rejectsSegmentsAdsAndPseudoProtocols() {
        assertNull(MediaUrlClassifier.classify("https://cdn.example.com/a/seg-1.ts", page, SniffSource.REQUEST, 1L))
        assertNull(MediaUrlClassifier.classify("https://cdn.example.com/a/seg-1.m4s", page, SniffSource.REQUEST, 1L))
        assertNull(MediaUrlClassifier.classify("https://ads.example.com/a/index.m3u8", page, SniffSource.REQUEST, 1L))
        assertNull(MediaUrlClassifier.classify("blob:https://m.example.com/123", page, SniffSource.JS, 1L))
        assertNull(MediaUrlClassifier.classify("https://cdn.example.com/a/cover.jpg", page, SniffSource.PAGE_SOURCE, 1L))
    }

    @Test
    fun rankPutsHlsFirstAndBoostsSameHost() {
        val hlsOther = MediaCandidate("https://other.com/index.m3u8", StreamType.HLS, "other.com", null, 1L, SniffSource.REQUEST)
        val mp4Same = MediaCandidate("https://www.example.com/v.mp4", StreamType.MP4, "www.example.com", null, 2L, SniffSource.REQUEST)
        val mp4Other = MediaCandidate("https://other.com/v.mp4", StreamType.MP4, "other.com", null, 3L, SniffSource.REQUEST)
        val ranked = MediaUrlClassifier.rank(listOf(mp4Other, mp4Same, hlsOther), "www.example.com")
        assertEquals(listOf(hlsOther, mp4Same, mp4Other), ranked)
    }

    @Test
    fun rankDedupesByUrlKeepingNewest() {
        val old = MediaCandidate("https://cdn.example.com/a.m3u8", StreamType.HLS, "cdn.example.com", null, 1L, SniffSource.REQUEST)
        val new = old.copy(discoveredAt = 9L, source = SniffSource.JS)
        val ranked = MediaUrlClassifier.rank(listOf(old, new), "example.com")
        assertEquals(1, ranked.size)
        assertEquals(9L, ranked[0].discoveredAt)
    }
}
