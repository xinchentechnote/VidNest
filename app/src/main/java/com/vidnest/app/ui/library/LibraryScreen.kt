package com.vidnest.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.vidnest.app.ui.components.FolderRowItem
import com.vidnest.app.ui.components.ScanStatusBanner

/** 文件夹页（底部 Tab「文件夹」）：根目录紧凑列表 + 扫描状态 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onFolderClick: (String) -> Unit,
    onSubFolderClick: (String) -> Unit = onFolderClick,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModelFactory.provide()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文件夹") },
                actions = {
                    IconButton(onClick = { viewModel.rescan() }) {
                        Icon(Icons.Filled.ManageSearch, contentDescription = "重新扫描")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.scanning -> ScanStatusBanner(
                    text = "正在扫描设备视频…已发现 ${state.scanFoundSoFar} 个",
                    progress = true,
                )
                state.scanError != null -> ScanStatusBanner(
                    text = "扫描出现问题：${state.scanError}",
                    isError = true,
                )
                state.lastScanSummary != null -> ScanStatusBanner(text = state.lastScanSummary!!)
            }
            if (state.folders.isEmpty() && !state.scanning) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无视频\n授权后将自动扫描设备中的视频",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.folders, key = { it.path }) { folder ->
                        FolderRowItem(
                            folder = folder,
                            onClick = { onFolderClick(folder.path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScanStatusBanner(text: String, isError: Boolean = false, progress: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}
