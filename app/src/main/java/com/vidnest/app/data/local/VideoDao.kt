package com.vidnest.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    /** 按 contentUri 唯一索引冲突更新；已存在行须携带其原 id 调用，才能保留主键与关联记录 */
    @Upsert
    suspend fun upsertAll(videos: List<VideoEntity>)

    @Query("SELECT * FROM videos")
    suspend fun getAll(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun getById(id: Long): VideoEntity?

    @Query("SELECT * FROM videos WHERE contentUri = :uri")
    suspend fun getByUri(uri: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE mediaStoreId = :mediaStoreId AND volumeName = :volumeName")
    suspend fun getByMediaStoreId(mediaStoreId: Long, volumeName: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringConflicts(videos: List<VideoEntity>): List<Long>

    /** 本轮扫描未触及的行标记缺失（按扫描批次号 lastScannedAt 判定，规避超长 IN 列表） */
    @Query(
        "UPDATE videos SET availabilityStatus = ${VideoEntity.STATUS_MISSING} " +
            "WHERE lastScannedAt < :scanBatch AND volumeName IN (:volumeNames)",
    )
    suspend fun markMissingByScanBatch(scanBatch: Long, volumeNames: List<String>): Int

    @Query(
        "UPDATE videos SET availabilityStatus = ${VideoEntity.STATUS_AVAILABLE} " +
            "WHERE contentUri IN (:uris)",
    )
    suspend fun markAvailable(uris: List<String>): Int

    /** 播放器报 IO 错误时即时标记单个视频不可访问 */
    @Query(
        "UPDATE videos SET availabilityStatus = ${VideoEntity.STATUS_MISSING} " +
            "WHERE id = :id",
    )
    suspend fun markMissing(id: Long): Int

    /** 用户清理失效索引（不涉及原始文件） */
    @Query("DELETE FROM videos WHERE availabilityStatus = ${VideoEntity.STATUS_MISSING}")
    suspend fun deleteMissing(): Int

    /** 用户移除 SAF 授权目录（显式操作，级联删除播放记录） */
    @Query("DELETE FROM videos WHERE volumeName = :volumeName")
    suspend fun deleteByVolume(volumeName: String): Int

    /** 移动/重命名识别后删除旧身份行（级联删除已迁移走的旧记录） */
    @Query("DELETE FROM videos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM videos WHERE availabilityStatus = ${VideoEntity.STATUS_MISSING}")
    suspend fun countMissing(): Int

    @Query("SELECT * FROM videos WHERE availabilityStatus = ${VideoEntity.STATUS_MISSING}")
    suspend fun getMissing(): List<VideoEntity>

    /** 文件夹聚合：目录 + 数量 + 总大小 + 最近播放时间 */
    @Query(
        "SELECT v.relativePath AS relativePath, COUNT(*) AS videoCount, " +
            "SUM(v.sizeBytes) AS totalBytes, MAX(r.lastPlayedAt) AS lastPlayedAt " +
            "FROM videos v LEFT JOIN playback_records r ON r.videoId = v.id " +
            "WHERE v.availabilityStatus = ${VideoEntity.STATUS_AVAILABLE} " +
            "GROUP BY v.relativePath",
    )
    fun observeFolderRows(): Flow<List<FolderRow>>

    /** 搜索/排序/目录过滤统一走 RawQuery（见 VideoRepository 组装），两张表变化都会触发重发 */
    @RawQuery(observedEntities = [VideoEntity::class])
    fun observeVideoRows(query: SupportSQLiteQuery): Flow<List<VideoWithRecordRow>>

    @RawQuery(observedEntities = [VideoEntity::class])
    suspend fun getVideoRows(query: SupportSQLiteQuery): List<VideoWithRecordRow>

    @Query("SELECT COUNT(*) FROM videos WHERE availabilityStatus = ${VideoEntity.STATUS_AVAILABLE}")
    fun observeAvailableCount(): Flow<Int>
}
