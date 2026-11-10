package com.jqc.webplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackHeadersTest {
    @Test
    fun buildsRefererOriginAndMergedCookies() {
        val headers = PlaybackHeaders.build(
            pageUrl = "https://m.example.com:8443/play/1",
            userAgent = "TestUA",
            videoCookies = "a=1; b=2",
            pageCookies = "b=2; c=3",
        )
        assertEquals("TestUA", headers["User-Agent"])
        assertEquals("https://m.example.com:8443/play/1", headers["Referer"])
        assertEquals("https://m.example.com:8443", headers["Origin"])
        assertEquals("a=1; b=2; c=3", headers["Cookie"])
        assertEquals("*/*", headers["Accept"])
    }

    @Test
    fun omitsCookieHeaderWhenAbsent() {
        val headers = PlaybackHeaders.build("https://example.com/p", "UA", null, null)
        assertFalse(headers.containsKey("Cookie"))
    }
}
