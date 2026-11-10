package com.jqc.webplayer.ui.common

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jqc.webplayer.core.LinkExtractor

private fun readClipboardUrl(context: Context): String? = runCatching {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return@runCatching null
    val clip = manager.primaryClip ?: return@runCatching null
    if (clip.itemCount == 0) return@runCatching null
    // 复制分享文案时通常带标题和“复制打开”等字样，提取其中的第一个链接。
    LinkExtractor.extract(clip.getItemAt(0).coerceToText(context).toString())
}.getOrNull()

@Composable
fun rememberClipboardUrl(): String? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var url by remember { mutableStateOf(readClipboardUrl(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                url = readClipboardUrl(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return url
}
