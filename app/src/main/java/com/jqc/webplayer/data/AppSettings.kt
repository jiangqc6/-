package com.jqc.webplayer.data

data class AppSettings(
    val blockPopups: Boolean = true,
    val desktopUa: Boolean = false,
    val preferBestQuality: Boolean = true,
    val rememberProgress: Boolean = true,
)
