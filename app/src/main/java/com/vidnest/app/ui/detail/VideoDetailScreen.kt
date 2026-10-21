package com.vidnest.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vidnest.app.ServiceLocator
import com.vidnest.app.domain.model.WatchStatus
import com.vidnest.app.util.Formatters

/** 视频详情页：媒体信息 + 播放统计（次数/最近时间/断点/状态） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailScreen(
    videoId: Long,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val viewModel: VideoDetailViewModel = viewModel(
        key = "detail_$videoId",
        factory = viewModelFactory {
            initializer {
                VideoDetailViewModel(
                    videoId = videoId,
                    videoRepository = ServiceLocator.videoRepository,
                    recordRepository = ServiceLocator.playbackRecordRepository,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.video?.displayName ?: "视频详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val video = state.video
        if (video == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    if (state.isLoading) "加载中…" else "视频不存在或已被清理",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 播放入口
            Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayCircle, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = when {
                        video.playback?.completed == true -> "再次播放（已看完，将从头开始）"
                        (video.playback?.positionMs ?: 0) > 0 -> "继续播放（${Formatters.duration(video.playback?.positionMs ?: 0)}）"
                        else -> "播放"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // 播放统计
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("播放统计", style = MaterialTheme.typography.titleSmall)
                    StatRow("观看状态", video.watchStatus.label)
                    StatRow("播放次数", "${video.playback?.playCount ?: 0} 次")
                    StatRow(
                        "最近播放",
                        video.playback?.lastPlayedAtMs?.let { Formatters.dateTime(it) } ?: "从未播放",
                    )
                    StatRow(
                        "上次位置",
                        (video.playback?.positionMs ?: 0).takeIf { it > 0 }
                            ?.let { Formatters.duration(it) } ?: "--",
                    )
                    StatRow("完成状态", if (video.playback?.completed == true) "已看完" else "未看完")
                }
            }

            // 媒体信息
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("媒体信息", style = MaterialTheme.typography.titleSmall)
                    StatRow("所在目录", video.folderPath)
                    StatRow("文件大小", Formatters.fileSize(video.sizeBytes))
                    StatRow("时长", Formatters.duration(video.durationMs))
                    Formatters.resolution(video.width, video.height)
                        .takeIf { it.isNotEmpty() }
                        ?.let { StatRow("分辨率", it) }
                    StatRow("修改时间", Formatters.dateTime(video.dateModifiedSec, isSeconds = true))
                    if (video.isMissing) {
                        StatRow("可用状态", "文件不可访问（可能已被移动/删除）")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}
