package com.vidnest.app.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayCountPolicyTest {

    @Test
    fun `长视频阈值固定为10秒`() {
        assertEquals(10_000L, PlayCountPolicy.thresholdMs(30 * 60 * 1000L))
        assertEquals(10_000L, PlayCountPolicy.thresholdMs(Long.MAX_VALUE / 2))
    }

    @Test
    fun `短视频阈值为时长的三分之一`() {
        assertEquals(3_333L, PlayCountPolicy.thresholdMs(10_000L))
        assertEquals(5_000L, PlayCountPolicy.thresholdMs(15_000L))
        assertEquals(1_000L, PlayCountPolicy.thresholdMs(3_000L))
    }

    @Test
    fun `未知或非法时长取10秒阈值`() {
        assertEquals(10_000L, PlayCountPolicy.thresholdMs(0))
        assertEquals(10_000L, PlayCountPolicy.thresholdMs(-5_000))
    }

    @Test
    fun `累计达到阈值才计数`() {
        val duration = 15_000L
        assertFalse(PlayCountPolicy.shouldCount(4_999, duration))
        assertTrue(PlayCountPolicy.shouldCount(5_000, duration))
    }

    @Test
    fun `未知时长按10秒判断`() {
        assertFalse(PlayCountPolicy.shouldCount(9_999, 0))
        assertTrue(PlayCountPolicy.shouldCount(10_000, 0))
    }
}
