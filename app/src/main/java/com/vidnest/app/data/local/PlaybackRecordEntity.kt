package com.vidnest.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放统计与断点表，与视频一对一（videoId 即主键）。
 * 级联删除仅在用户显式清理失效索引/移除 SAF 目录时触发；
 * 扫描时视频标 MISSING 不删除本表记录。
 */
@Entity(
    tableName = "playback_records",
    foreignKeys = [
        ForeignKey(
            entity = VideoEntity::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("videoId")],
)
data class PlaybackRecordEntity(
    @PrimaryKey @ColumnInfo(name = "videoId") val videoId: Long,
    @ColumnInfo(name = "positionMs") val positionMs: Long,
    @ColumnInfo(name = "durationMs") val durationMs: Long,
    @ColumnInfo(name = "playCount") val playCount: Int,
    @ColumnInfo(name = "lastPlayedAt") val lastPlayedAt: Long?,
    @ColumnInfo(name = "completed") val completed: Boolean,
    @ColumnInfo(name = "lastPlaybackSpeed") val lastPlaybackSpeed: Float?,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long,
)

/**
 * 播放会话表：崩溃安全的播放次数统计。
 * - accumulatedMs 达到 PlayCountPolicy 阈值时 counted 置位并 playCount+1（同一事务）；
 * - 进程死亡后 24h 内未计数会话可被“领养”继续累计，过期未计数会话启动时清理。
 */
@Entity(
    tableName = "playback_sessions",
    foreignKeys = [
        ForeignKey(
            entity = VideoEntity::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["videoId", "counted"])],
)
data class PlaybackSessionEntity(
    @PrimaryKey @ColumnInfo(name = "sessionId") val sessionId: String,
    @ColumnInfo(name = "videoId") val videoId: Long,
    @ColumnInfo(name = "accumulatedMs") val accumulatedMs: Long,
    @ColumnInfo(name = "counted") val counted: Boolean,
    @ColumnInfo(name = "startedAt") val startedAt: Long,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long,
)

/** 空记录工厂（顶级函数：KSP2 对带成员/companion 函数的 @Entity 存在类型解析缺陷，见 ksp#2026） */
fun emptyPlaybackRecord(videoId: Long, nowMs: Long) = PlaybackRecordEntity(
    videoId = videoId,
    positionMs = 0,
    durationMs = 0,
    playCount = 0,
    lastPlayedAt = null,
    completed = false,
    lastPlaybackSpeed = null,
    updatedAt = nowMs,
)
