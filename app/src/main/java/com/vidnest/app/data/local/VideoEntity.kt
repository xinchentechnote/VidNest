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

/** playback_records 联表投影（videoId 联接后别名，避免与主键冲突） */
data class PlaybackRecordRow(
    @ColumnInfo(name = "r_videoId") val videoId: Long,
    @ColumnInfo(name = "r_positionMs") val positionMs: Long,
    @ColumnInfo(name = "r_durationMs") val durationMs: Long,
    @ColumnInfo(name = "r_playCount") val playCount: Int,
    @ColumnInfo(name = "r_lastPlayedAt") val lastPlayedAt: Long?,
    @ColumnInfo(name = "r_completed") val completed: Boolean,
    @ColumnInfo(name = "r_lastPlaybackSpeed") val lastPlaybackSpeed: Float?,
    @ColumnInfo(name = "r_updatedAt") val updatedAt: Long,
)
