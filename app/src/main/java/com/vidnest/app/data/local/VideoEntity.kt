package com.vidnest.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 视频索引表。见 docs/data-model.md：
 * - contentUri 全局唯一，重复扫描按它 upsert，天然幂等防重复；
 * - 文件消失只置 availabilityStatus = MISSING，不删行、不删播放记录；
 * - SAF 视频的 mediaStoreId 固定为 -1，身份由 contentUri（DocumentsContract URI）承担。
 */
@Entity(
    tableName = "videos",
    indices = [
        Index(value = ["contentUri"], unique = true),
        Index(value = ["relativePath"]),
        Index(value = ["availabilityStatus"]),
    ],
)
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "volumeName") val volumeName: String,
    @ColumnInfo(name = "mediaStoreId") val mediaStoreId: Long,
    @ColumnInfo(name = "contentUri") val contentUri: String,
    @ColumnInfo(name = "relativePath") val relativePath: String,
    @ColumnInfo(name = "displayName") val displayName: String,
    @ColumnInfo(name = "sizeBytes") val sizeBytes: Long,
    @ColumnInfo(name = "durationMs") val durationMs: Long,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    @ColumnInfo(name = "dateModified") val dateModified: Long,
    @ColumnInfo(name = "lastScannedAt") val lastScannedAt: Long,
    @ColumnInfo(name = "availabilityStatus") val availabilityStatus: Int = STATUS_AVAILABLE,
) {
    companion object {
        const val STATUS_AVAILABLE = 0
        const val STATUS_MISSING = 1
    }
}

/** availabilityStatus 的领域取值 */
enum class Availability {
    AVAILABLE,
    MISSING,
}

/** 层级浏览的目录段聚合行（按路径某一段分组，计数含其全部子目录） */
data class FolderSegmentRow(
    @ColumnInfo(name = "segment") val segment: String,
    @ColumnInfo(name = "videoCount") val videoCount: Int,
    @ColumnInfo(name = "totalBytes") val totalBytes: Long?,
    @ColumnInfo(name = "lastPlayedAt") val lastPlayedAt: Long?,
)

/** 视频与其播放记录的联表行（记录可能不存在） */
data class VideoWithRecordRow(
    @Embedded val video: VideoEntity,
    @Embedded(prefix = "r_") val record: PlaybackRecordRow?,
)

/**
 * playback_records 联表投影。
 * 注意：@Embedded(prefix = "r_") 的匹配规则是“结果列去掉 r_ 前缀后等于字段列名”，
 * 因此这里字段名保持裸名（videoId 等），SQL 侧用 r_videoId 等别名对应。
 */
data class PlaybackRecordRow(
    val videoId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val completed: Boolean,
    val lastPlaybackSpeed: Float?,
    val updatedAt: Long,
)
