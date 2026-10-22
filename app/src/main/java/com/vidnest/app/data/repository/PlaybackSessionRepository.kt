package com.vidnest.app.data.repository

import androidx.room.withTransaction
import com.vidnest.app.data.local.PerVideoStatsRow
import com.vidnest.app.data.local.PlaybackSessionDao
import com.vidnest.app.data.local.UsageStatsRow
import com.vidnest.app.data.local.PlaybackSessionEntity
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.domain.policy.PlayCountPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 播放会话仓库：崩溃安全的计数基础。
 * - 打开视频时领养 24h 内未计数会话或新建；
 * - accumulatedMs 由 ProgressWriter 周期落库（与断点同事务由协调器编排）；
 * - 启动时 cleanup() 清理已计数与过期未计数会话。
 */
class PlaybackSessionRepository(
    private val db: VidNestDatabase,
    private val sessionDao: PlaybackSessionDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun adoptOrCreate(videoId: Long, nowMs: Long): PlaybackSessionEntity =
        withContext(ioDispatcher) {
            db.withTransaction {
                val adoptable = sessionDao.findAdoptable(
                    videoId = videoId,
                    notBefore = nowMs - PlayCountPolicy.SESSION_ADOPT_WINDOW_MS,
                )
                adoptable ?: PlaybackSessionEntity(
                    sessionId = UUID.randomUUID().toString(),
                    videoId = videoId,
                    accumulatedMs = 0,
                    wallMs = 0,
                    counted = false,
                    startedAt = nowMs,
                    updatedAt = nowMs,
                ).also { sessionDao.upsert(it) }
            }
        }

    suspend fun updateAccumulated(
        sessionId: String,
        accumulatedMs: Long,
        wallMs: Long,
        nowMs: Long,
    ) {
        db.withTransaction {
            val current = sessionDao.get(sessionId) ?: return@withTransaction
            // v2 起：counted 会话也持续落库——它们是播放统计的数据源
            sessionDao.upsert(
                current.copy(
                    accumulatedMs = accumulatedMs,
                    wallMs = wallMs,
                    updatedAt = nowMs,
                ),
            )
        }
    }

    suspend fun markCounted(sessionId: String, nowMs: Long) {
        db.withTransaction {
            val current = sessionDao.get(sessionId) ?: return@withTransaction
            if (!current.counted) {
                sessionDao.upsert(current.copy(counted = true, updatedAt = nowMs))
            }
        }
    }

    suspend fun delete(sessionId: String) = withContext(ioDispatcher) { sessionDao.delete(sessionId) }

    // ===== 播放统计（会话即历史）=====

    fun observeDailyStats(sinceMs: Long): Flow<List<UsageStatsRow>> =
        sessionDao.observeDailyStats(sinceMs)

    fun observeWeeklyStats(sinceMs: Long): Flow<List<UsageStatsRow>> =
        sessionDao.observeWeeklyStats(sinceMs)

    fun observeMonthlyStats(sinceMs: Long): Flow<List<UsageStatsRow>> =
        sessionDao.observeMonthlyStats(sinceMs)

    fun observePerVideoStats(): Flow<List<PerVideoStatsRow>> =
        sessionDao.observePerVideoStats()

    /**
     * 清理过期未计数会话。v2 起已计数会话保留——它们是播放历史统计的数据源。
     */
    suspend fun cleanup(nowMs: Long): Int = withContext(ioDispatcher) {
        val staleCutoff = nowMs - PlayCountPolicy.SESSION_ADOPT_WINDOW_MS
        db.withTransaction {
            sessionDao.deleteStaleUncounted(staleCutoff)
        }
    }
}
