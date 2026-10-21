package com.vidnest.app.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidnest.app.data.repository.VideoRepository
import com.vidnest.app.domain.model.Video
import com.vidnest.app.domain.model.VideoSortField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class FolderUiState(
    val folderPath: String = "",
    val folderName: String = "",
    val sort: VideoSortField = VideoSortField.NAME,
    val videos: List<Video> = emptyList(),
    val isLoading: Boolean = true,
)

class FolderViewModel(
    folderPath: String,
    private val videoRepository: VideoRepository,
) : ViewModel() {

    private val sort = MutableStateFlow(VideoSortField.NAME)

    private val videosFlow = sort.flatMapLatest { field ->
        videoRepository.observeFolderVideos(folderPath, field)
    }

    val uiState: StateFlow<FolderUiState> = combine(videosFlow, sort) { videos, currentSort ->
        FolderUiState(
            folderPath = folderPath,
            folderName = folderDisplayName(folderPath),
            sort = currentSort,
            videos = videos,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderUiState(folderPath = folderPath))

    fun onSortSelected(field: VideoSortField) {
        sort.value = field
    }

    private fun folderDisplayName(path: String): String =
        path.trimEnd('/').substringAfterLast('/', path)
}
