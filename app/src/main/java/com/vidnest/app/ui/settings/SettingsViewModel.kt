package com.vidnest.app.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidnest.app.data.media.SafVideoSource
import com.vidnest.app.data.repository.VideoRepository
import com.vidnest.app.data.settings.SettingsRepository
import com.vidnest.app.domain.model.EndBehavior
import com.vidnest.app.domain.model.Speeds
import com.vidnest.app.domain.model.ResumeBehavior
import com.vidnest.app.domain.model.VideoSortField
import com.vidnest.app.util.MediaPermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SafFolderUi(
    val treeUri: String,
    val displayName: String,
    val accessible: Boolean,
)

data class SettingsUiState(
    val permissionGranted: Boolean = false,
    val defaultSort: VideoSortField = VideoSortField.NAME,
    val resumeBehavior: ResumeBehavior = ResumeBehavior.ALWAYS,
    val endBehavior: EndBehavior = EndBehavior.FROM_START,
    val rememberSpeed: Boolean = true,
    val speedOptions: List<Float> = Speeds.DEFAULT,
    val safFolders: List<SafFolderUi> = emptyList(),
    val missingCount: Int = 0,
    val versionName: String = "",
)

class SettingsViewModel(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val videoRepository: VideoRepository,
    private val rescanTrigger: () -> Unit,
) : ViewModel() {

    private val permission = MutableStateFlow(false)
    private val missingCount = MutableStateFlow(0)
    private val version = MutableStateFlow("")

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.defaultSort,
        settingsRepository.resumeBehavior,
        settingsRepository.endBehavior,
        settingsRepository.rememberSpeed,
        settingsRepository.speedOptions,
        combine(settingsRepository.safTrees, permission, missingCount, version) { trees, perm, missing, ver ->
            SafState(trees, perm, missing, ver)
        },
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val sort = values[0] as VideoSortField
        val resume = values[1] as ResumeBehavior
        val end = values[2] as EndBehavior
        val rememberSpeed = values[3] as Boolean
        val speedOptions = values[4] as List<Float>
        val saf = values[5] as SafState
        SettingsUiState(
            permissionGranted = saf.perm,
            defaultSort = sort,
            resumeBehavior = resume,
            endBehavior = end,
            rememberSpeed = rememberSpeed,
            speedOptions = speedOptions,
            safFolders = saf.trees.map { tree ->
                val uri = Uri.parse(tree)
                SafFolderUi(
                    treeUri = tree,
                    displayName = safDisplayName(tree),
                    accessible = isTreeAccessible(uri),
                )
            },
            missingCount = saf.missing,
            versionName = saf.ver,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refresh()
    }

    fun refresh() {
        permission.value = MediaPermissions.isGranted(context)
        version.value = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "--"
        } catch (_: PackageManager.NameNotFoundException) {
            "--"
        }
        viewModelScope.launch {
            missingCount.value = runCatching { videoRepository.countMissing() }.getOrDefault(0)
        }
    }

    fun rescan() = rescanTrigger()

    fun setDefaultSort(value: VideoSortField) = viewModelScope.launch {
        settingsRepository.setDefaultSort(value)
    }

    fun setResumeBehavior(value: ResumeBehavior) = viewModelScope.launch {
        settingsRepository.setResumeBehavior(value)
    }

    fun setEndBehavior(value: EndBehavior) = viewModelScope.launch {
        settingsRepository.setEndBehavior(value)
    }

    fun setRememberSpeed(value: Boolean) = viewModelScope.launch {
        settingsRepository.setRememberSpeed(value)
    }

    fun setSpeedOptions(options: List<Float>) = viewModelScope.launch {
        settingsRepository.setSpeedOptions(options)
    }

    /** 保存 SAF 授权并持久化权限（docs/permissions-and-media-scanning.md §2.1） */
    fun onTreeUriGranted(treeUri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                settingsRepository.addSafTree(treeUri.toString())
            }
            missingCount.value = runCatching { videoRepository.countMissing() }.getOrDefault(0)
        }
    }

    /** 移除授权目录：删除该 volume 的索引与播放记录（显式操作，不涉及原始文件） */
    fun removeSafFolder(treeUri: String) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.removeSafTree(treeUri)
                videoRepository.deleteByVolume(SafVideoSource.volumeName(Uri.parse(treeUri)))
                releasePersistablePermission(Uri.parse(treeUri))
            }
            missingCount.value = runCatching { videoRepository.countMissing() }.getOrDefault(0)
        }
    }

    /** 清理失效索引：仅删除 MISSING 行及级联记录，不触碰原始文件 */
    fun cleanupMissingIndex(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val removed = runCatching { videoRepository.deleteMissing() }.getOrDefault(0)
            missingCount.value = runCatching { videoRepository.countMissing() }.getOrDefault(0)
            onDone(removed)
        }
    }

    private fun releasePersistablePermission(treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun isTreeAccessible(uri: Uri): Boolean = runCatching {
        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.canRead() == true
    }.getOrDefault(false)

    private fun safDisplayName(tree: String): String = runCatching {
        val uri = Uri.parse(tree)
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val path = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        path.lastPathSegment?.substringAfterLast('/') ?: tree
    }.getOrDefault(tree)

    private data class SafState(
        val trees: Set<String>,
        val perm: Boolean,
        val missing: Int,
        val ver: String,
    )
}
