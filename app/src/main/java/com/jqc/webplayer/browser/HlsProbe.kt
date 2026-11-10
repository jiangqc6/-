package com.jqc.webplayer.browser

import com.jqc.webplayer.core.MediaCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class HlsProbe(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun bestLabel(candidate: MediaCandidate, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(candidate.url)
                    .apply { headers.forEach { (key, value) -> header(key, value) } }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val text = response.peekBody(262_144).string()
                    val variants = com.jqc.webplayer.core.HlsManifestParser.parseMaster(text, candidate.url)
                    com.jqc.webplayer.core.HlsManifestParser.qualityLabel(variants)
                }
            }.getOrNull()
        }
}
