package com.jqc.webplayer.player

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.jqc.webplayer.ui.common.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    player: ExoPlayer,
    title: String,
    isFavorite: Boolean,
    errorText: String?,
    resumeHintMs: Long,
    isInPip: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenWeb: () -> Unit,
    onCopyUrl: () -> Unit,
    onDismissResumeHint: () -> Unit,
    onRestartFromBeginning: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleOrientation: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val isFullscreen = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var bufferPercent by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var qualityLabel by remember { mutableStateOf("自动") }
    var manualQuality by remember { mutableStateOf(false) }
    var selectedHeight by remember { mutableIntStateOf(0) }
    var qualities by remember { mutableStateOf<List<QualityOption>>(emptyList()) }
    var bufferingSince by remember { mutableLongStateOf(0L) }
    var showBuffering by remember { mutableStateOf(false) }
    var slowNetwork by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(-1L) }
    var gestureSeekPreview by remember { mutableStateOf<Long?>(null) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var boostActive by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var qualityMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            bufferPercent = player.bufferedPercentage
            val next = TrackQualityController.qualities(player)
            if (next != qualities) qualities = next
            selectedHeight = TrackQualityController.selectedHeight(player) ?: 0

            // 短暂卡顿不弹转圈，持续缓冲才提示；超过 5 秒视为弱网。
            val buffering = player.playbackState == Player.STATE_BUFFERING
            if (buffering) {
                val now = SystemClock.elapsedRealtime()
                if (bufferingSince == 0L) bufferingSince = now
                val elapsed = now - bufferingSince
                showBuffering = elapsed >= 600L
                slowNetwork = elapsed >= 5_000L
            } else {
                bufferingSince = 0L
                showBuffering = false
                slowNetwork = false
            }
            delay(500)
        }
    }

    LaunchedEffect(isPlaying, controlsVisible, isInPip) {
        if (isPlaying && controlsVisible && !isInPip) {
            delay(3_000)
            controlsVisible = false
        }
    }

    val showControls = !isInPip && (controlsVisible || !isPlaying)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    // 长按加速由下面的手势单独处理；这里声明长按可避免松手时又触发单击切换控件。
                    onLongPress = {},
                    onTap = { controlsVisible = !controlsVisible },
                )
            }
            .pointerInput(player) {
                var mode = 0 // 0 未定，1 进度，2 音量，3 亮度
                var startX = 0f
                var startY = 0f
                var startPosition = 0L
                var durationAtStart = 0L
                var seekPreview = 0L
                var startVolume = 0
                var maxVolume = 1
                var startBrightness = 0.5f
                var lastLiveSeek = 0L
                detectDragGestures(
                    onDragStart = { offset ->
                        mode = 0
                        startX = offset.x
                        startY = offset.y
                        startPosition = player.currentPosition
                        durationAtStart = player.duration.coerceAtLeast(0L)
                        seekPreview = startPosition
                        startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        maxVolume = audioManager
                            .getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            .coerceAtLeast(1)
                        startBrightness = activity.window.attributes.screenBrightness
                            .takeIf { it >= 0f } ?: 0.5f
                    },
                    onDragEnd = {
                        if (mode == 1) player.seekTo(seekPreview)
                        mode = 0
                        gestureSeekPreview = null
                        gestureHint = null
                    },
                    onDragCancel = {
                        mode = 0
                        gestureSeekPreview = null
                        gestureHint = null
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val dx = change.position.x - startX
                        val dy = change.position.y - startY
                        val threshold = 16.dp.toPx()
                        if (mode == 0) {
                            if (abs(dx) > threshold && abs(dx) > abs(dy)) {
                                mode = 1
                            } else if (abs(dy) > threshold) {
                                mode = if (change.position.x < size.width / 2f) 3 else 2
                            }
                        }
                        when (mode) {
                            1 -> {
                                if (durationAtStart > 0) {
                                    val deltaMs = (dx / size.width * durationAtStart).toLong()
                                    seekPreview = (startPosition + deltaMs)
                                        .coerceIn(0L, durationAtStart)
                                    gestureSeekPreview = seekPreview
                                    // 只在目标已缓冲时实时跟手；远距离快进若边拖边 seek，会让
                                    // 播放器反复丢弃缓冲、重新拉流，松手后反而更卡。
                                    // 未缓冲的跳转留到松手时一次完成。
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastLiveSeek > 250 &&
                                        seekPreview <= player.bufferedPosition
                                    ) {
                                        lastLiveSeek = now
                                        player.seekTo(seekPreview)
                                    }
                                }
                            }
                            2 -> {
                                val fraction = (-dy / (size.height * 0.6f)).coerceIn(-1f, 1f)
                                val target = (startVolume + fraction * maxVolume)
                                    .roundToInt()
                                    .coerceIn(0, maxVolume)
                                runCatching {
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        target,
                                        0,
                                    )
                                }
                                gestureHint = "音量 ${target * 100 / maxVolume}%"
                            }
                            3 -> {
                                val fraction = (-dy / (size.height * 0.6f)).coerceIn(-1f, 1f)
                                val target = (startBrightness + fraction).coerceIn(0.05f, 1f)
                                val attrs = activity.window.attributes
                                attrs.screenBrightness = target
                                activity.window.attributes = attrs
                                gestureHint = "亮度 ${(target * 100).roundToInt()}%"
                            }
                        }
                    },
                )
            }
            .pointerInput(player, speed) {
                // 长按画面临时 2 倍速，松手恢复；按下按钮/进度条时事件已被消费，不会误触。
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val finishedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var finished = false
                        while (!finished) {
                            val event = awaitPointerEvent()
                            finished = event.changes.any {
                                it.changedToUp() ||
                                    (it.position - down.position).getDistance() > viewConfiguration.touchSlop
                            }
                        }
                        true
                    }
                    if (finishedEarly == null && player.playWhenReady &&
                        player.playbackState != Player.STATE_ENDED
                    ) {
                        boostActive = true
                        player.setPlaybackSpeed(2f)
                        // 吃掉抬起前的事件，避免松手时又触发单击切换控件或拖动进度。
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                        player.setPlaybackSpeed(speed)
                        boostActive = false
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { it.player = player },
        )

        if (showControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                        tint = Color.White,
                    )
                }
            }

            if (!showBuffering) {
                IconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                        controlsVisible = true
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val currentMs = if (scrubPosition >= 0) scrubPosition else position
                val maxMs = duration.coerceAtLeast(1L)
                // 全屏（横屏）观看时不显示进度条，改用左右滑动快进/快退。
                if (!isFullscreen) {
                    if (bufferPercent < 100) {
                        LinearProgressIndicator(
                            progress = { bufferPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = Color.White.copy(alpha = 0.6f),
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    Slider(
                        value = currentMs.coerceIn(0L, maxMs).toFloat(),
                        onValueChange = { scrubPosition = it.toLong() },
                        onValueChangeFinished = {
                            if (scrubPosition >= 0) player.seekTo(scrubPosition)
                            scrubPosition = -1L
                        },
                        valueRange = 0f..maxMs.toFloat(),
                    )
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = TimeFormat.duration(currentMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = TimeFormat.duration(duration),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L))
                    }) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = "后退 10 秒",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = {
                        val target = player.currentPosition + 10_000
                        player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
                    }) {
                        Icon(
                            Icons.Default.Forward10,
                            contentDescription = "前进 10 秒",
                            tint = Color.White,
                        )
                    }
                    Spacer(Modifier.weight(1f))

                    Box {
                        TextButton(onClick = { speedMenuOpen = true }) {
                            Text("${speed}x", color = Color.White)
                        }
                        DropdownMenu(
                            expanded = speedMenuOpen,
                            onDismissRequest = { speedMenuOpen = false },
                        ) {
                            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("${option}x") },
                                    onClick = {
                                        player.setPlaybackSpeed(option)
                                        speed = option
                                        speedMenuOpen = false
                                    },
                                )
                            }
                        }
                    }

                    if (qualities.isNotEmpty()) {
                        Box {
                            TextButton(onClick = { qualityMenuOpen = true }) {
                                Text(
                                    text = if (manualQuality) {
                                        qualityLabel
                                    } else {
                                        selectedHeight
                                            .takeIf { it > 0 }
                                            ?.let { "自动·${it}P" }
                                            ?: "自动"
                                    },
                                    color = Color.White,
                                )
                            }
                            DropdownMenu(
                                expanded = qualityMenuOpen,
                                onDismissRequest = { qualityMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("自动") },
                                    onClick = {
                                        TrackQualityController.clearOverride(player)
                                        qualityLabel = "自动"
                                        manualQuality = false
                                        qualityMenuOpen = false
                                    },
                                )
                                qualities.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            TrackQualityController.select(player, option)
                                            qualityLabel = option.label
                                            manualQuality = true
                                            qualityMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    IconButton(onClick = onEnterPip) {
                        Icon(
                            Icons.Default.PictureInPictureAlt,
                            contentDescription = "画中画",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onToggleOrientation) {
                        Icon(
                            Icons.Default.ScreenRotation,
                            contentDescription = "旋转",
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        if (resumeHintMs > 0 && errorText == null && !isInPip) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp),
                color = Color.Black.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(
                        text = "已从 ${TimeFormat.duration(resumeHintMs)} 继续",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onRestartFromBeginning) { Text("从头播放") }
                    IconButton(
                        onClick = onDismissResumeHint,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "关闭提示", tint = Color.White)
                    }
                }
            }
        }

        if (boostActive && !isInPip) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 128.dp),
                color = Color.Black.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = "2.0x 加速中，松手恢复",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        gestureSeekPreview?.let { preview ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.75f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = "${TimeFormat.duration(preview)} / ${TimeFormat.duration(duration)}",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        gestureHint?.let { hint ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.75f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = hint,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (showBuffering && errorText == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color.White)
                if (slowNetwork) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "网络较慢，正在缓冲…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!manualQuality && qualities.size > 1) {
                        TextButton(onClick = {
                            val lowest = qualities.last()
                            TrackQualityController.select(player, lowest)
                            qualityLabel = lowest.label
                            manualQuality = true
                            controlsVisible = true
                        }) {
                            Text("切换到 ${qualities.last().label}")
                        }
                    }
                }
            }
        }

        if (errorText != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = errorText,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenWeb) { Text("回网页播放") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCopyUrl) { Text("复制地址") }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "地址可能已过期，可回网页重新嗅探",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
