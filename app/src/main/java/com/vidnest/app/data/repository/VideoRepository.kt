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
import kotlinx.coroutines.flow.first
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

    /**
     * 层级浏览：目录行聚合后在本层完成分段（含全部子层级计数）。
     * 根目录：按路径第一段分组。
     */
    fun observeRootFolders(): Flow<List<FolderInfo>> =
        videoDao.observeAllDirectoryRows().map { rows -> groupFolders(rows, path = null) }

    /** 子目录层级：文件夹页展示当前目录下的直接子目录 */
    fun observeChildFolders(path: String): Flow<List<FolderInfo>> =
        videoDao.observeDirectoryRows(path, escapeLike(path) + "/%")
            .map { rows -> groupFolders(rows, path = path) }

    /**
     * 自愈兜底：按最后一段目录名查找真实路径（取最短匹配 = 最顶层）。
     * 找不到返回 null。dirName 需为转义后的 LIKE 字面量调用方传入，
     * 这里同时提供原始名精确匹配。
     */
    suspend fun findFolderPathByName(dirName: String): String? =
        withContext(ioDispatcher) {
            val escaped = escapeLike(dirName)
            videoDao.findDirectoryRowsByName(dirName, escaped)
                .first()
                .filter {
                    it.relativePath == dirName || it.relativePath.endsWith("/$dirName")
                }
                .minByOrNull { it.relativePath }
                ?.relativePath
        }

    /**
     * 把精确目录行聚合为直接子目录：
     * - relativePath == path 的行计入“当前目录直含视频数”；
     * - 其余行按下一级段名归组（计数含其全部子层级）。
     */
    private fun groupFolders(rows: List<FolderRow>, path: String?): List<FolderInfo> {
        data class Agg(var count: Int = 0, var bytes: Long = 0, var lastPlayedAt: Long? = null)

        val children = LinkedHashMap<String, Agg>()
        var directCount = 0
        var directBytes = 0L
        var directLastPlayedAt: Long? = null
        val prefix = path?.let { "$it/" } ?: ""

        for (row in rows) {
            val playedAt = row.lastPlayedAt
            if (path != null && row.relativePath == path) {
                directCount += row.videoCount
                directBytes += row.totalBytes ?: 0
                if (playedAt != null &&
                    (directLastPlayedAt == null || playedAt > directLastPlayedAt)
                ) {
                    directLastPlayedAt = playedAt
                }
                continue
            }
            val rest = row.relativePath.removePrefix(prefix)
            val segment = rest.substringBefore('/')
            val agg = children.getOrPut(segment) { Agg() }
            agg.count += row.videoCount
            agg.bytes += row.totalBytes ?: 0
            val currentBest = agg.lastPlayedAt
            if (playedAt != null && (currentBest == null || playedAt > currentBest)) {
                agg.lastPlayedAt = playedAt
            }
        }

        val result = children.map { (segment, agg) ->
            val fullPath = path?.let { "$it/$segment" } ?: segment
            FolderInfo(
                path = fullPath,
                displayName = segment,
                videoCount = agg.count,
                totalBytes = agg.bytes,
                lastPlayedAtMs = agg.lastPlayedAt,
            )
        }
        // path 非空时直含视频数并入当前目录不展示；根目录不需要 direct 概念
        return result
    }

    fun observeFolderVideos(path: String, sort: VideoSortField): Flow<List<Video>> =
        videoDao.observeVideoRows(buildQuery(sort, folderPath = path)).map { it.toDomainList() }

    fun observeAllVideos(searchQuery: String?, sort: VideoSortField): Flow<List<Video>> =
        videoDao.observeVideoRows(buildQuery(sort, searchQuery = searchQuery)).map { it.toDomainList() }

    fun observeAvailableCount(): Flow<Int> = videoDao.observeAvailableCount()

    /** 播放历史：按最近播放倒序（在看 + 已看完） */
    fun observeHistory(): Flow<List<Video>> =
        videoDao.observeHistory().map { it.toDomainList() }

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
