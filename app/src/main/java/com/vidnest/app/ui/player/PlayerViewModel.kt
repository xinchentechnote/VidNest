package com.vidnest.app.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import com.vidnest.app.data.repository.VideoRepository
import com.vidnest.app.data.settings.SettingsRepository
import com.vidnest.app.domain.model.EndBehavior
import com.vidnest.app.domain.model.ResumeBehavior
import com.vidnest.app.domain.model.Speeds
import com.vidnest.app.domain.model.Video
import com.vidnest.app.domain.model.VideoSortField
import com.vidnest.app.domain.policy.ProgressPolicy
import com.vidnest.app.playback.PlaybackCoordinator
import com.vidnest.app.playback.PlayerController
import com.vidnest.app.playback.ProgressWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** 断点恢复询问（ResumeBehavior.ASK）：待用户选择的位置 */
    val pendingResumeAsk: Long? = null,
    /** 已看完视频打开询问（EndBehavior.ASK） */
    val pendingEndAsk: Boolean = false,
    /** 同文件夹内的视频（播放列表，供切换） */
    val playlist: List<Video> = emptyList(),
    /** 用户在设置中配置的倍速列表 */
    val speedOptions: List<Float> = Speeds.DEFAULT,
)

class PlayerViewModel(
    initialVideoId: Long,
    private val videoRepository: VideoRepository,
    private val recordRepository: PlaybackRecordRepository,
    private val sessionRepository: PlaybackSessionRepository,
    private val writer: ProgressWriter,
    private val settingsRepository: SettingsRepository,
    controllerContext: Context,
) : ViewModel() {

    val controller = PlayerController(controllerContext)

    private val coordinator = PlaybackCoordinator(recordRepository, sessionRepository, writer)

    /** 当前播放的视频（播放列表切换时会变化）；所有落库以它为准，防止新旧视频串号 */
    private var currentVideoId: Long = initialVideoId

    /** 切换视频期间忽略 tick，避免旧视频的位置写入新视频的记录 */
    @Volatile
    private var switching = false

    private val videoState = MutableStateFlow<Video?>(null)
    private val resumeHint = MutableStateFlow<Long?>(null)
    private val videoUnavailable = MutableStateFlow(false)
    private val playerError = MutableStateFlow<PlayerErrorInfo?>(null)
    private val pendingResumeAsk = MutableStateFlow<Long?>(null)
    private val pendingEndAsk = MutableStateFlow(false)
    private val speedOptionsState = MutableStateFlow(Speeds.DEFAULT)
    private var initialSpeed = 1.0f

    private var endedHandled = false

    private val playlistFlow = videoState.flatMapLatest { video ->
        if (video == null) flowOf(emptyList<Video>())
        else videoRepository.observeFolderVideos(video.folderPath, VideoSortField.NAME)
    }

    val uiState: StateFlow<PlayerUiState> = combine(
        videoState,
        controller.state,
        resumeHint,
        videoUnavailable,
        playerError,
        pendingResumeAsk,
        pendingEndAsk,
        playlistFlow,
        speedOptionsState,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val video = values[0] as Video?
        val cs = values[1] as PlayerController.State
        val hint = values[2] as Long?
        val unavailable = values[3] as Boolean
        val error = values[4] as PlayerErrorInfo?
        val resumeAsk = values[5] as Long?
        val endAsk = values[6] as Boolean
        val playlist = values[7] as List<Video>
        val options = values[8] as List<Float>
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
            pendingResumeAsk = resumeAsk,
            pendingEndAsk = endAsk,
            playlist = playlist,
            speedOptions = options,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    init {
        controller.tickListener = ::onControllerTick

        viewModelScope.launch {
            val video = videoRepository.getVideo(currentVideoId)
            if (video == null || video.isMissing) {
                videoState.value = video
                videoUnavailable.value = true
                return@launch
            }
            videoState.value = video
            currentVideoId = video.id

            val record = recordRepository.get(currentVideoId)
            val resumeBehavior = settingsRepository.resumeBehavior.first()
            val endBehavior = settingsRepository.endBehavior.first()
            val rememberSpeed = settingsRepository.rememberSpeed.first()
            val savedSpeed = settingsRepository.playbackSpeed.first()
            speedOptionsState.value = settingsRepository.speedOptions.first()
            initialSpeed = if (rememberSpeed && savedSpeed > 0) savedSpeed else DEFAULT_SPEED

            val mediaResume = ProgressPolicy.resolveResumePosition(
                savedPositionMs = record?.positionMs ?: 0,
                savedDurationMs = record?.durationMs ?: 0,
                mediaDurationMs = video.durationMs,
                completed = record?.completed == true,
            )
            when {
                // 已看完 + 每次询问：先让用户决定（默认约定是从头播放）
                record?.completed == true && endBehavior == EndBehavior.ASK -> {
                    pendingEndAsk.value = true
                }
                // 未看完 + 每次询问：继续 or 从头
                resumeBehavior == ResumeBehavior.ASK && mediaResume > 0 -> {
                    pendingResumeAsk.value = mediaResume
                }
                else -> {
                    val start = if (resumeBehavior == ResumeBehavior.NEVER) 0L else mediaResume
                    startPlayback(start)
                }
            }
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

        // 播放错误：保存最后已知位置并放弃未计数会话；IO 类错误标记视频不可访问
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
                        videoRepository.markMissing(currentVideoId)
                    }
                }
            }
        }
    }

    private fun onControllerTick(positionMs: Long, durationMs: Long, isPlaying: Boolean, mediaDeltaMs: Long) {
        if (switching) return
        coordinator.onTick(
            positionMs = positionMs,
            durationMs = durationMs,
            speed = controller.state.value.speed,
            isPlaying = isPlaying,
            mediaDeltaMs = mediaDeltaMs,
        )
    }

    /** 装载并开始播放（恢复询问/结束询问确认后调用） */
    fun startPlayback(positionMs: Long) {
        pendingResumeAsk.value = null
        pendingEndAsk.value = false
        val video = videoState.value ?: return
        viewModelScope.launch {
            coordinator.begin(currentVideoId, positionMs)
            resumeHint.value = positionMs.takeIf { it >= RESUME_HINT_THRESHOLD_MS }
            controller.load(video.contentUri, positionMs, speed = initialSpeed)
        }
    }

    /**
     * 播放列表切换：先 flush 当前视频断点（串行队列），再装载新视频并恢复其断点。
     * switching 闸门保证切换窗口内的旧 tick 不会写入新视频记录（防串号）。
     */
    fun switchToVideo(targetId: Long) {
        if (targetId == currentVideoId) return
        switching = true
        coordinator.onSwitchAway()
        endedHandled = false
        playerError.value = null
        resumeHint.value = null
        pendingResumeAsk.value = null
        pendingEndAsk.value = false
        currentVideoId = targetId
        viewModelScope.launch {
            val video = videoRepository.getVideo(targetId)
            if (video == null || video.isMissing) {
                switching = false
                videoUnavailable.value = true
                return@launch
            }
            videoUnavailable.value = false
            videoState.value = video
            val record = recordRepository.get(targetId)
            val resume = ProgressPolicy.resolveResumePosition(
                savedPositionMs = record?.positionMs ?: 0,
                savedDurationMs = record?.durationMs ?: 0,
                mediaDurationMs = video.durationMs,
                completed = record?.completed == true,
            )
            resumeHint.value = resume.takeIf { it >= RESUME_HINT_THRESHOLD_MS }
            coordinator.begin(targetId, resume)
            controller.load(video.contentUri, resume, speed = controller.state.value.speed)
            switching = false
        }
    }

    fun onResumeAskContinue() {
        startPlayback(pendingResumeAsk.value ?: 0)
    }

    fun onResumeAskFromStart() {
        startPlayback(0)
    }

    fun onEndAskReplay() {
        startPlayback(0)
    }

    fun onEndAskCancelled() {
        pendingEndAsk.value = false
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

    /** 切换倍速不重置位置；记住倍速为全局偏好 */
    fun setSpeed(speed: Float) {
        controller.setSpeed(speed)
        viewModelScope.launch { settingsRepository.setPlaybackSpeed(speed) }
    }

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
