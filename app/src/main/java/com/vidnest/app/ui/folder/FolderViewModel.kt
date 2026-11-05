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
    private val initialPath: String,
    private val videoRepository: VideoRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val sort = MutableStateFlow(VideoSortField.NAME)

    /** 实际生效的目录路径（自愈兜底可能重定位） */
    private val effectivePath = MutableStateFlow(initialPath)

    init {
        // 应用默认排序偏好（设置页可改）
        viewModelScope.launch { sort.value = settingsRepository.defaultSort.first() }
        // 自愈兜底：传入路径与数据库不一致（历史解码 bug / ROM 路径差异）时，
        // 按目录名重新定位真实路径，页面不再显示为空
        viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            val videos = videosFlow.first()
            val subs = subFoldersFlow.first()
            if (videos.isEmpty() && subs.isEmpty()) {
                val name = folderDisplayName(initialPath)
                if (name != UNKNOWN_SEGMENT && name.isNotBlank()) {
                    val alt = videoRepository.findFolderPathByName(name)
                    if (alt != null && alt != initialPath) {
                        effectivePath.value = alt
                    }
                }
            }
        }
    }

    private val videosFlow = combine(effectivePath, sort) { p, s -> p to s }
        .flatMapLatest { (p, s) -> videoRepository.observeFolderVideos(p, s) }

    private val subFoldersFlow = effectivePath.flatMapLatest { p ->
        videoRepository.observeChildFolders(p)
    }

    val uiState: StateFlow<FolderUiState> =
        combine(videosFlow, subFoldersFlow, sort, effectivePath) { videos, subFolders, currentSort, path ->
            FolderUiState(
                folderPath = path,
                folderName = folderDisplayName(path),
                sort = currentSort,
                subFolders = subFolders,
                videos = videos,
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderUiState(folderPath = initialPath))

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
