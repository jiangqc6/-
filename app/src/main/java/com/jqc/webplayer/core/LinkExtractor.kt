package com.jqc.webplayer.core

/**
 * 从“标题 + 链接 + 复制打开”这类分享文案里提取第一个 http(s) 地址。
 * 只提取，不改写：utm/spm 等跟踪参数保留在打开的地址里，去重由 PageIdentity 负责。
 */
object LinkExtractor {
    private const val MAX_TEXT = 20_000

    private val urlRegex = Regex(
        """https?://[^\s<>"'`，。；：！？、（）【】《》]+""",
        RegexOption.IGNORE_CASE,
    )

    private val trailingChars = charArrayOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'', '、',
    )

    fun extract(text: String): String? {
        val source = text.trim()
        if (source.isEmpty()) return null
        val window = if (source.length > MAX_TEXT) source.take(MAX_TEXT) else source
        var url = urlRegex.find(window)?.value ?: return null
        while (url.isNotEmpty() && url.last() in trailingChars) {
            url = url.dropLast(1)
        }
        if (url.length <= "https://".length) return null
        return UrlNormalizer.normalize(url)
    }
}
