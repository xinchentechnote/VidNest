package com.vidnest.app.domain.model

import com.vidnest.app.data.local.Availability
import com.vidnest.app.data.local.PlaybackRecordEntity
import com.vidnest.app.data.local.VideoEntity

/** 观看状态：全局统一规则，见 docs/playback-and-progress.md §5 */
enum class WatchStatus(val label: String) {
    NEVER("未播放"),
    WATCHING("正在观看"),
    COMPLETED("已看完"),
    ;

    companion object {
        fun from(playCount: Int, positionMs: Long, completed: Boolean): WatchStatus = when {
            completed -> COMPLETED
            playCount <= 0 && positionMs <= 0L -> NEVER
            else -> WATCHING
        }
    }
}

/** 播放统计信息（来自 playback_records，可能为空表示从未播放） */
data class PlaybackInfo(
    val positionMs: Long,
    val durationMs: Long,
    val playCount: Int,
    val lastPlayedAtMs: Long?,
    val completed: Boolean,
    val lastPlaybackSpeed: Float?,
) {
    val watchStatus: WatchStatus
        get() = WatchStatus.from(playCount, positionMs, completed)
}

/** 视频领域对象 */
data class Video(
    val id: Long,
    val contentUri: String,
    val displayName: String,
    val folderPath: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val dateModifiedSec: Long,
    val availability: Availability,
    val playback: PlaybackInfo?,
) {
    val watchStatus: WatchStatus get() = playback?.watchStatus ?: WatchStatus.NEVER
    val isMissing: Boolean get() = availability == Availability.MISSING
}

/** 文件夹聚合信息 */
data class FolderInfo(
    val path: String,
    val displayName: String,
    val videoCount: Int,
    val totalBytes: Long,
    val lastPlayedAtMs: Long?,
)

/** 排序字段（列表页与设置页共用） */
enum class VideoSortField(val label: String) {
    NAME("按名称"),
    DATE_MODIFIED("按修改时间"),
    SIZE("按大小"),
    DURATION("按时长"),
    LAST_PLAYED("按最近播放"),
    PLAY_COUNT("按播放次数"),
}

/** 断点恢复行为 */
enum class ResumeBehavior(val label: String) {
    ALWAYS("总是恢复上次位置"),
    ASK("每次询问"),
    NEVER("总是从头播放"),
}

/** 播放结束后的行为 */
enum class EndBehavior(val label: String) {
    FROM_START("下次从头播放"),
    ASK("下次播放时询问"),
}

fun VideoEntity.toDomain(record: PlaybackRecordEntity?): Video = Video(
    id = id,
    contentUri = contentUri,
    displayName = displayName,
    folderPath = relativePath,
    sizeBytes = sizeBytes,
    durationMs = durationMs,
    width = width,
    height = height,
    dateModifiedSec = dateModified,
    availability = if (availabilityStatus == VideoEntity.STATUS_MISSING) Availability.MISSING else Availability.AVAILABLE,
    playback = record?.toDomain(),
)

fun PlaybackRecordEntity.toDomain(): PlaybackInfo = PlaybackInfo(
    positionMs = positionMs,
    durationMs = durationMs,
    playCount = playCount,
    lastPlayedAtMs = lastPlayedAt,
    completed = completed,
    lastPlaybackSpeed = lastPlaybackSpeed,
)

/** 目录末段作为展示名；根目录展示为“内部存储”等友好名称 */
fun folderDisplayName(path: String): String {
    if (path.isBlank()) return "未知目录"
    return path.trimEnd('/').substringAfterLast('/', path)
}
