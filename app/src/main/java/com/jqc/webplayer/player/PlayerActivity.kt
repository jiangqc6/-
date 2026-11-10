package com.jqc.webplayer.player

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.jqc.webplayer.WebPlayerApp
import com.jqc.webplayer.browser.HeaderFactory
import com.jqc.webplayer.core.ProgressRules
import com.jqc.webplayer.core.StreamType
import com.jqc.webplayer.ui.theme.WebPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var args: Args
    private var recorded = false
    private var rememberProgress = true

    private val favoriteState = mutableStateOf(false)
    private val errorState = mutableStateOf<String?>(null)
    private val resumeHintState = mutableLongStateOf(0L)
    private val isInPipState = mutableStateOf(false)

    private data class Args(
        val url: String,
        val type: StreamType,
        val pageUrl: String,
        val title: String,
        val headers: Map<String, String>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        args = parseArgs(intent)
        val container = (application as WebPlayerApp).container
        val historyRepository = container.historyRepository
        val favoritesRepository = container.favoritesRepository
        val settingsRepository = container.settingsRepository

        val headers = if (args.headers.isNotEmpty()) {
            args.headers
        } else {
            HeaderFactory.build(this, args.pageUrl, args.url)
        }

        player = buildPlayer(headers)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!recorded) {
                    recorded = true
                    lifecycleScope.launch {
                        val duration = player.duration.coerceAtLeast(0)
                        historyRepository.recordPlayback(
                            pageUrl = args.pageUrl,
                            title = args.title,
                            videoUrl = args.url,
                            type = args.type,
                            quality = null,
                            positionMs = player.currentPosition,
                            durationMs = duration,
                        )
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorState.value = PlayerErrorText.ofKind(PlayerErrorText.kindOf(error.errorCode))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveProgress()
            }
        })

        lifecycleScope.launch {
            rememberProgress = settingsRepository.flow.first().rememberProgress
            val existing = historyRepository.find(args.pageUrl)
            val start = if (rememberProgress && existing != null) {
                ProgressRules.resumePosition(existing.positionMs, existing.durationMs)
            } else {
                0L
            }
            if (start > 0) {
                player.seekTo(start)
                resumeHintState.value = start
            }
            player.playWhenReady = true
        }

        lifecycleScope.launch {
            favoriteState.value = favoritesRepository.isFavorite(args.pageUrl)
        }

        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                saveProgress()
            }
        }

        setContent {
            WebPlayerTheme {
                PlayerScreen(
                    player = player,
                    title = args.title.ifBlank { args.pageUrl },
                    isFavorite = favoriteState.value,
                    errorText = errorState.value,
                    resumeHintMs = resumeHintState.longValue,
                    isInPip = isInPipState.value,
                    onBack = { finish() },
                    onToggleFavorite = {
                        lifecycleScope.launch {
                            val added = favoritesRepository.toggle(args.pageUrl, args.title.ifBlank { args.pageUrl }, args.url)
                            favoriteState.value = added
                        }
                    },
                    onOpenWeb = { openInWeb() },
                    onCopyUrl = {
                        val clipboard = getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("视频地址", args.url))
                    },
                    onDismissResumeHint = { resumeHintState.longValue = 0L },
                    onRestartFromBeginning = {
                        player.seekTo(0)
                        resumeHintState.longValue = 0L
                    },
                    onEnterPip = { enterPipIfPossible() },
                    onToggleOrientation = { toggleOrientation() },
                )
            }
        }
    }

    private fun buildPlayer(headers: Map<String, String>): ExoPlayer {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setUserAgent(headers["User-Agent"] ?: "")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSource = if (args.type == StreamType.HLS) {
            val mediaItem = MediaItem.fromUri(args.url).buildUpon()
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(args.url))
        }

        return ExoPlayer.Builder(this).build().apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = false
            setHandleAudioBecomingNoisy(true)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
        }
    }

    private fun saveProgress() {
        if (!recorded || !rememberProgress) return
        val position = player.currentPosition
        val duration = player.duration.coerceAtLeast(0)
        val repo = (application as WebPlayerApp).container.historyRepository
        lifecycleScope.launch { repo.updateProgress(args.pageUrl, position, duration) }
    }

    private fun openInWeb() {
        setResult(PlayerContract.RESULT_OPEN_WEB, Intent().putExtra(PlayerContract.EXTRA_PAGE_URL, args.pageUrl))
        finish()
    }

    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val size = player.videoSize
        val ratio = if (size.height > 0) Rational(size.width, size.height) else Rational(16, 9)
        enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .build()
        )
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipState.value = isInPictureInPictureMode
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStop() {
        saveProgress()
        super.onStop()
    }

    override fun onDestroy() {
        saveProgress()
        player.release()
        super.onDestroy()
    }

    private fun parseArgs(intent: Intent): Args = Args(
        url = intent.getStringExtra(PlayerContract.EXTRA_URL).orEmpty(),
        type = runCatching {
            StreamType.valueOf(intent.getStringExtra(PlayerContract.EXTRA_TYPE) ?: StreamType.HLS.name)
        }.getOrDefault(StreamType.HLS),
        pageUrl = intent.getStringExtra(PlayerContract.EXTRA_PAGE_URL).orEmpty(),
        title = intent.getStringExtra(PlayerContract.EXTRA_TITLE).orEmpty(),
        headers = PlayerContract.headersFrom(intent),
    )
}
