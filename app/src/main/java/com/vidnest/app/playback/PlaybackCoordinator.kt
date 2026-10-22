package com.vidnest.app.playback

import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import com.vidnest.app.domain.policy.PlayCountPolicy
import com.vidnest.app.domain.policy.ProgressPolicy

/**
 * 播放协调器（docs/playback-and-progress.md §2/§4）：
 *
 * 断点：
 * - 播放中每 5s 持久化一次；暂停/切换/退出/错误时立即保存；
 * - 播放结束置 completed 并归零断点；写入经 ProgressWriter 串行执行。
 *
 * 播放次数（会话制，崩溃安全）：
 * - begin 时领养 24h 内未计数会话或新建；
 * - 累计实际播放媒体时长（PlayerController 的 tick 增量已做 seek 防护，暂停不累计）；
 * - 达到 PlayCountPolicy 阈值即提交 CountReached（playCount+1 与 counted 同事务，每会话至多一次）；
 * - 会话累计时长随断点周期落库（SessionTick），进程被杀可恢复。
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
    private var sessionCounted: Boolean = false
    private var sessionAccumulatedMs: Long = 0

    /** 实际墙钟时长用 Double 累计，避免每 tick 整除截断漂移 */
    private var sessionWallMsDouble: Double = 0.0
    private var lastTickNowMs: Long = 0
    private var lastTickWasPlaying: Boolean = false

    /** 打开视频：领养/新建播放会话并记录初始断点（suspend：需读库领养） */
    suspend fun begin(videoId: Long, resumePositionMs: Long, nowMs: Long = System.currentTimeMillis()) {
        this.videoId = videoId
        lastKnownPositionMs = resumePositionMs
        lastSaveElapsedMs = nowMs
        val session = sessionRepository.adoptOrCreate(videoId, nowMs)
        sessionId = session.sessionId
        sessionCounted = session.counted
        sessionAccumulatedMs = session.accumulatedMs
        sessionWallMsDouble = session.wallMs.toDouble()
        lastTickNowMs = nowMs
        // begin 后立即装载并播放，首段间隔计入墙钟（buffering 期间由 2×tick 上限兜底）
        lastTickWasPlaying = true
    }

    /**
     * 播放器 tick（500ms）。mediaDeltaMs 为本次 tick 的媒体时间增量（seek 防护后）。
     */
    fun onTick(
        positionMs: Long,
        durationMs: Long,
        speed: Float?,
        isPlaying: Boolean,
        mediaDeltaMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (videoId < 0) return
        lastKnownPositionMs = positionMs
        lastKnownDurationMs = durationMs
        lastKnownSpeed = speed

        // 媒体时长累计（暂停/seek 跳变不累计）
        if (isPlaying && mediaDeltaMs > 0 && sessionId != null) {
            sessionAccumulatedMs += mediaDeltaMs
            // 实际墙钟时长 = 媒体增量 / 当时倍速（墙钟积分的精确近似：
            // mediaDelta = speed × Δt_wall，1x 时两者相等；2x 听 1 小时内容 → wall 30 分钟）
            sessionWallMsDouble += mediaDeltaMs / (speed ?: 1f).coerceIn(0.25f, 8f)
        }
        lastTickNowMs = nowMs
        lastTickWasPlaying = isPlaying

        // 达到阈值：计数 + 断点同事务落库（每会话至多一次）
        if (!sessionCounted && sessionId != null &&
            PlayCountPolicy.shouldCount(sessionAccumulatedMs, durationMs)
        ) {
            writer.submit(
                WriteRequest.CountReached(
                    videoId = videoId,
                    positionMs = ProgressPolicy.sanitizeForSave(positionMs, durationMs),
                    durationMs = durationMs,
                    speed = speed,
                    sessionId = sessionId!!,
                    nowMs = nowMs,
                ),
            )
            sessionCounted = true
        }

        if (isPlaying && nowMs - lastSaveElapsedMs >= SAVE_INTERVAL_MS) {
            saveProgress(nowMs)
        }
    }

    /** 暂停 / 生命周期停止：立即保存（含会话累计落库） */
    fun onPause(nowMs: Long = System.currentTimeMillis()) {
        if (videoId < 0) return
        saveProgress(nowMs)
    }

    /** 切换视频前 / 退出播放器：立即保存（flush 语义） */
    fun onSwitchAway(nowMs: Long = System.currentTimeMillis()) {
        if (videoId < 0) return
        saveProgress(nowMs)
    }

    /** 播放结束：补足最后一个 tick 到结尾的时长，completed 置位、断点归零（下次从头） */
    fun onEnded(nowMs: Long = System.currentTimeMillis()) {
        if (videoId < 0) return
        // 丢尾补偿：最后 tick 与结尾之间的媒体/墙钟时长
        if (lastKnownDurationMs > lastKnownPositionMs &&
            lastKnownDurationMs - lastKnownPositionMs <= 2 * TICK_INTERVAL_MS * 8
        ) {
            val tail = lastKnownDurationMs - lastKnownPositionMs
            sessionAccumulatedMs += tail
            if (lastTickWasPlaying) {
                sessionWallMsDouble += tail / (lastKnownSpeed ?: 1f).coerceIn(0.25f, 8f)
            }
            lastKnownPositionMs = lastKnownDurationMs
        }
        sessionId?.let {
            writer.submit(
                WriteRequest.SessionTick(
                    sessionId = it,
                    accumulatedMs = sessionAccumulatedMs,
                    wallMs = Math.round(sessionWallMsDouble),
                    nowMs = nowMs,
                ),
            )
        }
        writer.submit(WriteRequest.MarkCompleted(videoId = videoId, speed = lastKnownSpeed, nowMs = nowMs))
    }

    /** 播放错误：保存最后已知位置；未计数的会话直接放弃（失败播放不计入、可领养窗口作废） */
    fun onError(nowMs: Long = System.currentTimeMillis()) {
        if (videoId < 0) return
        if (lastKnownPositionMs > 0) {
            saveProgress(nowMs)
        }
        sessionId?.let { writer.submit(WriteRequest.DropSession(it)) }
        sessionId = null
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
        sessionId?.let {
            writer.submit(
                WriteRequest.SessionTick(
                    sessionId = it,
                    accumulatedMs = sessionAccumulatedMs,
                    wallMs = Math.round(sessionWallMsDouble),
                    nowMs = nowMs,
                ),
            )
        }
        lastSaveElapsedMs = nowMs
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
        const val TICK_INTERVAL_MS = 500L
    }
}
