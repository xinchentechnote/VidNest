package com.vidnest.app.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import com.vidnest.app.data.repository.VideoRepository
import com.vidnest.app.domain.model.Video
import com.vidnest.app.domain.policy.ProgressPolicy
import com.vidnest.app.playback.PlaybackCoordinator
import com.vidnest.app.playback.PlayerController
import com.vidnest.app.playback.ProgressWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 播放器错误信息（面向用户） */
data class PlayerErrorInfo(
    val message: String,
    val isFileMissing: Boolean,
)

data class PlayerUiState(
    val video: Video? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val ended: Boolean = false,
    val error: PlayerErrorInfo? = null,
    val resumeHintMs: Long? = null,
    val videoUnavailable: Boolean = false,
)

class PlayerViewModel(
    private val videoId: Long,
    private val videoRepository: VideoRepository,
    private val recordRepository: PlaybackRecordRepository,
    private val sessionRepository: PlaybackSessionRepository,
    private val writer: ProgressWriter,
    controllerContext: Context,
) : ViewModel() {

    val controller = PlayerController(controllerContext)

    private val coordinator = PlaybackCoordinator(recordRepository, sessionRepository, writer)

    private val videoState = MutableStateFlow<Video?>(null)
    private val resumeHint = MutableStateFlow<Long?>(null)
    private val videoUnavailable = MutableStateFlow(false)
    private val playerError = MutableStateFlow<PlayerErrorInfo?>(null)

    private var endedHandled = false

    val uiState: StateFlow<PlayerUiState> = combine(
        videoState,
        controller.state,
        resumeHint,
        videoUnavailable,
        playerError,
    ) { video, cs, hint, unavailable, error ->
        PlayerUiState(
            video = video,
            isPlaying = cs.isPlaying,
            isBuffering = cs.isBuffering,
            positionMs = cs.positionMs,
            durationMs = cs.durationMs,
            speed = cs.speed,
            ended = cs.ended,
            error = error,
            resumeHintMs = hint,
            videoUnavailable = unavailable,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    init {
        controller.tickListener = ::onControllerTick

        viewModelScope.launch {
            val video = videoRepository.getVideo(videoId)
            if (video == null) {
                videoUnavailable.value = true
                return@launch
            }
            if (video.isMissing) {
                videoState.value = video
                videoUnavailable.value = true
                return@launch
            }
            videoState.value = video
            val record = recordRepository.get(videoId)
            val resumePosition = ProgressPolicy.resolveResumePosition(
                savedPositionMs = record?.positionMs ?: 0,
                savedDurationMs = record?.durationMs ?: 0,
                mediaDurationMs = video.durationMs,
                completed = record?.completed == true,
            )
            // ≥15s 才提示已恢复，避免短视频噪音
            resumeHint.value = resumePosition.takeIf { it >= RESUME_HINT_THRESHOLD_MS }
            coordinator.begin(videoId, resumePosition)
            controller.load(video.contentUri, resumePosition, speed = DEFAULT_SPEED)
        }

        // 播放结束：断点按约定归零并标记已看完
        viewModelScope.launch {
            controller.state.collect { cs ->
                if (cs.ended && !endedHandled) {
                    endedHandled = true
                    coordinator.onEnded()
                }
            }
        }

        // 播放错误：保存最后已知位置；IO 类错误把该视频标记为不可访问（仅索引状态）
        viewModelScope.launch {
            controller.state.collect { cs ->
                val err = cs.error ?: return@collect
                if (playerError.value == null) {
                    coordinator.onError()
                    val isIo = err.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        err.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        err.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        err.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                        err.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
                        err.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                    playerError.value = PlayerErrorInfo(
                        message = if (isIo) "无法读取视频文件，文件可能已被移动、删除或权限失效" else "播放失败（${err.errorCodeName}）",
                        isFileMissing = isIo,
                    )
                    if (isIo) {
                        videoRepository.markMissing(videoId)
                    }
                }
            }
        }
    }

    private fun onControllerTick(positionMs: Long, durationMs: Long, isPlaying: Boolean, mediaDeltaMs: Long) {
        coordinator.onTick(positionMs, durationMs, controller.state.value.speed, isPlaying)
    }

    fun togglePlay() {
        val cs = controller.state.value
        if (cs.isPlaying) {
            controller.pause()
            coordinator.onPause()
        } else {
            if (cs.ended) {
                // 播放结束后再播：从头开始
                controller.seekTo(0)
            }
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    fun retry() {
        playerError.value = null
        controller.retry()
    }

    /** 生命周期 onStop / 离开页面：暂停并保存进度 */
    fun onLifecycleStopped() {
        if (controller.state.value.isPlaying) {
            controller.pause()
        }
        coordinator.onPause()
    }

    fun onDisposed() {
        coordinator.onSwitchAway()
        controller.release()
    }

    override fun onCleared() {
        // viewModelScope 已取消，但写入经 ProgressWriter 独立作用域仍会落库
        coordinator.onSwitchAway()
        controller.release()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_SPEED = 1.0f
        private const val RESUME_HINT_THRESHOLD_MS = 15_000L
    }
}
