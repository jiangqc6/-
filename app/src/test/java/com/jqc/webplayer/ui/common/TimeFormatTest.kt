package com.jqc.webplayer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class TimeFormatTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun formatsDurations() {
        assertEquals("00:00", TimeFormat.duration(0))
        assertEquals("01:05", TimeFormat.duration(65_000))
        assertEquals("1:01:01", TimeFormat.duration(3_661_000))
    }

    @Test
    fun formatsRelativeTime() {
        val now = 1_800_000_000_000L
        assertEquals("刚刚", TimeFormat.relative(now - 30_000, now, zone))
        assertEquals("5 分钟前", TimeFormat.relative(now - 5 * 60_000, now, zone))
        assertEquals("3 小时前", TimeFormat.relative(now - 3 * 3_600_000, now, zone))
    }
}
