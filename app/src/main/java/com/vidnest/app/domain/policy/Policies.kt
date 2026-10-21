package com.vidnest.app.domain.policy

import kotlin.math.abs
import kotlin.math.min

/**
 * 播放次数规则（docs/playback-and-progress.md §4）：
 * - 有效播放 = 实际播放时长（暂停不累计）达到 min(10s, duration/3)；时长未知取 10s；
 * - 每个播放会话至多计数一次（counted 标记）。
 */
object PlayCountPolicy {

    const val DEFAULT_THRESHOLD_MS = 10_000L
    const val SESSION_ADOPT_WINDOW_MS = 24L * 60 * 60 * 1000

    fun thresholdMs(durationMs: Long): Long =
        if (durationMs <= 0L) DEFAULT_THRESHOLD_MS else min(DEFAULT_THRESHOLD_MS, durationMs / 3)

    fun shouldCount(accumulatedMs: Long, durationMs: Long): Boolean =
        accumulatedMs >= thresholdMs(durationMs)
}

/**
 * 断点校验规则（docs/playback-and-progress.md §2）。
 */
object ProgressPolicy {

    /** 允许的位置浮差（关键帧/时长取整） */
    const val POSITION_SLACK_MS = 2_000L

    /** 保存/恢复时认为媒体时长发生显著变化的容忍比例 */
    const val DURATION_TOLERANCE_RATIO = 0.05

    /** 打开视频时决定初始位置；completed 或无效进度一律从头 */
    fun resolveResumePosition(
        savedPositionMs: Long,
        savedDurationMs: Long,
        mediaDurationMs: Long,
        completed: Boolean,
    ): Long {
        if (completed) return 0L
        if (savedPositionMs <= 0L) return 0L
        if (mediaDurationMs > 0 && savedPositionMs > mediaDurationMs + POSITION_SLACK_MS) return 0L
        if (savedDurationMs > 0 && mediaDurationMs > 0) {
            val diff = abs(savedDurationMs - mediaDurationMs)
            val changed = diff > savedDurationMs * DURATION_TOLERANCE_RATIO
            if (changed && savedPositionMs > mediaDurationMs) return 0L
        }
        return savedPositionMs
    }

    /** 保存时归一化：负数归 0，超出时长截到时长（避免把断点存成结尾） */
    fun sanitizeForSave(positionMs: Long, mediaDurationMs: Long): Long = when {
        positionMs < 0 -> 0L
        mediaDurationMs > 0 && positionMs >= mediaDurationMs -> mediaDurationMs
        else -> positionMs
    }
}
