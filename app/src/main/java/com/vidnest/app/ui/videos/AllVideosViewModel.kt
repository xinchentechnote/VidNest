package com.vidnest.app.ui.videos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidnest.app.data.repository.VideoRepository
import com.vidnest.app.data.settings.SettingsRepository
import com.vidnest.app.domain.model.Video
import com.vidnest.app.domain.model.VideoSortField
import com.vidnest.app.domain.model.WatchStatus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class StatusFilter(val label: String) {
    ALL("全部"),
    NEVER("未播放"),
    WATCHING("在看"),
    COMPLETED("已看完"),
    ;

    fun matches(video: Video): Boolean = when (this) {
        ALL -> true
        NEVER -> video.watchStatus == WatchStatus.NEVER
        WATCHING -> video.watchStatus == WatchStatus.WATCHING
        COMPLETED -> video.watchStatus == WatchStatus.COMPLETED
    }
}

data class AllVideosUiState(
    val query: String = "",
    val sort: VideoSortField = VideoSortField.NAME,
    val filter: StatusFilter = StatusFilter.ALL,
    val videos: List<Video> = emptyList(),
    val isFromSearch: Boolean = false,
)

class AllVideosViewModel(
    private val videoRepository: VideoRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(VideoSortField.LAST_PLAYED)
    private val filter = MutableStateFlow(StatusFilter.ALL)

    init {
        // 应用默认排序偏好（设置页可改）
        viewModelScope.launch { sort.value = settingsRepository.defaultSort.first() }
    }

    @OptIn(FlowPreview::class)
    private val videosFlow = combine(
        query.debounce(300).distinctUntilChanged(),
        sort,
    ) { q, s -> q to s }
        .flatMapLatest { (q, s) -> videoRepository.observeAllVideos(q.ifBlank { null }, s) }

    val uiState: StateFlow<AllVideosUiState> = combine(
        videosFlow,
        query,
        sort,
        filter,
    ) { videos, q, s, f ->
        AllVideosUiState(
            query = q,
            sort = s,
            filter = f,
            videos = videos.filter { f.matches(it) },
            isFromSearch = q.isNotBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllVideosUiState())

    fun onQueryChanged(value: String) {
        query.value = value
    }

    fun onSortSelected(field: VideoSortField) {
        sort.value = field
    }

    fun onFilterSelected(filter: StatusFilter) {
        this.filter.value = filter
    }
}
