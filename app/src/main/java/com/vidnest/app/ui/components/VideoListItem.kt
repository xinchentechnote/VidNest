package com.vidnest.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.vidnest.app.domain.model.FolderInfo
import com.vidnest.app.domain.model.Video
import com.vidnest.app.domain.model.WatchStatus
import com.vidnest.app.util.Formatters

/**
 * 紧凑文件夹行：一行一个目录（名称 + 数量/大小右对齐），小行距。
 */
@Composable
fun FolderRowItem(
    folder: FolderInfo,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = if (folder.displayName == "unknown") "未知目录" else folder.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${folder.videoCount} 个 · ${Formatters.fileSize(folder.totalBytes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 紧凑视频行：一行一个视频（缩略占位 + 名称 + 单行元信息），小行距。
 * 元信息：状态 · 时长 · 大小 · 已播放 N 次 · 已看 mm:ss
 * 长按菜单：播放 / 查看详情 / 打开所在文件夹
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 32.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.displayName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WatchStatusTag(video.watchStatus)
                Text(
                    text = watchMetaLine(video),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

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
}

/** 单行元信息：时长 · 大小 · 已播放 N 次（在看时附已看时长） */
fun watchMetaLine(video: Video): String {
    val parts = buildList {
        add(Formatters.durationOrUnknown(video.durationMs))
        add(Formatters.fileSize(video.sizeBytes))
        add("已播放 ${video.playback?.playCount ?: 0} 次")
        if (video.watchStatus == WatchStatus.WATCHING && (video.playback?.positionMs ?: 0) > 0) {
            add("已看 ${Formatters.duration(video.playback!!.positionMs)}")
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun WatchStatusTag(status: WatchStatus) {
    val (label, color, onColor) = when (status) {
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
            .background(color, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = onColor)
    }
}
