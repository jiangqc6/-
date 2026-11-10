package com.jqc.webplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun addsHttpsWhenSchemeMissing() {
        assertEquals("https://example.com/a", UrlNormalizer.normalize(" example.com/a "))
    }

    @Test
    fun keepsHttpScheme() {
        assertEquals("http://example.com/a", UrlNormalizer.normalize("http://example.com/a"))
    }

    @Test
    fun rejectsNonHttpAndBlank() {
        assertNull(UrlNormalizer.normalize(""))
        assertNull(UrlNormalizer.normalize("javascript:alert(1)"))
        assertNull(UrlNormalizer.normalize("ftp://example.com/x"))
        assertNull(UrlNormalizer.normalize("not a url"))
    }

    @Test
    fun rejectsHostlessUrl() {
        assertNull(UrlNormalizer.normalize("https:///path"))
    }
}
