package com.vidnest.app.util

import java.util.Locale
import java.util.concurrent.TimeUnit

/** 统一的时间/大小格式化（中文界面） */
object Formatters {

    /** 1:23:45 / 12:30；非正数返回 0:00（未知时长由 UI 决定显示“--:--”） */
    fun duration(totalMs: Long): String {
        val safeMs = if (totalMs < 0) 0 else totalMs
        val hours = TimeUnit.MILLISECONDS.toHours(safeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(safeMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(safeMs) % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** 列表元信息用：未知/零时长显示 --:-- */
    fun durationOrUnknown(totalMs: Long): String =
        if (totalMs > 0) duration(totalMs) else "--:--"

    /** 1.2 GB / 345 MB / 56 KB / 512 B */
    fun fileSize(bytes: Long): String {
        if (bytes < 0) return "--"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    /** yyyy-MM-dd HH:mm（epoch 毫秒或秒自动识别） */
    fun dateTime(epochValue: Long, isSeconds: Boolean = false): String {
        val ms = if (isSeconds) epochValue * 1000 else epochValue
        if (ms <= 0) return "--"
        val cal = java.util.Calendar.getInstance(Locale.CHINA).apply { timeInMillis = ms }
        val y = cal.get(java.util.Calendar.YEAR)
        val mo = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val mi = cal.get(java.util.Calendar.MINUTE)
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d", y, mo, d, h, mi)
    }

    /** 分辨率文本；未知返回空串 */
    fun resolution(width: Int, height: Int): String =
        if (width > 0 && height > 0) "${width}×${height}" else ""
}
