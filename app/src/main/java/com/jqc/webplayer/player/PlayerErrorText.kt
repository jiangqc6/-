package com.jqc.webplayer.player

import androidx.media3.common.PlaybackException

enum class PlayerErrorKind { HTTP, NETWORK, DECODING, UNKNOWN }

object PlayerErrorText {
    fun kindOf(errorCode: Int): PlayerErrorKind = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        -> PlayerErrorKind.HTTP

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> PlayerErrorKind.NETWORK

        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> PlayerErrorKind.DECODING

        else -> PlayerErrorKind.UNKNOWN
    }

    fun ofKind(kind: PlayerErrorKind): String = when (kind) {
        PlayerErrorKind.HTTP -> "视频地址已失效或被拒绝（HTTP 错误）"
        PlayerErrorKind.NETWORK -> "网络连接失败"
        PlayerErrorKind.DECODING -> "设备不支持该视频编码"
        PlayerErrorKind.UNKNOWN -> "播放失败，请尝试用网页播放"
    }
}
