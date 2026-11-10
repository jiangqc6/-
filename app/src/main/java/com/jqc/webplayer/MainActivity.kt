package com.jqc.webplayer

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jqc.webplayer.browser.HlsProbe
import com.jqc.webplayer.data.AppSettings
import com.jqc.webplayer.player.PlaybackRequest
import com.jqc.webplayer.player.PlayerContract
import com.jqc.webplayer.ui.browser.BrowserScreen
import com.jqc.webplayer.ui.common.rememberClipboardUrl
import com.jqc.webplayer.ui.home.HomeScreen
import com.jqc.webplayer.ui.theme.WebPlayerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 仅调试包开启 WebView 远程调试，方便排查网页内播放问题；正式包保持关闭。
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        setContent {
            WebPlayerTheme {
                val container = (application as WebPlayerApp).container
                val history by container.historyRepository.observe()
                    .collectAsStateWithLifecycle(emptyList())
                val favorites by container.favoritesRepository.observe()
                    .collectAsStateWithLifecycle(emptyList())
                val watchSessions by container.watchSessionRepository.observeRecent()
                    .collectAsStateWithLifecycle(emptyList())
                val settings by container.settingsRepository.flow
                    .collectAsStateWithLifecycle(AppSettings())
                val clipboardUrl = rememberClipboardUrl()
                val scope = rememberCoroutineScope()
                val hlsProbe = remember { HlsProbe() }
                var browserUrl by rememberSaveable { mutableStateOf<String?>(null) }
                var browserReloadToken by rememberSaveable { mutableIntStateOf(0) }

                val playerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == PlayerContract.RESULT_OPEN_WEB) {
                        result.data
                            ?.getStringExtra(PlayerContract.EXTRA_PAGE_URL)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { pageUrl ->
                                browserUrl = pageUrl
                                browserReloadToken += 1
                            }
                    }
                }

                val currentUrl = browserUrl
                if (currentUrl == null) {
                    HomeScreen(
                        history = history,
                        favorites = favorites,
                        continueWatching = watchSessions,
                        clipboardUrl = clipboardUrl,
                        settings = settings,
                        onOpenUrl = { url ->
                            browserUrl = url
                            browserReloadToken = 0
                        },
                        onPlayDirect = { url, type ->
                            playerLauncher.launch(
                                PlaybackRequest(
                                    url = url,
                                    type = type,
                                    pageUrl = url,
                                    title = url,
                                    headers = emptyMap(),
                                ).toIntent(this@MainActivity)
                            )
                        },
                        onPlayHistory = { entity ->
                            // 历史按“网页”记录：点一下回到该网页；网页内再自己点播放。
                            browserUrl = entity.pageUrl
                            browserReloadToken = 0
                        },
                        onPlayFavorite = { entity ->
                            // 收藏同样一律回网页，不在收藏列表里偷偷切到原生播放器。
                            browserUrl = entity.pageUrl
                            browserReloadToken = 0
                        },
                        onResumeSession = { session ->
                            // 回网页后由 P0-1 的自动续播接力，不另起一套播放路径。
                            browserUrl = session.pageUrl
                            browserReloadToken = 0
                        },
                        onDeleteHistory = { id ->
                            scope.launch { container.historyRepository.delete(id) }
                        },
                        onClearHistory = {
                            scope.launch {
                                container.historyRepository.clear()
                                // 历史与播放进度是同一套“浏览记录”，清空历史必须一起清，
                                // 否则首页“继续观看”还留着已删掉的记录。
                                container.watchSessionRepository.clearAll()
                            }
                        },
                        onDeleteFavorite = { id ->
                            scope.launch { container.favoritesRepository.delete(id) }
                        },
                        onRenameFavorite = { favorite, title ->
                            scope.launch {
                                container.favoritesRepository.rename(favorite.id, title)
                            }
                        },
                        onSettingsChange = { newSettings ->
                            scope.launch { container.settingsRepository.update { newSettings } }
                        },
                    )
                } else {
                    BrowserScreen(
                        initialUrl = currentUrl,
                        reloadToken = browserReloadToken,
                        settings = settings,
                        hlsProbe = hlsProbe,
                        watchSessionRepository = container.watchSessionRepository,
                        siteMemoryRepository = container.siteMemoryRepository,
                        isFavorite = favorites.any { it.pageUrl == currentUrl },
                        onBackToHome = { browserUrl = null },
                        onPlay = { request ->
                            playerLauncher.launch(request.toIntent(this@MainActivity))
                        },
                        onPageVisited = { pageUrl, pageTitle ->
                            scope.launch {
                                container.historyRepository.recordVisit(pageUrl, pageTitle)
                            }
                        },
                        onPlaybackProgress = { pageUrl, pageTitle, videoKey, positionMs, durationMs ->
                            scope.launch {
                                container.watchSessionRepository.save(
                                    pageUrl,
                                    pageTitle,
                                    videoKey,
                                    positionMs,
                                    durationMs,
                                )
                            }
                        },
                        onToggleFavorite = { pageUrl, pageTitle, videoUrl ->
                            scope.launch {
                                container.favoritesRepository
                                    .toggle(pageUrl, pageTitle, videoUrl)
                            }
                        },
                        onMessage = { message ->
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}
