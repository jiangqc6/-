package com.jqc.webplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCollectorTest {
    private val page = "https://m.example.com/play/1"

    @Test
    fun dedupesSameUrl() {
        val collector = MediaCollector { 1L }
        collector.startPage(page)
        collector.offer("https://cdn.example.com/a.m3u8", SniffSource.REQUEST)
        val list = collector.offer("https://cdn.example.com/a.m3u8", SniffSource.JS)
        assertEquals(1, list.size)
    }

    @Test
    fun startPageClearsButUpdatePageKeeps() {
        val collector = MediaCollector { 1L }
        collector.startPage(page)
        collector.offer("https://cdn.example.com/a.m3u8", SniffSource.REQUEST)
        collector.updatePage("https://m.example.com/play/2")
        assertEquals(1, collector.snapshot().size)
        collector.startPage("https://m.example.com/play/3")
        assertTrue(collector.snapshot().isEmpty())
    }

    @Test
    fun setQualityUpdatesCandidate() {
        val collector = MediaCollector { 1L }
        collector.startPage(page)
        collector.offer("https://cdn.example.com/a.m3u8", SniffSource.REQUEST)
        val list = collector.setQuality("https://cdn.example.com/a.m3u8", "1080P")
        assertEquals("1080P", list.first().qualityLabel)
    }

    @Test
    fun ignoresNonMediaRequests() {
        val collector = MediaCollector { 1L }
        collector.startPage(page)
        val list = collector.offer("https://cdn.example.com/app.js", SniffSource.REQUEST)
        assertTrue(list.isEmpty())
    }
}
