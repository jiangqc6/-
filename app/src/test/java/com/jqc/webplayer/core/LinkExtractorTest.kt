package com.jqc.webplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkExtractorTest {
    @Test
    fun extractsUrlFromShareText() {
        val text = "《庆余年》第2集 https://m.example.com/vod/play/1?utm_source=wx 复制打开"
        assertEquals(
            "https://m.example.com/vod/play/1?utm_source=wx",
            LinkExtractor.extract(text),
        )
    }

    @Test
    fun trimsChinesePunctuationAfterUrl() {
        assertEquals(
            "https://example.com/v/1",
            LinkExtractor.extract("看这个 https://example.com/v/1，好看"),
        )
        assertEquals("https://example.com/v/1", LinkExtractor.extract("https://example.com/v/1。"))
    }

    @Test
    fun acceptsBareUrl() {
        assertEquals("https://example.com/v/1", LinkExtractor.extract("  https://example.com/v/1  "))
    }

    @Test
    fun keepsQueryAndFragmentWithoutRewriting() {
        assertEquals(
            "https://example.com/v?id=1&spm=abc#t=10",
            LinkExtractor.extract("链接：https://example.com/v?id=1&spm=abc#t=10 打开"),
        )
    }

    @Test
    fun returnsFirstHttpUrlOnly() {
        assertEquals(
            "https://a.com/1",
            LinkExtractor.extract("https://a.com/1 和 https://b.com/2"),
        )
    }

    @Test
    fun rejectsTextWithoutHttpUrl() {
        assertNull(LinkExtractor.extract("没有链接"))
        assertNull(LinkExtractor.extract("ftp://a.com/x"))
        assertNull(LinkExtractor.extract(""))
    }

    @Test
    fun rejectsUnsupportedSchemeLikeJavascript() {
        assertNull(LinkExtractor.extract("javascript:alert(1)"))
    }
}
