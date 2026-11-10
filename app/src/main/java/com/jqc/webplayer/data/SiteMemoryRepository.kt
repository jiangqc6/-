package com.jqc.webplayer.data

import android.content.Context
import android.content.SharedPreferences

/** 单个站点的覆盖项；null 表示沿用全局设置。 */
data class SitePrefs(
    val desktopUa: Boolean? = null,
    val blockPopups: Boolean? = null,
) {
    val isEmpty: Boolean get() = desktopUa == null && blockPopups == null
}

/**
 * 按站点记住桌面版 UA / 弹窗拦截选择。
 * 用最小自定义格式存一条字符串，避免为几个开关引入序列化依赖。
 */
class SiteMemoryRepository(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences("web_player", Context.MODE_PRIVATE)
    )

    fun get(host: String): SitePrefs? {
        if (host.isBlank()) return null
        return decode(prefs.getString(KEY, null))[host]
    }

    fun setDesktopUa(host: String, enabled: Boolean?) {
        update(host) { it.copy(desktopUa = enabled) }
    }

    fun setBlockPopups(host: String, enabled: Boolean?) {
        update(host) { it.copy(blockPopups = enabled) }
    }

    fun clear(host: String) {
        update(host) { SitePrefs() }
    }

    private fun update(host: String, transform: (SitePrefs) -> SitePrefs) {
        if (host.isBlank()) return
        val map = decode(prefs.getString(KEY, null)).toMutableMap()
        val next = transform(map[host] ?: SitePrefs())
        if (next.isEmpty) map.remove(host) else map[host] = next
        prefs.edit().putString(KEY, encode(map)).apply()
    }

    private companion object {
        const val KEY = "site_prefs"

        fun flag(value: Boolean?): String = when (value) {
            true -> "1"
            false -> "0"
            null -> "-"
        }

        fun encode(map: Map<String, SitePrefs>): String =
            map.entries
                .filterNot { it.value.isEmpty }
                .joinToString(";") { (host, site) ->
                    "$host=${flag(site.desktopUa)},${flag(site.blockPopups)}"
                }

        fun decode(raw: String?): Map<String, SitePrefs> {
            if (raw.isNullOrBlank()) return emptyMap()
            val result = mutableMapOf<String, SitePrefs>()
            raw.split(';').forEach { entry ->
                val sep = entry.indexOf('=')
                if (sep <= 0) return@forEach
                val host = entry.substring(0, sep)
                val parts = entry.substring(sep + 1).split(',')
                if (parts.size != 2) return@forEach
                val desktopUa = when (parts[0]) {
                    "1" -> true
                    "0" -> false
                    "-" -> null
                    else -> return@forEach
                }
                val blockPopups = when (parts[1]) {
                    "1" -> true
                    "0" -> false
                    "-" -> null
                    else -> return@forEach
                }
                val site = SitePrefs(desktopUa, blockPopups)
                if (!site.isEmpty) result[host] = site
            }
            return result
        }
    }
}
