package com.jqc.webplayer.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.jqc.webplayer.core.StreamType

data class PlaybackRequest(
    val url: String,
    val type: StreamType,
    val pageUrl: String,
    val title: String,
    val headers: Map<String, String>,
) {
    fun toIntent(context: Context): Intent = Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerContract.EXTRA_URL, url)
        putExtra(PlayerContract.EXTRA_TYPE, this@PlaybackRequest.type.name)
        putExtra(PlayerContract.EXTRA_PAGE_URL, pageUrl)
        putExtra(PlayerContract.EXTRA_TITLE, title)
        putExtra(
            PlayerContract.EXTRA_HEADERS,
            Bundle().apply { headers.forEach { (key, value) -> putString(key, value) } }
        )
    }
}

object PlayerContract {
    const val EXTRA_URL = "extra_url"
    const val EXTRA_TYPE = "extra_type"
    const val EXTRA_PAGE_URL = "extra_page_url"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_HEADERS = "extra_headers"
    const val RESULT_OPEN_WEB = 1001

    fun headersFrom(intent: Intent): Map<String, String> {
        val bundle = intent.getBundleExtra(EXTRA_HEADERS) ?: return emptyMap()
        return bundle.keySet().associateWith { bundle.getString(it).orEmpty() }
    }
}
