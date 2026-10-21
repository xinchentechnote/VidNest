package com.vidnest.app.data.media

import androidx.room.withTransaction
import com.vidnest.app.data.local.PlaybackRecordDao
import com.vidnest.app.data.local.VideoDao
import com.vidnest.app.data.local.VideoEntity
import com.vidnest.app.data.local.VidNestDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 合并扫描器（docs/permissions-and-media-scanning.md §2.3）：
 * 1. upsert（保留已有行 id 与播放记录）；2. 本轮未触及的行标 MISSING（不删行）；
 * 3. 移动/重命名识别：新增行与 MISSING 行唯一同名同大小 → 迁移播放记录；
 * 4. 单个数据源失败不影响其他源与既有数据；全部在数据库事务内完成。
 */
class DeviceVideoScanner(
    private val db: VidNestDatabase,
    private val videoDao: VideoDao,
    private val recordDao: PlaybackRecordDao,
    private val sources: List<VideoSource>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    data class Result(
        val added: Int,
        val updated: Int,
        val missing: Int,
        val transferred: Int,
        val failedSources: List<String>,
        val totalFound: Int,
        val elapsedMs: Long,
    ) {
        val hasFailures: Boolean get() = failedSources.isNotEmpty()
    }

    suspend fun fullScan(onProgress: (found: Int) -> Unit = {}): Result =
        withContext(ioDispatcher) {
            val startedAt = System.currentTimeMillis()
            val scanBatch = startedAt
            val failedSources = mutableListOf<String>()
            val collected = mutableListOf<ScannedVideo>()
            val coveredVolumes = mutableListOf<String>()

            for (source in sources) {
                val before = collected.size
                try {
                    val result = source.scan { done -> onProgress(before + done) }
                    collected += result.videos
                    coveredVolumes += result.coveredVolumes
                    onProgress(collected.size)
                } catch (e: SecurityException) {
                    failedSources.add("没有媒体访问权限")
                } catch (e: Exception) {
                    failedSources.add(e.message ?: e.javaClass.simpleName)
                }
            }

            var added = 0
            var updated = 0
            var missing = 0
            var transferred = 0

            db.withTransaction {
                val existingByUri = videoDao.getAll().associateBy { it.contentUri }
                val newUris = mutableSetOf<String>()

                val entities = collected.map { s ->
                    val prev = existingByUri[s.contentUri]
                    if (prev == null) {
                        added++
                        newUris.add(s.contentUri)
                    } else {
                        updated++
                    }
                    VideoEntity(
                        id = prev?.id ?: 0,
                        volumeName = s.volumeName,
                        mediaStoreId = s.mediaStoreId,
                        contentUri = s.contentUri,
                        relativePath = s.relativePath,
                        displayName = s.displayName,
                        sizeBytes = s.sizeBytes,
                        durationMs = s.durationMs,
                        width = s.width,
                        height = s.height,
                        dateModified = s.dateModified,
                        lastScannedAt = scanBatch,
                        availabilityStatus = VideoEntity.STATUS_AVAILABLE,
                    )
                }
                entities.chunked(UPSERT_BATCH).forEach { videoDao.upsertAll(it) }

                // 本轮未触及的行（限本轮成功扫描的 volume）标缺失；失败的 volume 原样保留
                missing = if (coveredVolumes.isEmpty()) 0 else {
                    videoDao.markMissingByScanBatch(scanBatch, coveredVolumes.distinct())
                }

                transferred = if (newUris.isEmpty()) 0 else {
                    reconcileMovedFiles(scanBatch, newUris)
                }
            }

            Result(
                added = added,
                updated = updated,
                missing = missing,
                transferred = transferred,
                failedSources = failedSources,
                totalFound = collected.size,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        }

    /**
     * 移动/重命名识别：本批“新增”行 vs 当前 MISSING 行。
     * 仅当 displayName + sizeBytes 唯一匹配时迁移播放记录（宁可丢关联也不错绑）。
     */
    private suspend fun reconcileMovedFiles(scanBatch: Long, newUris: Set<String>): Int {
        val missingRows = videoDao.getMissing()
        if (missingRows.isEmpty()) return 0
        val allRows = videoDao.getAll()
        val newRows = allRows.filter { it.contentUri in newUris }
        var transferred = 0
        val oldIdsToDelete = mutableListOf<Long>()
        val now = System.currentTimeMillis()
        for (newRow in newRows) {
            val candidates = missingRows.filter {
                it.id != newRow.id &&
                    it.displayName == newRow.displayName &&
                    it.sizeBytes == newRow.sizeBytes
            }
            if (candidates.size != 1) continue
            val oldRow = candidates.first()
            val record = recordDao.get(oldRow.id) ?: continue
            // 记录迁移到新行（videoId 为主键，整体换行），旧行删除时级联清理旧记录
            recordDao.upsert(record.copy(videoId = newRow.id, updatedAt = now))
            oldIdsToDelete.add(oldRow.id)
            transferred++
        }
        if (oldIdsToDelete.isNotEmpty()) {
            videoDao.deleteByIds(oldIdsToDelete)
        }
        return transferred
    }

    private companion object {
        const val UPSERT_BATCH = 200
    }
}
