package com.jqc.webplayer.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeFormat {
    fun duration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) + 500) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun relative(timestampMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val diff = nowMs - timestampMs
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            else -> {
                val date = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
                val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
                val pattern = if (date.year == now.year) "MM-dd" else "yyyy-MM-dd"
                date.format(DateTimeFormatter.ofPattern(pattern))
            }
        }
    }
}
