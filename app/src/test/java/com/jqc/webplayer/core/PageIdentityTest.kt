package com.jqc.webplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageIdentityTest {
    @Test
    fun stripsTrackingParamsButKeepsSemanticOnes() {
        assertEquals(
            "https://example.com/vod/play?id=1&page=2",
            PageIdentity.key("https://example.com/vod/play?utm_source=wx&id=1&spm=abc&page=2&fbclid=x"),
        )
    }

    @Test
    fun sortsRemainingParamsForStableKey() {
        assertEquals(
            PageIdentity.key("https://example.com/v?a=1&b=2"),
            PageIdentity.key("https://example.com/v?b=2&a=1"),
        )
    }

    @Test
    fun keepsFragmentBecauseSpaRoutesUseIt() {
        assertEquals(
            "https://example.com/app#/play/9",
            PageIdentity.key("https://example.com/app?utm_medium=x#/play/9"),
        )
    }

    @Test
    fun normalizesHostDefaultPortAndRootPath() {
        assertEquals("https://example.com/", PageIdentity.key("https://EXAMPLE.com:443"))
        assertEquals("http://example.com/", PageIdentity.key("http://example.com:80/"))
    }

    @Test
    fun differentSemanticParamsStayDifferent() {
        assertEquals(false, PageIdentity.key("https://example.com/v?id=1") == PageIdentity.key("https://example.com/v?id=2"))
    }

    @Test
    fun rejectsInvalidUrls() {
        assertNull(PageIdentity.key(""))
        assertNull(PageIdentity.key("javascript:alert(1)"))
        assertNull(PageIdentity.key("https:///path"))
    }
}
