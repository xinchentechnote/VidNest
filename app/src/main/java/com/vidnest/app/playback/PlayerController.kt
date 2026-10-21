package com.vidnest.app.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ExoPlayer 封装（docs/playback-and-progress.md §1）：
 * - 状态以 StateFlow 暴露给 UI；
 * - 500ms tick：驱动进度条与媒体时间增量（供断点/计数协调器）；
 * - tick 中的媒体时间增量带 seek 防护（跳变不累计）。
 * 必须在主线程构造与操作。
 */
class PlayerController(context: Context) {

    data class State(
        val hasMedia: Boolean = false,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val speed: Float = 1f,
        val isBuffering: Boolean = false,
        val ended: Boolean = false,
        val error: PlaybackException? = null,
    )

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 每个 tick 回调（500ms）：position/duration/isPlaying + 本次 tick 的媒体时间增量。
     * 增量仅在播放中且未发生 seek 跳变时为正；暂停为 0；seek 跳变与回退为 0。
     */
    var tickListener: ((positionMs: Long, durationMs: Long, isPlaying: Boolean, mediaDeltaMs: Long) -> Unit)? =
        null

    private var tickJob: Job? = null
    private var lastTickPositionMs = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateFromPlayer()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _state.update { it.copy(ended = true, positionMs = it.durationMs) }
            }
            updateFromPlayer()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error, isPlaying = false, isBuffering = false) }
        }
    }

    init {
        player.addListener(listener)
    }

    /** 装载视频；startPositionMs 为恢复断点（0 = 从头） */
    fun load(contentUri: String, startPositionMs: Long, speed: Float) {
        _state.value = State(hasMedia = true, speed = speed)
        lastTickPositionMs = startPositionMs.coerceAtLeast(0)
        player.setMediaItem(MediaItem.fromUri(contentUri))
        player.setPlaybackSpeed(speed)
        if (startPositionMs > 0) player.seekTo(startPositionMs)
        player.prepare()
        player.playWhenReady = true
        startTick()
    }

    fun play() {
        player.play()
        _state.update { it.copy(error = null) }
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        player.seekTo(target)
        lastTickPositionMs = target
        _state.update { it.copy(positionMs = target, ended = false) }
    }

    fun seekBy(deltaMs: Long) {
        seekTo(player.currentPosition + deltaMs)
    }

    /** 切换倍速不重置播放位置（ExoPlayer 语义即如此）；错误后可重试 */
    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun retry() {
        _state.update { it.copy(error = null, ended = false) }
        player.prepare()
        player.play()
    }

    fun release() {
        tickJob?.cancel()
        tickJob = null
        player.removeListener(listener)
        player.release()
        scope.cancel()
    }

    private fun startTick() {
        if (tickJob != null) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                val position = player.currentPosition.coerceAtLeast(0)
                val duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
                val playing = player.isPlaying
                val delta = position - lastTickPositionMs
                val mediaDelta = if (playing && delta > 0 && delta <= MAX_TICK_MEDIA_DELTA_MS) delta else 0L
                lastTickPositionMs = position
                _state.update {
                    it.copy(
                        positionMs = position,
                        durationMs = duration,
                        isPlaying = playing,
                        isBuffering = player.playbackState == Player.STATE_BUFFERING,
                    )
                }
                tickListener?.invoke(position, duration, playing, mediaDelta)
            }
        }
    }

    private fun updateFromPlayer() {
        _state.update {
            it.copy(
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
            )
        }
    }

    private companion object {
        const val TICK_INTERVAL_MS = 500L

        /** 3x 倍速下 500ms 墙钟最多 1500ms 媒体时间；超过视为 seek 跳变 */
        const val MAX_TICK_MEDIA_DELTA_MS = 1_600L
    }
}
