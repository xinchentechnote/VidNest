package com.vidnest.app.ui.library

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vidnest.app.R
import com.vidnest.app.ServiceLocator
import com.vidnest.app.util.Formatters
import com.vidnest.app.util.MediaPermissions

/** 库首页：权限门控 + 文件夹网格（阶段3接入扫描后展示真实数据） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onFolderClick: (String) -> Unit,
    viewModel: LibraryViewModel =
        viewModel(factory = LibraryViewModelFactory.provide()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onPermissionResolved(result.values.all { it })
    }

    LaunchedEffect(Unit) {
        viewModel.onPermissionResolved(MediaPermissions.isGranted(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.permissionUnknown -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                !state.permissionGranted -> PermissionRationale(
                    modifier = Modifier.align(Alignment.Center),
                    onRequest = { permissionLauncher.launch(MediaPermissions.requiredPermissions()) },
                )

                else -> FolderGrid(
                    folders = state.folders,
                    onFolderClick = onFolderClick,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun PermissionRationale(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("需要访问视频文件", style = MaterialTheme.typography.titleMedium)
        Text(
            "VidNest 是完全离线的本地视频播放器，仅读取设备上的视频列表用于浏览和播放，"
                + "不会修改或上传任何文件。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) {
            Text("授权视频访问")
        }
    }
}

@Composable
fun FolderGrid(
    folders: List<com.vidnest.app.domain.model.FolderInfo>,
    onFolderClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "暂无视频\n授权后将自动扫描设备中的视频",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(folders, key = { it.path }) { folder ->
            FolderCard(folder = folder, onClick = { onFolderClick(folder.path) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderCard(
    folder: com.vidnest.app.domain.model.FolderInfo,
    onClick: () -> Unit,
) {
    Card(onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${folder.videoCount} 个视频 · ${Formatters.fileSize(folder.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
