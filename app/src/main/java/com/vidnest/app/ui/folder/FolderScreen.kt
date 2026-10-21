package com.vidnest.app.ui.folder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vidnest.app.ServiceLocator
import com.vidnest.app.ui.components.SortMenuButton
import com.vidnest.app.ui.components.VideoListItem

/** 文件夹详情页：该目录下的视频列表 + 排序 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    folderPath: String,
    onBack: () -> Unit,
    onVideoClick: (Long) -> Unit = {},
    onVideoInfoClick: (Long) -> Unit = {},
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            if (state.videos.isEmpty()) {
                Text(
                    text = "该目录暂无视频",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn {
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
