package com.vidnest.app.ui.player

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vidnest.app.ServiceLocator
import com.vidnest.app.util.Formatters
import kotlinx.coroutines.delay

/** 支持的倍速档位 */
val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

@Composable
fun PlayerScreen(
    videoId: Long,
    onBack: () -> Unit,
) {
    val viewModel: PlayerViewModel = viewModel(
        key = "player_$videoId",
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    videoId = videoId,
                    videoRepository = ServiceLocator.videoRepository,
                    recordRepository = ServiceLocator.playbackRecordRepository,
                    sessionRepository = ServiceLocator.playbackSessionRepository,
                    writer = ServiceLocator.progressWriter,
                    controllerContext = ServiceLocator.appContext,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 沉浸式：隐藏系统栏；播放时保持屏幕常亮
    val window = (context as? Activity)?.window
    DisposableEffect(Unit) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 生命周期停止：暂停并保存进度
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.onLifecycleStopped()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 离开页面：flush 断点并释放播放器
    DisposableEffect(Unit) {
        onDispose { viewModel.onDisposed() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            state.videoUnavailable -> UnavailableUi(state.video?.displayName, onBack)
            else -> PlayerContent(
                state = state,
                viewModel = viewModel,
                onBack = onBack,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

    // 播放中 3.5s 无操作自动隐藏控制层
    LaunchedEffect(state.isPlaying, controlsVisible) {
        if (controlsVisible && state.isPlaying) {
            delay(3_500)
            controlsVisible = false
        }
    }

    // 断点恢复提示（≥15s），5 秒后自动消失
    var showResumeHint by remember { mutableStateOf(false) }
    LaunchedEffect(state.resumeHintMs) {
        state.resumeHintMs?.let {
            showResumeHint = true
            delay(5_000)
            showResumeHint = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
    ) {
        // 视频画面
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view -> view.player = viewModel.controller.player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // 加载中
        if (state.isBuffering && state.error == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        // 错误态
        state.error?.let { error ->
            ErrorOverlay(
                message = error.message,
                onRetry = viewModel::retry,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 恢复断点提示
        if (showResumeHint && state.resumeHintMs != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 80.dp),
            ) {
                Text("已恢复到上次位置 ${Formatters.duration(state.resumeHintMs!!)}")
            }
        }

        // 控制层
        if (controlsVisible && state.error == null) {
            ControlsOverlay(
                state = state,
                dragging = dragging,
                dragPosition = dragPosition,
                onTogglePlay = viewModel::togglePlay,
                onSeekTo = { ms ->
                    viewModel.seekTo(ms)
                },
                onSeekBy = viewModel::seekBy,
                onDrag = {
                    dragging = true
                    dragPosition = it
                },
                onDragEnd = {
                    dragging = false
                    viewModel.seekTo(dragPosition.toLong())
                },
                onSpeedClick = { showSpeedSheet = true },
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showSpeedSheet) {
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            SpeedSheetContent(
                currentSpeed = state.speed,
                onSelect = { speed ->
                    viewModel.setSpeed(speed)
                    showSpeedSheet = false
                },
            )
        }
    }
}

@Composable
private fun ControlsOverlay(
    state: PlayerUiState,
    dragging: Boolean,
    dragPosition: Float,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onSpeedClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // 顶部：返回 + 视频名
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出播放器", tint = Color.White)
            }
            Text(
                text = state.video?.displayName ?: "",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        // 中央：播放/暂停 + 快进快退
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onClick = { onSeekBy(-10_000) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.FastRewind, contentDescription = "快退10秒", tint = Color.White)
            }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                ),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            FilledIconButton(onClick = { onSeekBy(10_000) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.FastForward, contentDescription = "快进10秒", tint = Color.White)
            }
        }

        // 底部：进度条 + 时间 + 倍速 + 旋转
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            val position = if (dragging) dragPosition.toLong() else state.positionMs
            val duration = state.durationMs.coerceAtLeast(0)
            Slider(
                value = if (duration > 0) {
                    (position.toFloat() / duration).coerceIn(0f, 1f)
                } else {
                    0f
                },
                onValueChange = { fraction ->
                    onDrag(fraction * duration)
                },
                onValueChangeFinished = onDragEnd,
                enabled = duration > 0,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${Formatters.duration(position)} / ${Formatters.duration(duration)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSpeedClick) {
                    Icon(Icons.Filled.Speed, contentDescription = "倍速", tint = Color.White)
                    // 当前倍速角标
                }
                Text(
                    text = formatSpeed(state.speed),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun SpeedSheetContent(currentSpeed: Float, onSelect: (Float) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("播放倍速", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // 3 行 3 列网格排布
            Column {
                SPEED_OPTIONS.chunked(3).forEach { rowOptions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowOptions.forEach { speed ->
                            val selected = kotlin.math.abs(speed - currentSpeed) < 0.01f
                            FilledTonalButton(
                                onClick = { onSelect(speed) },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                ),
                            ) {
                                Text(formatSpeed(speed))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

@Composable
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledIconButton(onClick = onRetry) {
                Text("重试", color = Color.White)
            }
            FilledIconButton(onClick = onBack) {
                Text("返回", color = Color.White)
            }
        }
    }
}

@Composable
private fun UnavailableUi(videoName: String?, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "视频无法访问",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Text(
            text = (videoName ?: "该视频") + " 可能已被移动、删除，或缺少访问权限。\n"
                + "可在设置中重新扫描以更新索引（不会删除任何原始文件）。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        FilledIconButton(onClick = onBack) {
            Text("返回", color = Color.White)
        }
    }
}
