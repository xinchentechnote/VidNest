package com.vidnest.app.data.repository

import androidx.room.withTransaction
import com.vidnest.app.data.local.PlaybackRecordDao
import com.vidnest.app.data.local.PlaybackRecordEntity
import com.vidnest.app.data.local.emptyPlaybackRecord
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.domain.model.PlaybackInfo
import com.vidnest.app.domain.model.toDomain
import com.vidnest.app.domain.policy.ProgressPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 播放记录仓库。写方法供 ProgressWriter（串行队列）调用：
 * 读-改-写全部包在数据库事务中，配合串行化保证无丢失更新。
 */
class PlaybackRecordRepository(
    private val db: VidNestDatabase,
    private val recordDao: PlaybackRecordDao,
) {

    fun observe(videoId: Long): Flow<PlaybackInfo?> =
        recordDao.observe(videoId).map { it?.toDomain() }

    suspend fun get(videoId: Long): PlaybackInfo? = recordDao.get(videoId)?.toDomain()

    /**
     * 播放进度落库（tick/暂停/切换/退出共用）。
     * position 经 sanitizeForSave 校验；duration 为媒体时长快照，供恢复校验。
     */
    suspend fun saveProgress(
        videoId: Long,
        positionMs: Long,
        durationMs: Long,
        speed: Float?,
        nowMs: Long,
    ) {
        val sanitized = ProgressPolicy.sanitizeForSave(positionMs, durationMs)
        db.withTransaction {
            val current = recordDao.get(videoId) ?: emptyPlaybackRecord(videoId, nowMs)
            recordDao.upsert(
                current.copy(
                    positionMs = sanitized,
                    durationMs = durationMs,
                    lastPlayedAt = nowMs,
                    lastPlaybackSpeed = speed ?: current.lastPlaybackSpeed,
                    updatedAt = nowMs,
                ),
            )
        }
    }

    /** 停止播放且位置推进为 0（如刚打开就退出），仅刷新 lastPlayedAt，不写脏位置 */
    suspend fun saveStopped(videoId: Long, nowMs: Long) {
        db.withTransaction {
            val current = recordDao.get(videoId) ?: return@withTransaction
            recordDao.upsert(current.copy(lastPlayedAt = nowMs, updatedAt = nowMs))
        }
    }

    /** 播放结束：completed 置位、位置归零（下次从头），不残留末尾位置 */
    suspend fun markCompleted(videoId: Long, speed: Float?, nowMs: Long) {
        db.withTransaction {
            val current = recordDao.get(videoId) ?: emptyPlaybackRecord(videoId, nowMs)
            recordDao.upsert(
                current.copy(
                    positionMs = 0,
                    completed = true,
                    lastPlayedAt = nowMs,
                    lastPlaybackSpeed = speed ?: current.lastPlaybackSpeed,
                    updatedAt = nowMs,
                ),
            )
        }
    }

    /**
     * 计数 + 断点同事务落库（会话达到阈值时由协调器调用一次）。
     */
    suspend fun applyCountAndProgress(
        videoId: Long,
        positionMs: Long,
        durationMs: Long,
        speed: Float?,
        nowMs: Long,
    ) {
        val sanitized = ProgressPolicy.sanitizeForSave(positionMs, durationMs)
        db.withTransaction {
            val current = recordDao.get(videoId) ?: emptyPlaybackRecord(videoId, nowMs)
            recordDao.upsert(
                current.copy(
                    positionMs = sanitized,
                    durationMs = durationMs,
                    playCount = current.playCount + 1,
                    lastPlayedAt = nowMs,
                    lastPlaybackSpeed = speed ?: current.lastPlaybackSpeed,
                    updatedAt = nowMs,
                ),
            )
        }
    }

    /**
     * 移动/重命名识别后迁移记录到新视频行。
     * videoId 是主键，直接改主键即完成迁移（新行由扫描器 upsert，旧行随后删除）。
     */
    suspend fun transferRecord(fromVideoId: Long, toVideoId: Long, nowMs: Long) {
        db.withTransaction {
            val current = recordDao.get(fromVideoId) ?: return@withTransaction
            recordDao.upsert(current.copy(videoId = toVideoId, updatedAt = nowMs))
            // 原行随旧视频删除时级联清理；若旧视频仍存在则显式移除，防止重复
            recordDao.delete(fromVideoId)
        }
    }
}
