package com.jqc.webplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerErrorTextTest {
    @Test
    fun mapsHttpFailure() {
        assertEquals(
            "视频地址已失效或被拒绝（HTTP 错误）",
            PlayerErrorText.ofKind(PlayerErrorKind.HTTP)
        )
    }

    @Test
    fun mapsNetworkAndDecode() {
        assertEquals("网络连接失败", PlayerErrorText.ofKind(PlayerErrorKind.NETWORK))
        assertEquals("设备不支持该视频编码", PlayerErrorText.ofKind(PlayerErrorKind.DECODING))
        assertEquals("播放失败，请尝试用网页播放", PlayerErrorText.ofKind(PlayerErrorKind.UNKNOWN))
    }
}
