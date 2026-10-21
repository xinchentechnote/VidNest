package com.vidnest.app.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.VideoRepository
import com.vidnest.app.domain.model.PlaybackInfo
import com.vidnest.app.domain.model.Video
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class VideoDetailUiState(
    val video: Video? = null,
    val playback: PlaybackInfo? = null,
    val isLoading: Boolean = true,
)

class VideoDetailViewModel(
    private val videoId: Long,
    private val videoRepository: VideoRepository,
    recordRepository: PlaybackRecordRepository,
) : ViewModel() {

    private val videoFlow = flow {
        emit(videoRepository.getVideo(videoId))
    }

    val uiState: StateFlow<VideoDetailUiState> = combine(
        videoFlow,
        recordRepository.observe(videoId),
    ) { video, playback ->
        VideoDetailUiState(video = video, playback = playback, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoDetailUiState())
}
