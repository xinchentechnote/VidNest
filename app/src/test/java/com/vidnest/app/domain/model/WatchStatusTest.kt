package com.vidnest.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStatusTest {

    @Test
    fun `无播放记录为未播放`() {
        assertEquals(WatchStatus.NEVER, WatchStatus.from(0, 0, false))
    }

    @Test
    fun `看过但无进度为正在观看`() {
        assertEquals(WatchStatus.WATCHING, WatchStatus.from(1, 0, false))
    }

    @Test
    fun `有进度未看完为正在观看`() {
        assertEquals(WatchStatus.WATCHING, WatchStatus.from(0, 5_000, false))
    }

    @Test
    fun `completed标记优先`() {
        assertEquals(WatchStatus.COMPLETED, WatchStatus.from(3, 0, true))
    }
}
