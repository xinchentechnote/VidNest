package com.vidnest.app.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressPolicyTest {

    @Test
    fun `已看完视频从头播放`() {
        assertEquals(
            0L,
            ProgressPolicy.resolveResumePosition(
                savedPositionMs = 1_000_000,
                savedDurationMs = 1_000_000,
                mediaDurationMs = 1_000_000,
                completed = true,
            ),
        )
    }

    @Test
    fun `正常断点被恢复`() {
        assertEquals(
            750_000L,
            ProgressPolicy.resolveResumePosition(
                savedPositionMs = 750_000,
                savedDurationMs = 1_000_000,
                mediaDurationMs = 1_000_000,
                completed = false,
            ),
        )
    }

    @Test
    fun `零或负断点从头播放`() {
        assertEquals(
            0L,
            ProgressPolicy.resolveResumePosition(0, 1_000_000, 1_000_000, false),
        )
        assertEquals(
            0L,
            ProgressPolicy.resolveResumePosition(-100, 1_000_000, 1_000_000, false),
        )
    }

    @Test
    fun `超出媒体时长加容差的进度视为无效`() {
        assertEquals(
            0L,
            ProgressPolicy.resolveResumePosition(
                savedPositionMs = 2_000_000,
                savedDurationMs = 1_000_000,
                mediaDurationMs = 1_000_000,
                completed = false,
            ),
        )
    }

    @Test
    fun `接近结尾但未超容差的位置照常恢复`() {
        // 1_001_000 <= 1_000_000 + 2_000
        assertEquals(
            1_001_000L,
            ProgressPolicy.resolveResumePosition(1_001_000, 1_000_000, 1_000_000, false),
        )
    }

    @Test
    fun `媒体时长显著变化且旧进度超出新时长则从头`() {
        // 原时长 100s，进度 99s；重新扫描后时长变为 50s（变化超 5%），99s > 50s
        assertEquals(
            0L,
            ProgressPolicy.resolveResumePosition(
                savedPositionMs = 99_000,
                savedDurationMs = 100_000,
                mediaDurationMs = 50_000,
                completed = false,
            ),
        )
    }

    @Test
    fun `保存位置为负归零`() {
        assertEquals(0L, ProgressPolicy.sanitizeForSave(-1, 100_000))
    }

    @Test
    fun `保存位置超过时长截到时长`() {
        assertEquals(100_000L, ProgressPolicy.sanitizeForSave(120_000, 100_000))
    }

    @Test
    fun `未知时长时不截断位置`() {
        assertEquals(50_000L, ProgressPolicy.sanitizeForSave(50_000, 0))
    }

    @Test
    fun `正常位置原样保留`() {
        assertEquals(50_000L, ProgressPolicy.sanitizeForSave(50_000, 100_000))
    }
}
