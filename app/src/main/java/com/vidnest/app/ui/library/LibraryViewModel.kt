package com.vidnest.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidnest.app.data.media.ScanManager
import com.vidnest.app.data.repository.VideoRepository
import com.vidnest.app.domain.model.FolderInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 库首页状态 */
data class LibraryUiState(
    val permissionUnknown: Boolean = true,
    val permissionGranted: Boolean = false,
    val folders: List<FolderInfo> = emptyList(),
    val totalVideos: Int = 0,
    val scanning: Boolean = false,
    val scanFoundSoFar: Int = 0,
    val lastScanSummary: String? = null,
    val scanError: String? = null,
)

class LibraryViewModel(
    private val videoRepository: VideoRepository,
    private val scanManager: ScanManager,
) : ViewModel() {

    private val permissionGranted = MutableStateFlow<Boolean?>(null)

    val uiState: StateFlow<LibraryUiState> = combine(
        videoRepository.observeFolders(),
        videoRepository.observeAvailableCount(),
        scanManager.state,
        permissionGranted,
    ) { folders, total, scan, granted ->
        val scanning: Boolean
        val foundSoFar: Int
        val summary: String?
        val error: String?
        when (scan) {
            is ScanManager.State.Scanning -> {
                scanning = true; foundSoFar = scan.foundSoFar; summary = null; error = null
            }
            is ScanManager.State.Completed -> {
                scanning = false; foundSoFar = 0; error = scan.result.failedSources
                    .takeIf { it.isNotEmpty() }?.joinToString("；")
                summary = "上次扫描：发现 ${scan.result.totalFound} 个视频，新增 ${scan.result.added}，" +
                    "更新 ${scan.result.updated}，失效 ${scan.result.missing}" +
                    if (scan.result.transferred > 0) "，迁移记录 ${scan.result.transferred}" else ""
            }
            is ScanManager.State.Failed -> {
                scanning = false; foundSoFar = 0; summary = null; error = scan.reason
            }
            ScanManager.State.Idle -> {
                scanning = false; foundSoFar = 0; summary = null; error = null
            }
        }
        LibraryUiState(
            permissionUnknown = granted == null,
            permissionGranted = granted == true,
            folders = folders,
            totalVideos = total,
            scanning = scanning,
            scanFoundSoFar = foundSoFar,
            lastScanSummary = summary,
            scanError = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** UI 在权限检查/授权回调后回报；授权后自动触发一次扫描 */
    fun onPermissionResolved(granted: Boolean) {
        val firstResolution = permissionGranted.value == null
        permissionGranted.value = granted
        if (granted && firstResolution) {
            scanManager.startScan(auto = true)
        }
    }

    fun rescan() = scanManager.startScan(auto = false)
}
