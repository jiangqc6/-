package com.jqc.webplayer

import android.content.Context
import com.jqc.webplayer.data.AppDatabase
import com.jqc.webplayer.data.FavoritesRepository
import com.jqc.webplayer.data.HistoryRepository
import com.jqc.webplayer.data.SettingsRepository
import com.jqc.webplayer.data.SiteMemoryRepository
import com.jqc.webplayer.data.WatchSessionRepository
import com.jqc.webplayer.browser.HlsProbe

class AppContainer(context: Context) {
    private val database = AppDatabase.get(context)
    val historyRepository = HistoryRepository(database.historyDao())
    val favoritesRepository = FavoritesRepository(database.favoriteDao())
    val watchSessionRepository =
        WatchSessionRepository(database.watchSessionDao(), database.historyDao())
    val settingsRepository = SettingsRepository(context)
    val siteMemoryRepository = SiteMemoryRepository(context)
    val hlsProbe = HlsProbe()
}
