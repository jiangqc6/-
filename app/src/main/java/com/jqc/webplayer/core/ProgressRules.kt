package com.jqc.webplayer.core

object ProgressRules {
    const val MIN_RESUME_MS = 10_000L

    fun resumePosition(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0 && positionMs >= MIN_RESUME_MS && durationMs - positionMs > MIN_RESUME_MS) {
            positionMs
        } else {
            0L
        }
}
