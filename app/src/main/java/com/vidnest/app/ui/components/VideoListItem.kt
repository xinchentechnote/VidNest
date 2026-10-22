package com.vidnest.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vidnest.app.domain.model.Video
import com.vidnest.app.domain.model.WatchStatus
import com.vidnest.app.util.Formatters

/**
 * 视频列表项（文件夹页/全部视频页共用）。
 * 明确展示：时长、大小、分辨率 + 已播放次数 + 观看状态。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VideoListItem(
    video: Video,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onInfoClick: (() -> Unit)? = null,
    onOpenFolderClick: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let {
                if (onClick != null || onInfoClick != null || onOpenFolderClick != null) {
                    it.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = { menuExpanded = true },
                    )
                } else {
                    it
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 长按菜单：播放 / 查看详情 / 打开所在文件夹
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("播放") },
                leadingIcon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onClick?.invoke()
                },
            )
            if (onInfoClick != null) {
                DropdownMenuItem(
                    text = { Text("查看详情") },
                    leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onInfoClick.invoke()
                    },
                )
            }
            if (onOpenFolderClick != null) {
                DropdownMenuItem(
                    text = { Text("打开所在文件夹") },
                    leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenFolderClick.invoke()
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 56.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 元信息：时长 · 大小 · 分辨率
            val resolution = Formatters.resolution(video.width, video.height)
            val meta = listOfNotNull(
                Formatters.durationOrUnknown(video.durationMs),
                Formatters.fileSize(video.sizeBytes),
                resolution.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 统计行：状态 · 已播放次数 · 上次位置
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WatchStatusChip(video)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "已播放 ${video.playback?.playCount ?: 0} 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (video.watchStatus == WatchStatus.WATCHING && video.playback != null) {
                    Text(
                        text = "已看 ${Formatters.duration(video.playback.positionMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchStatusChip(video: Video) {
    val (label, color, onColor) = when (video.watchStatus) {
        WatchStatus.NEVER -> null
        WatchStatus.WATCHING -> Triple(
            "在看",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        WatchStatus.COMPLETED -> Triple(
            "已看完",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
    } ?: return
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = onColor)
    }
}
