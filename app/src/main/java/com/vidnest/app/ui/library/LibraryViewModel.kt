package com.vidnest.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidnest.app.domain.model.FolderInfo
import com.vidnest.app.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 库首页状态：权限 / 文件夹 / 扫描（扫描器在阶段3接入） */
data class LibraryUiState(
    val permissionUnknown: Boolean = true,
    val permissionGranted: Boolean = false,
    val folders: List<FolderInfo> = emptyList(),
    val isLoading: Boolean = true,
)

class LibraryViewModel(
    private val videoRepository: VideoRepository,
) : ViewModel() {

    private val permissionGranted = MutableStateFlow<Boolean?>(null)

    val uiState: StateFlow<LibraryUiState> = combine(
        videoRepository.observeFolders(),
        permissionGranted,
    ) { folders, granted ->
        LibraryUiState(
            permissionUnknown = granted == null,
            permissionGranted = granted == true,
            folders = folders,
            isLoading = granted == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** UI 在权限检查/授权回调后回报 */
    fun onPermissionResolved(granted: Boolean) {
        permissionGranted.value = granted
    }
}
