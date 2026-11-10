package com.jqc.webplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsManifestParserTest {
    private val base = "https://cdn.example.com/hls/master.m3u8"

    @Test
    fun parsesMasterWithRelativeAndAbsoluteVariants() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
            https://cdn.example.com/hls/720/index.m3u8
        """.trimIndent()

        assertTrue(HlsManifestParser.isMaster(text))
        val variants = HlsManifestParser.parseMaster(text, base)
        assertEquals(2, variants.size)
        assertEquals("https://cdn.example.com/hls/360/index.m3u8", variants[0].url)
        assertEquals(360, variants[0].height)
        assertEquals(800000L, variants[0].bandwidth)
        assertEquals("https://cdn.example.com/hls/720/index.m3u8", variants[1].url)
    }

    @Test
    fun mediaPlaylistHasNoVariants() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            seg-1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertFalse(HlsManifestParser.isMaster(text))
        assertTrue(HlsManifestParser.parseMaster(text, base).isEmpty())
    }

    @Test
    fun qualityLabelUsesLargestHeight() {
        val variants = listOf(
            HlsVariant("https://x/360.m3u8", 640, 360, 800000),
            HlsVariant("https://x/1080.m3u8", 1920, 1080, 4000000),
        )
        assertEquals("1080P", HlsManifestParser.qualityLabel(variants))
        assertNull(HlsManifestParser.qualityLabel(emptyList()))
        assertNull(HlsManifestParser.qualityLabel(listOf(HlsVariant("https://x/a.m3u8", null, null, 1))))
    }
}
