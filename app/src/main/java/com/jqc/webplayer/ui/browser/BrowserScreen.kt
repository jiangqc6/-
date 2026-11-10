package com.jqc.webplayer.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jqc.webplayer.browser.BrowserController
import com.jqc.webplayer.browser.HeaderFactory
import com.jqc.webplayer.browser.HlsProbe
import com.jqc.webplayer.core.MediaCandidate
import com.jqc.webplayer.core.MediaCollector
import com.jqc.webplayer.core.MediaUrlClassifier
import com.jqc.webplayer.core.StreamType
import com.jqc.webplayer.data.AppSettings
import com.jqc.webplayer.data.SiteMemoryRepository
import com.jqc.webplayer.data.SitePrefs
import com.jqc.webplayer.data.WatchSessionRepository
import com.jqc.webplayer.player.PlaybackRequest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String,
    reloadToken: Int,
    settings: AppSettings,
    hlsProbe: HlsProbe,
    watchSessionRepository: WatchSessionRepository,
    siteMemoryRepository: SiteMemoryRepository,
    isFavorite: Boolean,
    onBackToHome: () -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
    onPageVisited: (pageUrl: String, title: String) -> Unit,
    onPlaybackProgress: (
        pageUrl: String,
        pageTitle: String,
        videoKey: String,
        positionMs: Long,
        durationMs: Long,
    ) -> Unit,
    onToggleFavorite: (pageUrl: String, title: String, videoUrl: String?) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as android.app.Activity

    val generationState = rememberSaveable { mutableIntStateOf(0) }
    val crashCountState = remember { mutableIntStateOf(0) }
    val generation = generationState.intValue
    val collector = remember(generation) { MediaCollector() }
    val controllerHolder = remember(generation) { arrayOfNulls<BrowserController>(1) }

    val titleState = rememberSaveable { mutableStateOf("") }
    val currentUrlState = rememberSaveable { mutableStateOf(initialUrl) }
    val candidatesState = remember { mutableStateOf<List<MediaCandidate>>(emptyList()) }
    val pageErrorState = remember { mutableStateOf<String?>(null) }
    val sslErrorState = remember { mutableStateOf<String?>(null) }
    val messageCallback = rememberUpdatedState(onMessage)
    val pageVisitedCallback = rememberUpdatedState(onPageVisited)
    val playbackProgressCallback = rememberUpdatedState(onPlaybackProgress)
    val playCallback = rememberUpdatedState(onPlay)
    val rememberProgress = rememberUpdatedState(settings.rememberProgress)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val resumeNoticeState = remember { mutableStateOf<ResumeNotice?>(null) }
    // 网页视频是否正在播放：只用于浮现“影院模式”快捷入口，暂停即收起。
    var webPlaying by remember { mutableStateOf(false) }
    // 卡顿/失败兜底提示按视频节流，避免同一个视频反复打扰。
    val videoNoticeAt = remember { mutableMapOf<String, Long>() }

    fun showVideoFallback(key: String, src: String, buffering: Boolean) {
        val now = System.currentTimeMillis()
        if (now - (videoNoticeAt[key] ?: 0L) < 60_000) return
        videoNoticeAt[key] = now
        val candidate = candidatesState.value.firstOrNull { sameMediaUrl(it.url, src) }
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = if (buffering) "网页视频加载缓慢" else "网页视频播放失败",
                actionLabel = if (candidate != null) "原生播放" else "重试页面",
                duration = SnackbarDuration.Long,
            )
            if (result != SnackbarResult.ActionPerformed) return@launch
            if (candidate != null) {
                val pageUrl = currentUrlState.value
                val ua = controllerHolder[0]?.userAgent()
                playCallback.value(
                    PlaybackRequest(
                        url = candidate.url,
                        type = candidate.type,
                        pageUrl = pageUrl,
                        title = titleState.value.ifBlank { pageUrl },
                        headers = HeaderFactory.build(context, pageUrl, candidate.url, ua),
                    )
                )
            } else {
                controllerHolder[0]?.reload()
            }
        }
    }

    val listener = remember {
        object : BrowserController.Listener {
            override fun onCandidates(candidates: List<MediaCandidate>) {
                candidatesState.value = candidates
            }

            override fun onTitleChanged(title: String) {
                titleState.value = title
            }

            override fun onUrlChanged(url: String) {
                currentUrlState.value = url
                webPlaying = false
            }

            override fun onPageError(message: String?) {
                pageErrorState.value = message
            }

            override fun onSslError(url: String) {
                sslErrorState.value = url
            }

            override fun onRenderProcessGone() {
                if (crashCountState.intValue >= 2) {
                    pageErrorState.value = "网页进程反复崩溃，已停止重建。请返回首页后重试。"
                    return
                }
                crashCountState.intValue += 1
                pageErrorState.value = "网页进程异常，已重建"
                generationState.intValue += 1
            }

            override fun onPageFinished() {
                crashCountState.intValue = 0
                // 打开过的网页都进历史；标题此时已由 onTitleChanged 更新。
                val visitedUrl = controllerHolder[0]?.currentUrl()?.takeIf { it.isNotBlank() }
                    ?: currentUrlState.value
                pageVisitedCallback.value(visitedUrl, titleState.value)
                // 页面脚本已注入，查一下这个网页上次看到哪；JS 用视频地址匹配，不会串页。
                if (rememberProgress.value) {
                    scope.launch {
                        val session = watchSessionRepository.findForPage(visitedUrl) ?: return@launch
                        if (session.positionMs < 5_000) return@launch
                        controllerHolder[0]?.applyResume(session)
                    }
                }
            }

            override fun onPlaybackProgress(videoKey: String, positionMs: Long, durationMs: Long) {
                if (!rememberProgress.value) return
                val pageUrl = controllerHolder[0]?.currentUrl()?.takeIf { it.isNotBlank() }
                    ?: currentUrlState.value
                // 落库交给首页侧的长生命周期协程：离开浏览器页时最后一次 flush
                // 会晚于本页销毁到达，本页的作用域那时已被取消。
                playbackProgressCallback.value(
                    pageUrl,
                    titleState.value,
                    videoKey,
                    positionMs,
                    durationMs,
                )
            }

            override fun onResumeApplied(videoKey: String, positionMs: Long) {
                if (!rememberProgress.value) return
                resumeNoticeState.value = ResumeNotice(
                    videoKey = videoKey,
                    positionMs = positionMs,
                    pageUrl = controllerHolder[0]?.currentUrl()?.takeIf { it.isNotBlank() }
                        ?: currentUrlState.value,
                )
            }

            override fun onPlaybackStateChanged(playing: Boolean) {
                webPlaying = playing
            }

            override fun onVideoBuffering(videoKey: String, src: String) {
                showVideoFallback(videoKey, src, buffering = true)
            }

            override fun onVideoError(videoKey: String, src: String) {
                showVideoFallback(videoKey, src, buffering = false)
            }

            override fun onMessage(message: String) {
                messageCallback.value(message)
            }
        }
    }

    var showMediaSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var chromeHidden by rememberSaveable { mutableStateOf(false) }

    // 清屏：隐藏 App 顶栏与系统栏；离开本页时恢复，避免沉浸状态带回首页。
    DisposableEffect(chromeHidden) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (chromeHidden) {
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val currentUrl = currentUrlState.value
    val title = titleState.value
    val candidates = candidatesState.value
    val pageError = pageErrorState.value
    val sslErrorUrl = sslErrorState.value

    // 站点记忆：本轮访问里对该站点的覆盖项，null 表示沿用全局设置。
    val siteHost = MediaUrlClassifier.hostOf(currentUrl)
    var sitePrefs by remember { mutableStateOf<SitePrefs?>(null) }
    LaunchedEffect(siteHost) {
        sitePrefs = siteHost?.let { siteMemoryRepository.get(it) }
    }
    val effectiveDesktopUa = sitePrefs?.desktopUa ?: settings.desktopUa
    val effectiveBlockPopups = sitePrefs?.blockPopups ?: settings.blockPopups

    DisposableEffect(generation) {
        onDispose {
            controllerHolder[0]?.destroy()
            controllerHolder[0] = null
        }
    }

    // 进入原生播放器或退到后台时暂停网页，避免后台页面继续拉流抢带宽。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controllerHolder[0]?.pause()
                Lifecycle.Event.ON_START -> controllerHolder[0]?.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(effectiveDesktopUa) {
        controllerHolder[0]?.setDesktopUa(effectiveDesktopUa)
    }

    LaunchedEffect(effectiveBlockPopups) {
        controllerHolder[0]?.setBlockPopups(effectiveBlockPopups)
    }

    LaunchedEffect(reloadToken) {
        if (reloadToken > 0) controllerHolder[0]?.reload()
    }

    LaunchedEffect(candidates, currentUrl) {
        val pending = candidates.filter { it.type == StreamType.HLS && it.qualityLabel == null }
        if (pending.isEmpty()) return@LaunchedEffect
        val controller = controllerHolder[0] ?: return@LaunchedEffect
        for (candidate in pending) {
            val headers = HeaderFactory.build(context, currentUrl, candidate.url, controller.userAgent())
            val label = hlsProbe.bestLabel(candidate, headers)
            if (label != null) {
                candidatesState.value = collector.setQuality(candidate.url, label)
            }
        }
    }

    BackHandler {
        val controller = controllerHolder[0]
        when {
            controller?.isFullscreen() == true -> controller.exitFullscreen()
            chromeHidden -> chromeHidden = false
            controller?.canGoBack() == true -> controller.goBack()
            else -> onBackToHome()
        }
    }

    // 续播成功后才提示，并把“从头播放”留成一个明确出口。
    val resumeNotice = resumeNoticeState.value
    LaunchedEffect(resumeNotice) {
        if (resumeNotice == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "已续播到 ${formatPosition(resumeNotice.positionMs)}",
            actionLabel = "从头播放",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            controllerHolder[0]?.seekWebToStart(resumeNotice.videoKey)
            watchSessionRepository.clear(resumeNotice.pageUrl)
        }
        resumeNoticeState.value = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!chromeHidden) {
            TopAppBar(
                title = {
                    Text(
                        text = title.ifBlank { currentUrl },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val controller = controllerHolder[0]
                        when {
                            controller?.isFullscreen() == true -> controller.exitFullscreen()
                            controller?.canGoBack() == true -> controller.goBack()
                            else -> onBackToHome()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { controllerHolder[0]?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    TextButton(onClick = { showMediaSheet = true }) {
                        Text("视频源(${candidates.size})")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    val suffix = if (sitePrefs?.desktopUa != null) "（本站）" else ""
                                    Text(
                                        if (effectiveDesktopUa) "桌面版网页：开$suffix"
                                        else "桌面版网页：关$suffix"
                                    )
                                },
                                onClick = {
                                    val host = siteHost
                                    if (host != null) {
                                        val next = !effectiveDesktopUa
                                        sitePrefs = (sitePrefs ?: SitePrefs()).copy(desktopUa = next)
                                        siteMemoryRepository.setDesktopUa(host, next)
                                    }
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    val suffix = if (sitePrefs?.blockPopups != null) "（本站）" else ""
                                    Text(
                                        if (effectiveBlockPopups) "拦截弹窗：开$suffix"
                                        else "拦截弹窗：关$suffix"
                                    )
                                },
                                onClick = {
                                    val host = siteHost
                                    if (host != null) {
                                        val next = !effectiveBlockPopups
                                        sitePrefs = (sitePrefs ?: SitePrefs()).copy(blockPopups = next)
                                        siteMemoryRepository.setBlockPopups(host, next)
                                    }
                                    showMenu = false
                                },
                            )
                            if (sitePrefs?.desktopUa != null || sitePrefs?.blockPopups != null) {
                                DropdownMenuItem(
                                    text = { Text("恢复本站默认设置") },
                                    onClick = {
                                        siteHost?.let { siteMemoryRepository.clear(it) }
                                        sitePrefs = null
                                        showMenu = false
                                        messageCallback.value("已恢复本站默认设置")
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (isFavorite) "取消收藏" else "收藏此页") },
                                onClick = {
                                    onToggleFavorite(
                                        currentUrl,
                                        title.ifBlank { currentUrl },
                                        candidates.firstOrNull()?.url,
                                    )
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("清屏模式") },
                                onClick = {
                                    chromeHidden = true
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("在系统浏览器打开") },
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                                    }.onFailure { messageCallback.value("无法打开系统浏览器") }
                                    showMenu = false
                                },
                            )
                        }
                    }
                },
            )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            key(generation) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // 按站点覆盖项构造，避免先按全局 UA 加载再因覆盖而二次刷新。
                        val loadHost = MediaUrlClassifier.hostOf(currentUrlState.value.ifBlank { initialUrl })
                        val site = loadHost?.let { siteMemoryRepository.get(it) }
                        val effective = settings.copy(
                            desktopUa = site?.desktopUa ?: settings.desktopUa,
                            blockPopups = site?.blockPopups ?: settings.blockPopups,
                        )
                        BrowserController(activity, effective, collector, listener).also { controller ->
                            controllerHolder[0] = controller
                            controller.load(currentUrlState.value.ifBlank { initialUrl })
                        }.webView
                    },
                )
            }

            if (pageError != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(pageError, style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                runCatching {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText("网页地址", currentUrl)
                                    )
                                }.onSuccess {
                                    messageCallback.value("已复制网页地址")
                                }.onFailure {
                                    messageCallback.value("复制失败")
                                }
                            }) { Text("复制链接") }
                            TextButton(onClick = {
                                pageErrorState.value = null
                                controllerHolder[0]?.reload()
                            }) { Text("重试") }
                        }
                    }
                }
            }

            // 网页视频播放中浮现“影院模式”：一键清屏并把视频滚到视口中央。
            if (webPlaying && !chromeHidden) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .clickable {
                            chromeHidden = true
                            controllerHolder[0]?.enterTheater()
                        },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = "影院模式",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // 清屏后的可见出口：不依赖系统栏，随时能恢复 App 栏。
            if (chromeHidden) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .clickable { chromeHidden = false },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = "退出清屏",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    if (sslErrorUrl != null) {
        AlertDialog(
            onDismissRequest = { sslErrorState.value = null },
            title = { Text("证书错误") },
            text = { Text("页面证书校验失败，已阻止加载。\n$sslErrorUrl") },
            confirmButton = {
                TextButton(onClick = { sslErrorState.value = null }) { Text("知道了") }
            },
        )
    }

    if (showMediaSheet) {
        ModalBottomSheet(onDismissRequest = { showMediaSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text("视频源", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (candidates.isEmpty()) {
                    Text(
                        text = "未发现可播放视频。常见原因：视频在跨域 iframe 内、需要先点网页里的播放按钮，或站点使用了加密/blob 地址。可以先在网页内继续播放，或重试嗅探。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            showMediaSheet = false
                            controllerHolder[0]?.reload()
                        }) { Text("重新嗅探") }
                        TextButton(onClick = { showMediaSheet = false }) { Text("继续网页播放") }
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(candidates, key = { it.url }) { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = candidate.type.name,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(candidate.qualityLabel ?: "未知清晰度")
                                    Text(
                                        text = "${candidate.host} · ${candidate.url}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // 原生播放必须显式点，避免用户在找“回网页”时被切走播放器。
                                TextButton(onClick = {
                                    showMediaSheet = false
                                    val ua = controllerHolder[0]?.userAgent()
                                    onPlay(
                                        PlaybackRequest(
                                            url = candidate.url,
                                            type = candidate.type,
                                            pageUrl = currentUrl,
                                            title = title.ifBlank { currentUrl },
                                            headers = HeaderFactory.build(
                                                context,
                                                currentUrl,
                                                candidate.url,
                                                ua,
                                            ),
                                        )
                                    )
                                }) { Text("原生播放") }
                                TextButton(onClick = {
                                    runCatching {
                                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                                        clipboard?.setPrimaryClip(
                                            ClipData.newPlainText("视频地址", candidate.url)
                                        )
                                    }.onSuccess {
                                        messageCallback.value("已复制视频地址")
                                    }.onFailure {
                                        messageCallback.value("复制失败")
                                    }
                                }) { Text("复制地址") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private data class ResumeNotice(
    val videoKey: String,
    val positionMs: Long,
    val pageUrl: String,
)

private fun sameMediaUrl(a: String, b: String): Boolean {
    if (a.isBlank() || b.isBlank()) return false
    if (a == b) return true
    fun base(url: String) = url.substringBefore('#').substringBefore('?')
    return base(a) == base(b)
}

private fun formatPosition(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
