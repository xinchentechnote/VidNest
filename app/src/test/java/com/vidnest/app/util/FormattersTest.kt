package com.vidnest.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `时长格式化 分秒`() {
        assertEquals("0:00", Formatters.duration(0))
        assertEquals("0:59", Formatters.duration(59_000))
        assertEquals("12:30", Formatters.duration(750_000))
        assertEquals("0:00", Formatters.duration(-100))
    }

    @Test
    fun `时长格式化 时分秒`() {
        assertEquals("1:00:00", Formatters.duration(3_600_000))
        assertEquals("1:23:45", Formatters.duration(((1 * 3600) + (23 * 60) + 45) * 1000L))
    }

    @Test
    fun `文件大小格式化`() {
        assertEquals("512 B", Formatters.fileSize(512))
        assertEquals("56 KB", Formatters.fileSize(56 * 1024L))
        assertEquals("345.0 MB", Formatters.fileSize(345 * 1024 * 1024L))
        assertEquals("1.00 GB", Formatters.fileSize(1024 * 1024 * 1024L))
        assertEquals("--", Formatters.fileSize(-1))
    }

    @Test
    fun `分辨率格式化`() {
        assertEquals("1920×1080", Formatters.resolution(1920, 1080))
        assertEquals("", Formatters.resolution(0, 0))
    }

    @Test
    fun `时间格式化`() {
        assertEquals("--", Formatters.dateTime(0))
        assertEquals("--", Formatters.dateTime(-5))
        // epoch 秒 0 (1970-01-01 08:00 北京时间) 按 UTC+8 计算
        val formatted = Formatters.dateTime(1, isSeconds = true)
        assertEquals("1970-01-01 08:00", formatted)
    }
}
