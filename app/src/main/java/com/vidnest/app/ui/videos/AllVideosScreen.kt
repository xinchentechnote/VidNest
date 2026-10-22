package com.vidnest.app.ui.videos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vidnest.app.ServiceLocator
import com.vidnest.app.ui.components.SortMenuButton
import com.vidnest.app.ui.components.VideoListItem

/** 全部视频页：搜索 + 排序 + 观看状态筛选 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllVideosScreen(
    onVideoClick: (Long) -> Unit = {},
    onVideoInfoClick: (Long) -> Unit = {},
    onOpenFolderClick: (String) -> Unit = {},
) {
    val viewModel: AllVideosViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AllVideosViewModel(ServiceLocator.videoRepository, ServiceLocator.settingsRepository)
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全部") },
                actions = {
                    SortMenuButton(current = state.sort, onSelect = viewModel::onSortSelected)
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = { Text("搜索视频名称") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // 观看状态 Tab：全部 / 未播放 / 在看 / 已看完（含数量）
            TabRow(
                selectedTabIndex = state.filter.ordinal,
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusFilter.entries.forEach { f ->
                    val count = state.counts[f] ?: 0
                    Tab(
                        selected = state.filter == f,
                        onClick = { viewModel.onFilterSelected(f) },
                        text = { Text("${f.label} ($count)") },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (state.videos.isEmpty()) {
                    Text(
                        text = if (state.isFromSearch) "没有匹配“${state.query}”的视频" else "暂无视频",
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
                                onOpenFolderClick = { onOpenFolderClick(video.folderPath) },
                            )
                        }
                    }
                }
            }
        }
    }
}
