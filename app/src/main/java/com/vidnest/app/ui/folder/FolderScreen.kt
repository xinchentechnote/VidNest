package com.vidnest.app.ui.folder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vidnest.app.ServiceLocator
import com.vidnest.app.domain.model.FolderInfo
import com.vidnest.app.ui.components.SortMenuButton
import com.vidnest.app.ui.components.VideoListItem
import com.vidnest.app.util.Formatters

/**
 * 文件夹页：层级浏览——当前目录的直接子目录（计数含其全部子层级）+ 当前目录下的视频。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    folderPath: String,
    onBack: () -> Unit,
    onVideoClick: (Long) -> Unit = {},
    onVideoInfoClick: (Long) -> Unit = {},
    onSubFolderClick: (String) -> Unit = {},
) {
    val viewModel: FolderViewModel = viewModel(
        key = folderPath,
        factory = viewModelFactory {
            initializer {
                FolderViewModel(folderPath, ServiceLocator.videoRepository, ServiceLocator.settingsRepository)
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.folderName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级目录")
                    }
                },
                actions = {
                    SortMenuButton(current = state.sort, onSelect = viewModel::onSortSelected)
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.subFolders.isEmpty() && state.videos.isEmpty()) {
                Text(
                    text = "该目录暂无视频",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (state.subFolders.isNotEmpty()) {
                        item(key = "subfolders") {
                            Column {
                                Text(
                                    text = "子目录",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                SubFolderGrid(
                                    folders = state.subFolders,
                                    onFolderClick = onSubFolderClick,
                                )
                                if (state.videos.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "视频",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    items(state.videos, key = { it.id }) { video ->
                        VideoListItem(
                            video = video,
                            onClick = { onVideoClick(video.id) },
                            onInfoClick = { onVideoInfoClick(video.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 嵌套在 LazyColumn 里的网格：按行数固定高度（每行 2 列，禁用自身滚动交给外层） */
@Composable
private fun SubFolderGrid(
    folders: List<FolderInfo>,
    onFolderClick: (String) -> Unit,
) {
    val rows = ((folders.size + 1) / 2).coerceAtLeast(1)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxWidth().height((rows * 84).dp),
    ) {
        gridItems(folders, key = { it.path }) { folder ->
            SubFolderCard(folder = folder, onClick = { onFolderClick(folder.path) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubFolderCard(folder: FolderInfo, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (folder.displayName == "unknown") "未知目录" else folder.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${folder.videoCount} 个 · ${Formatters.fileSize(folder.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
