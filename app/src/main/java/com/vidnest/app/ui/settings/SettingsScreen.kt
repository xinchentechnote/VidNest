package com.vidnest.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vidnest.app.ServiceLocator
import com.vidnest.app.domain.model.EndBehavior
import com.vidnest.app.domain.model.ResumeBehavior
import com.vidnest.app.domain.model.Speeds
import com.vidnest.app.domain.model.VideoSortField

/** 设置页：扫描/权限/排序/播放行为/SAF 目录/清理索引/版本 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    context = context.applicationContext,
                    settingsRepository = ServiceLocator.settingsRepository,
                    videoRepository = ServiceLocator.videoRepository,
                    rescanTrigger = { ServiceLocator.scanManager.startScan(auto = false) },
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showSortDialog by remember { mutableStateOf(false) }
    var showSpeedListDialog by remember { mutableStateOf(false) }
    var showResumeDialog by remember { mutableStateOf(false) }
    var showEndDialog by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var cleanupResult by remember { mutableStateOf<Int?>(null) }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.onTreeUriGranted(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // —— 扫描与权限 ——
            SettingsCard(title = "扫描与权限") {
                ActionRow(title = "重新扫描视频", subtitle = "全量检查 MediaStore 与已授权目录", onClick = {
                    viewModel.rescan()
                    onBack()
                })
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("媒体访问权限", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (state.permissionGranted) "已授予视频读取权限" else "未授权，无法扫描设备视频",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = if (state.permissionGranted) "已授权" else "未授权",
                        color = if (state.permissionGranted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // —— SAF 授权目录 ——
            SettingsCard(title = "授权目录（可选）") {
                Text(
                    "系统媒体库覆盖不到的目录（如 Download 子目录），可通过此入口授权访问。"
                        + "仅读取视频，不会修改任何文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.safFolders.forEach { folder ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(folder.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (folder.accessible) "授权有效" else "授权已失效，请移除后重新添加",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (folder.accessible) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        IconButton(onClick = { viewModel.removeSafFolder(folder.treeUri) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "移除授权目录")
                        }
                    }
                }
                TextButton(onClick = { treePicker.launch(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加文件夹授权")
                }
            }

            // —— 播放与排序 ——
            SettingsCard(title = "播放与排序") {
                ActionRow(
                    title = "默认排序方式",
                    subtitle = state.defaultSort.label,
                    onClick = { showSortDialog = true },
                )
                HorizontalDivider()
                ActionRow(
                    title = "断点恢复行为",
                    subtitle = state.resumeBehavior.label,
                    onClick = { showResumeDialog = true },
                )
                HorizontalDivider()
                ActionRow(
                    title = "播放结束后再次打开",
                    subtitle = state.endBehavior.label,
                    onClick = { showEndDialog = true },
                )
                HorizontalDivider()
                ActionRow(
                    title = "倍速列表",
                    subtitle = state.speedOptions.joinToString(" · ") { Speeds.formatSpeed(it) },
                    onClick = { showSpeedListDialog = true },
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("记住播放倍速", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "下次播放时使用上次选择的倍速（视频不会锁定倍速）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.rememberSpeed, onCheckedChange = viewModel::setRememberSpeed)
                }
            }

            // —— 计数规则说明 ——
            SettingsCard(title = "播放次数统计规则") {
                Text(
                    "一次有效播放会话累计一次播放次数：实际播放满一定时长"
                        + "（通常 10 秒，短视频为其时长的三分之一）才会计入。"
                        + "暂停、拖动进度、切换倍速不会额外计数；"
                        + "旋转屏幕或播放器重建不会重复计数；"
                        + "应用被系统结束后继续观看会延续之前的会话。"
                        + "已看完的视频重新播放会再次计数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "播放结束的约定：视频播放到结尾会被标记为“已看完”，下次打开默认从头播放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // —— 维护 ——
            SettingsCard(title = "维护") {
                ActionRow(
                    title = "清理失效索引",
                    subtitle = if (state.missingCount > 0) {
                        "${state.missingCount} 个视频文件已不可访问，可清理其索引（不会删除原始文件）"
                    } else {
                        "当前没有失效的索引"
                    },
                    onClick = { if (state.missingCount > 0) showCleanupDialog = true },
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("应用版本", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        state.versionName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSortDialog) {
        RadioDialog(
            title = "默认排序方式",
            options = VideoSortField.entries.associate { it to it.label },
            selected = state.defaultSort,
            onSelect = {
                viewModel.setDefaultSort(it)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false },
        )
    }
    if (showResumeDialog) {
        RadioDialog(
            title = "断点恢复行为",
            options = ResumeBehavior.entries.associate { it to it.label },
            selected = state.resumeBehavior,
            onSelect = {
                viewModel.setResumeBehavior(it)
                showResumeDialog = false
            },
            onDismiss = { showResumeDialog = false },
        )
    }
    if (showSpeedListDialog) {
        SpeedOptionsDialog(
            current = state.speedOptions,
            onConfirm = { options ->
                viewModel.setSpeedOptions(options)
                showSpeedListDialog = false
            },
            onDismiss = { showSpeedListDialog = false },
        )
    }
    if (showEndDialog) {
        RadioDialog(
            title = "播放结束后再次打开",
            options = EndBehavior.entries.associate { it to it.label },
            selected = state.endBehavior,
            onSelect = {
                viewModel.setEndBehavior(it)
                showEndDialog = false
            },
            onDismiss = { showEndDialog = false },
        )
    }
    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { showCleanupDialog = false },
            title = { Text("清理失效索引") },
            text = {
                Text("将删除 ${state.missingCount} 个不可访问视频的索引与播放记录。" +
                    "手机里的原始视频文件不会被删除或修改。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showCleanupDialog = false
                    viewModel.cleanupMissingIndex { removed -> cleanupResult = removed }
                }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupDialog = false }) { Text("取消") }
            },
        )
    }
    cleanupResult?.let { removed ->
        AlertDialog(
            onDismissRequest = { cleanupResult = null },
            title = { Text("清理完成") },
            text = { Text("已清理 $removed 条失效索引。") },
            confirmButton = {
                TextButton(onClick = { cleanupResult = null }) { Text("好的") }
            },
        )
    }
}

/** 倍速列表编辑：从全集多选（升序展示），至少保留一个 */
@Composable
private fun SpeedOptionsDialog(
    current: List<Float>,
    onConfirm: (List<Float>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember {
        mutableStateOf(
            Speeds.UNIVERSE.filter { u -> current.any { kotlin.math.abs(it - u) < 0.001f } }.toSet(),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("倍速列表") },
        text = {
            Column {
                Text(
                    "勾选播放页可用的倍速（按升序显示，至少保留一个）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Speeds.UNIVERSE.forEach { speed ->
                    val checked = selected.any { kotlin.math.abs(it - speed) < 0.001f }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) {
                                    selected - speed
                                } else {
                                    selected + speed
                                }
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selected = if (checked) selected - speed else selected + speed
                            },
                        )
                        Text(Speeds.formatSpeed(speed))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(selected.sorted()) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun <T> RadioDialog(
    title: String,
    options: Map<T, String>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
