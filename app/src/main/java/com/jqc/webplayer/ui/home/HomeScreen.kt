package com.jqc.webplayer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jqc.webplayer.core.LinkExtractor
import com.jqc.webplayer.core.MediaUrlClassifier
import com.jqc.webplayer.core.StreamType
import com.jqc.webplayer.data.AppSettings
import com.jqc.webplayer.data.FavoriteEntity
import com.jqc.webplayer.data.HistoryEntity
import com.jqc.webplayer.data.WatchSessionEntity
import com.jqc.webplayer.ui.common.TimeFormat
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    history: List<HistoryEntity>,
    favorites: List<FavoriteEntity>,
    continueWatching: List<WatchSessionEntity>,
    clipboardUrl: String?,
    settings: AppSettings,
    onOpenUrl: (String) -> Unit,
    onPlayDirect: (String, StreamType) -> Unit,
    onPlayHistory: (HistoryEntity) -> Unit,
    onPlayFavorite: (FavoriteEntity) -> Unit,
    onResumeSession: (WatchSessionEntity) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteFavorite: (Long) -> Unit,
    onRenameFavorite: (FavoriteEntity, String) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FavoriteEntity?>(null) }
    var dismissedClipboard by rememberSaveable { mutableStateOf<String?>(null) }

    // 输入框也接受整段分享文案，只取其中第一个链接。
    val inputUrl = LinkExtractor.extract(input)
    val directType = inputUrl?.let { MediaUrlClassifier.detectType(it) }
    val clipboardType = clipboardUrl?.let { MediaUrlClassifier.detectType(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网页播放器") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("网页链接") },
                placeholder = { Text("粘贴或输入 http(s) 链接") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { inputUrl?.let(onOpenUrl) },
                    modifier = Modifier.weight(1f),
                    enabled = inputUrl != null,
                ) { Text("打开网页") }
                if (directType != null) {
                    // 粘贴的是 .mp4/.m3u8 直链时，给一个明确的原生播放入口。
                    OutlinedButton(
                        onClick = { inputUrl?.let { onPlayDirect(it, directType) } },
                        modifier = Modifier.weight(1f),
                    ) { Text("直接播放") }
                }
            }

            if (clipboardUrl != null && clipboardUrl != input && clipboardUrl != dismissedClipboard) {
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "剪贴板：$clipboardUrl",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (clipboardType != null) {
                            TextButton(onClick = { onPlayDirect(clipboardUrl, clipboardType) }) {
                                Text("播放")
                            }
                        }
                        TextButton(onClick = { onOpenUrl(clipboardUrl) }) { Text("打开") }
                        TextButton(onClick = { dismissedClipboard = clipboardUrl }) { Text("忽略") }
                    }
                }
            }

            // 记忆开关关闭时不展示，避免“卡片写着继续观看，点进去却不续播”的预期落差。
            if (settings.rememberProgress && continueWatching.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ContinueWatchingSection(
                    sessions = continueWatching,
                    onClick = onResumeSession,
                )
            }

            Spacer(Modifier.height(16.dp))
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("最近浏览") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("收藏") })
            }
            Spacer(Modifier.height(8.dp))

            if (tab == 0) {
                if (history.isEmpty()) {
                    EmptyHint("还没有浏览记录")
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showClearConfirm = true }) { Text("清空历史") }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(history, key = { it.id }) { item ->
                            HistoryRow(item, onClick = { onPlayHistory(item) }, onDelete = { onDeleteHistory(item.id) })
                        }
                    }
                }
            } else {
                if (favorites.isEmpty()) {
                    EmptyHint("还没有收藏")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(favorites, key = { it.id }) { item ->
                            FavoriteRow(
                                item = item,
                                onClick = { onPlayFavorite(item) },
                                onRename = { renameTarget = item },
                                onDelete = { onDeleteFavorite(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onChange = onSettingsChange,
            onDismiss = { showSettings = false },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空历史？") },
            text = { Text("将删除全部浏览和播放记录，收藏不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearHistory()
                    showClearConfirm = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    renameTarget?.let { favorite ->
        RenameFavoriteDialog(
            favorite = favorite,
            onConfirm = { newTitle ->
                onRenameFavorite(favorite, newTitle)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun ContinueWatchingSection(
    sessions: List<WatchSessionEntity>,
    onClick: (WatchSessionEntity) -> Unit,
) {
    Text(
        text = "继续观看",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    Card(Modifier.fillMaxWidth()) {
        Column {
            sessions.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider()
                ContinueWatchingRow(session, onClick = { onClick(session) })
            }
        }
    }
}

@Composable
private fun ContinueWatchingRow(session: WatchSessionEntity, onClick: () -> Unit) {
    val fraction = if (session.durationMs > 0) {
        (session.positionMs.toFloat() / session.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (fraction * 100).roundToInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "继续观看",
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(session.pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已看 $percent% · ${TimeFormat.duration(session.positionMs)} / " +
                    TimeFormat.duration(session.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "继续",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryRow(item: HistoryEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.pageTitle,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.videoUrl.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = "网页",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${item.siteHost} · ${TimeFormat.relative(item.updatedAt, System.currentTimeMillis())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.videoUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "点按回到该网页",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.durationMs > 0) {
                    Spacer(Modifier.height(6.dp))
                    val fraction = (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f)
                    val percent = (fraction * 100).roundToInt()
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${if (percent >= 95) "已看完" else "已看 $percent%"} · " +
                            "${TimeFormat.duration(item.positionMs)} / ${TimeFormat.duration(item.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    item: FavoriteEntity,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.pageUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "改名")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column {
                SettingSwitch("拦截网页弹窗", settings.blockPopups) { onChange(settings.copy(blockPopups = it)) }
                SettingSwitch("桌面版网页（UA）", settings.desktopUa) { onChange(settings.copy(desktopUa = it)) }
                SettingSwitch("优先推荐最佳清晰度", settings.preferBestQuality) { onChange(settings.copy(preferBestQuality = it)) }
                SettingSwitch("记忆播放进度", settings.rememberProgress) { onChange(settings.copy(rememberProgress = it)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RenameFavoriteDialog(
    favorite: FavoriteEntity,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(favorite.id) { mutableStateOf(favorite.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏改名") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
