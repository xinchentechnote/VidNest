package com.vidnest.app.data.local

import androidx.room.ColumnInfo

/** 播放统计按时间桶聚合行（天/周/月） */
data class UsageStatsRow(
    @ColumnInfo(name = "bucket") val bucket: String,
    @ColumnInfo(name = "mediaMs") val mediaMs: Long,
    @ColumnInfo(name = "wallMs") val wallMs: Long,
    @ColumnInfo(name = "sessionCount") val sessionCount: Int,
)

/** 播放统计按视频聚合行 */
data class PerVideoStatsRow(
    @ColumnInfo(name = "videoId") val videoId: Long,
    @ColumnInfo(name = "displayName") val displayName: String,
    @ColumnInfo(name = "mediaMs") val mediaMs: Long,
    @ColumnInfo(name = "wallMs") val wallMs: Long,
    @ColumnInfo(name = "sessionCount") val sessionCount: Int,
)
