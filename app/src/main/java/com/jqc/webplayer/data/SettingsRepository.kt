package com.jqc.webplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val blockPopups = booleanPreferencesKey("block_popups")
        val desktopUa = booleanPreferencesKey("desktop_ua")
        val preferBestQuality = booleanPreferencesKey("prefer_best_quality")
        val rememberProgress = booleanPreferencesKey("remember_progress")
    }

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            blockPopups = prefs[Keys.blockPopups] ?: true,
            desktopUa = prefs[Keys.desktopUa] ?: false,
            preferBestQuality = prefs[Keys.preferBestQuality] ?: true,
            rememberProgress = prefs[Keys.rememberProgress] ?: true,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = AppSettings(
                blockPopups = prefs[Keys.blockPopups] ?: true,
                desktopUa = prefs[Keys.desktopUa] ?: false,
                preferBestQuality = prefs[Keys.preferBestQuality] ?: true,
                rememberProgress = prefs[Keys.rememberProgress] ?: true,
            )
            val next = transform(current)
            prefs[Keys.blockPopups] = next.blockPopups
            prefs[Keys.desktopUa] = next.desktopUa
            prefs[Keys.preferBestQuality] = next.preferBestQuality
            prefs[Keys.rememberProgress] = next.rememberProgress
        }
    }
}
