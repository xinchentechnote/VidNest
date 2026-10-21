package com.vidnest.app.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidnest.app.data.repository.VideoRepository
import com.vidnest.app.data.settings.SettingsRepository
import com.vidnest.app.domain.model.FolderInfo
import com.vidnest.app.domain.model.Video
import com.vidnest.app.domain.model.VideoSortField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FolderUiState(
    val folderPath: String = "",
    val folderName: String = "",
    val sort: VideoSortField = VideoSortField.NAME,
    val subFolders: List<FolderInfo> = emptyList(),
    val videos: List<Video> = emptyList(),
    val isLoading: Boolean = true,
)

class FolderViewModel(
    folderPath: String,
    private val videoRepository: VideoRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val sort = MutableStateFlow(VideoSortField.NAME)

    init {
        // 应用默认排序偏好（设置页可改）
        viewModelScope.launch { sort.value = settingsRepository.defaultSort.first() }
    }

    private val videosFlow = sort.flatMapLatest { field ->
        videoRepository.observeFolderVideos(folderPath, field)
    }

    private val subFoldersFlow = videoRepository.observeChildFolders(folderPath)

    val uiState: StateFlow<FolderUiState> =
        combine(videosFlow, subFoldersFlow, sort) { videos, subFolders, currentSort ->
            FolderUiState(
                folderPath = folderPath,
                folderName = folderDisplayName(folderPath),
                sort = currentSort,
                subFolders = subFolders,
                videos = videos,
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderUiState(folderPath = folderPath))

    fun onSortSelected(field: VideoSortField) {
        sort.value = field
    }

    private fun folderDisplayName(path: String): String {
        val segment = path.trimEnd('/').substringAfterLast('/', path)
        return if (segment == UNKNOWN_SEGMENT) "未知目录" else segment
    }

    companion object {
        const val UNKNOWN_SEGMENT = "unknown"
    }
}
