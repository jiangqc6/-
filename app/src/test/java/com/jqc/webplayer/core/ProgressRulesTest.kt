package com.jqc.webplayer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressRulesTest {
    @Test
    fun resumesOnlyInTheMiddle() {
        assertEquals(15_000L, ProgressRules.resumePosition(15_000, 60_000))
        assertEquals(0L, ProgressRules.resumePosition(5_000, 60_000))
        assertEquals(0L, ProgressRules.resumePosition(55_000, 60_000))
        assertEquals(0L, ProgressRules.resumePosition(15_000, 0))
    }
}
