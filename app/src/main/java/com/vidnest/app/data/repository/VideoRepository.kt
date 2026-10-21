package com.vidnest.app.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.vidnest.app.data.local.FolderRow
import com.vidnest.app.data.local.VideoDao
import com.vidnest.app.data.local.VideoEntity
import com.vidnest.app.data.local.PlaybackRecordEntity
import com.vidnest.app.data.local.PlaybackRecordRow
import com.vidnest.app.data.local.VideoWithRecordRow
import com.vidnest.app.domain.model.FolderInfo
import com.vidnest.app.domain.model.Video
import com.vidnest.app.domain.model.VideoSortField
import com.vidnest.app.domain.model.folderDisplayName
import com.vidnest.app.domain.model.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 视频索引仓库：UI 查询全部走 Flow；扫描相关的写入供 DeviceVideoScanner 调用。
 * 查询按 availabilityStatus = AVAILABLE 过滤；MISSING 项只在详情/清理场景单独读取。
 */
class VideoRepository(
    private val videoDao: VideoDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun observeFolders(): Flow<List<FolderInfo>> =
        videoDao.observeFolderRows().map { rows -> rows.map { it.toDomain() } }

    fun observeFolderVideos(path: String, sort: VideoSortField): Flow<List<Video>> =
        videoDao.observeVideoRows(buildQuery(sort, folderPath = path)).map { it.toDomainList() }

    fun observeAllVideos(searchQuery: String?, sort: VideoSortField): Flow<List<Video>> =
        videoDao.observeVideoRows(buildQuery(sort, searchQuery = searchQuery)).map { it.toDomainList() }

    fun observeAvailableCount(): Flow<Int> = videoDao.observeAvailableCount()

    suspend fun getVideo(videoId: Long): Video? = withContext(ioDispatcher) {
        videoDao.getById(videoId)?.toDomain(null)
    }

    suspend fun getByUri(uri: String): VideoEntity? = withContext(ioDispatcher) {
        videoDao.getByUri(uri)
    }

    suspend fun getAllEntities(): List<VideoEntity> = withContext(ioDispatcher) { videoDao.getAll() }

    /** 供扫描器批量 upsert（实体须携带已有 id 以保留主键） */
    suspend fun upsertAll(videos: List<VideoEntity>) = withContext(ioDispatcher) {
        videos.chunked(UPSERT_BATCH).forEach { videoDao.upsertAll(it) }
    }

    suspend fun markMissingByScanBatch(scanBatch: Long, volumeNames: List<String>): Int =
        withContext(ioDispatcher) { videoDao.markMissingByScanBatch(scanBatch, volumeNames) }

    suspend fun markMissing(id: Long) = withContext(ioDispatcher) { videoDao.markMissing(id) }

    suspend fun markAvailable(uris: List<String>) =
        withContext(ioDispatcher) { videoDao.markAvailable(uris) }

    suspend fun deleteMissing(): Int = withContext(ioDispatcher) { videoDao.deleteMissing() }

    suspend fun deleteByVolume(volumeName: String): Int =
        withContext(ioDispatcher) { videoDao.deleteByVolume(volumeName) }

    suspend fun countMissing(): Int = withContext(ioDispatcher) { videoDao.countMissing() }

    suspend fun getMissing(): List<VideoEntity> = withContext(ioDispatcher) { videoDao.getMissing() }

    private fun FolderRow.toDomain() = FolderInfo(
        path = relativePath,
        displayName = folderDisplayName(relativePath),
        videoCount = videoCount,
        totalBytes = totalBytes ?: 0L,
        lastPlayedAtMs = lastPlayedAt,
    )

    private fun List<VideoWithRecordRow>.toDomainList(): List<Video> =
        map { row -> row.video.toDomain(row.record?.toRecordEntity()) }

    private fun PlaybackRecordRow.toRecordEntity() =
        PlaybackRecordEntity(
            videoId = videoId,
            positionMs = positionMs,
            durationMs = durationMs,
            playCount = playCount,
            lastPlayedAt = lastPlayedAt,
            completed = completed,
            lastPlaybackSpeed = lastPlaybackSpeed,
            updatedAt = updatedAt,
        )

    companion object {
        private const val UPSERT_BATCH = 200

        private const val SELECT = """
            SELECT v.*, r.videoId AS r_videoId, r.positionMs AS r_positionMs,
                   r.durationMs AS r_durationMs, r.playCount AS r_playCount,
                   r.lastPlayedAt AS r_lastPlayedAt, r.completed AS r_completed,
                   r.lastPlaybackSpeed AS r_lastPlaybackSpeed, r.updatedAt AS r_updatedAt
            FROM videos v LEFT JOIN playback_records r ON r.videoId = v.id
            WHERE v.availabilityStatus = ${VideoEntity.STATUS_AVAILABLE}
        """

        fun orderByClause(sort: VideoSortField): String = when (sort) {
            VideoSortField.NAME -> "LOWER(v.displayName) ASC, v.dateModified DESC"
            VideoSortField.DATE_MODIFIED -> "v.dateModified DESC, LOWER(v.displayName) ASC"
            VideoSortField.SIZE -> "v.sizeBytes DESC, LOWER(v.displayName) ASC"
            VideoSortField.DURATION -> "v.durationMs DESC, LOWER(v.displayName) ASC"
            VideoSortField.LAST_PLAYED ->
                "CASE WHEN r.lastPlayedAt IS NULL THEN 1 ELSE 0 END ASC, r.lastPlayedAt DESC, LOWER(v.displayName) ASC"
            VideoSortField.PLAY_COUNT -> "r.playCount DESC, LOWER(v.displayName) ASC"
        }

        /** LIKE 转义：\ % _ */
        fun escapeLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        fun buildQuery(
            sort: VideoSortField,
            folderPath: String? = null,
            searchQuery: String? = null,
        ): SimpleSQLiteQuery {
            val sql = StringBuilder(SELECT)
            val args = mutableListOf<Any>()
            if (!folderPath.isNullOrBlank()) {
                sql.append(" AND v.relativePath = ?")
                args.add(folderPath)
            }
            if (!searchQuery.isNullOrBlank()) {
                sql.append(" AND v.displayName LIKE ? ESCAPE '\\'")
                args.add("%${escapeLike(searchQuery.trim())}%")
            }
            sql.append(" ORDER BY ").append(orderByClause(sort))
            return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
        }
    }
}
