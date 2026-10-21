package com.vidnest.app.playback

import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import com.vidnest.app.domain.policy.ProgressPolicy

/**
 * 断点保存协调器（docs/playback-and-progress.md §2）：
 * - 播放中每 5s 持久化一次（以累计播放时间驱动，暂停不计）；
 * - 暂停/切换/退出/错误时立即保存；
 * - 播放结束置 completed 并归零断点；
 * - 所有写入经 ProgressWriter 串行执行。
 * 每个播放器会话一个实例（由 PlayerViewModel 持有）。
 */
class PlaybackCoordinator(
    private val recordRepository: PlaybackRecordRepository,
    private val sessionRepository: PlaybackSessionRepository,
    private val writer: ProgressWriter,
) {

    private var videoId: Long = -1
    private var lastSaveElapsedMs: Long = 0
    private var lastKnownPositionMs: Long = 0
    private var lastKnownDurationMs: Long = 0
    private var lastKnownSpeed: Float? = null

    private var sessionId: String? = null

    fun begin(videoId: Long, resumePositionMs: Long, nowMs: Long = System.currentTimeMillis()) {
        this.videoId = videoId
        lastKnownPositionMs = resumePositionMs
        lastSaveElapsedMs = nowMs
    }

    /**
     * 播放器 tick（500ms）。媒体时间增量已由 PlayerController 做 seek 防护。
     * 累计播放满 SAVE_INTERVAL_MS 即提交一次保存（暂停时 tick 累计为 0，不触发）。
     */
    fun onTick(
        positionMs: Long,
        durationMs: Long,
        speed: Float?,
        isPlaying: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (videoId < 0) return
        lastKnownPositionMs = positionMs
        lastKnownDurationMs = durationMs
        lastKnownSpeed = speed
        if (isPlaying && nowMs - lastSaveElapsedMs >= SAVE_INTERVAL_MS) {
            saveProgress(nowMs)
        }
    }

    /** 暂停 / 生命周期停止：立即保存 */
    fun onPause(nowMs: Long = System.currentTimeMillis()) {
        if (videoId < 0) return
        saveProgress(nowMs)
    }

    /** 切换视频前 / 退出播放器：立即保存（flush 语义） */
    fun onSwitchAway(nowMs: Long = System.currentTimeMillis()) {
        if (videoId < 0) return
        saveProgress(nowMs)
    }

    /** 播放结束：completed 置位、断点归零（下次从头），不得把断点存成末尾 */
    fun onEnded(nowMs: Long = System.currentTimeMillis()) {
        if (videoId < 0) return
        writer.submit(WriteRequest.MarkCompleted(videoId = videoId, speed = lastKnownSpeed, nowMs = nowMs))
    }

    /** 播放错误：保存最后已知位置 */
    fun onError(nowMs: Long = System.currentTimeMillis()) {
        if (videoId < 0) return
        if (lastKnownPositionMs > 0) {
            saveProgress(nowMs)
        }
    }

    private fun saveProgress(nowMs: Long) {
        val duration = lastKnownDurationMs
        writer.submit(
            WriteRequest.SaveProgress(
                videoId = videoId,
                positionMs = ProgressPolicy.sanitizeForSave(lastKnownPositionMs, duration),
                durationMs = duration,
                speed = lastKnownSpeed,
                nowMs = nowMs,
            ),
        )
        lastSaveElapsedMs = nowMs
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
