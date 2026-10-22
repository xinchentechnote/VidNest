package com.vidnest.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackSessionDao {

    @Upsert
    suspend fun upsert(session: PlaybackSessionEntity)

    @Upsert
    suspend fun upsertAll(sessions: List<PlaybackSessionEntity>)

    @Query("SELECT * FROM playback_sessions WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): PlaybackSessionEntity?

    /** 领养窗口内的未计数会话（防进程死亡导致的计数丢失/重复） */
    @Query(
        "SELECT * FROM playback_sessions " +
            "WHERE videoId = :videoId AND counted = 0 AND updatedAt >= :notBefore " +
            "ORDER BY startedAt DESC LIMIT 1",
    )
    suspend fun findAdoptable(videoId: Long, notBefore: Long): PlaybackSessionEntity?

    /** 启动清理：已计数会话数据已固化进记录，可删除 */
    @Query("DELETE FROM playback_sessions WHERE counted = 1")
    suspend fun deleteCounted(): Int

    /** 启动清理：超过领养窗口仍未计数的废弃会话 */
    @Query("DELETE FROM playback_sessions WHERE counted = 0 AND updatedAt < :notBefore")
    suspend fun deleteStaleUncounted(notBefore: Long): Int

    @Query("DELETE FROM playback_sessions WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("DELETE FROM playback_sessions")
    suspend fun deleteAll()

    // ===== 播放统计（v2：会话即历史）=====

    /** 按天聚合：媒体时长 / 实际时长（统计近 N 天） */
    @Query(
        "SELECT strftime('%Y-%m-%d', startedAt / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "SUM(accumulatedMs) AS mediaMs, SUM(wallMs) AS wallMs, COUNT(*) AS sessionCount " +
            "FROM playback_sessions WHERE startedAt >= :sinceMs " +
            "GROUP BY bucket ORDER BY bucket DESC",
    )
    fun observeDailyStats(sinceMs: Long): Flow<List<UsageStatsRow>>

    /** 按周聚合（%W 周一起算） */
    @Query(
        "SELECT strftime('%Y-W%W', startedAt / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "SUM(accumulatedMs) AS mediaMs, SUM(wallMs) AS wallMs, COUNT(*) AS sessionCount " +
            "FROM playback_sessions WHERE startedAt >= :sinceMs " +
            "GROUP BY bucket ORDER BY bucket DESC",
    )
    fun observeWeeklyStats(sinceMs: Long): Flow<List<UsageStatsRow>>

    /** 按月聚合 */
    @Query(
        "SELECT strftime('%Y-%m', startedAt / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "SUM(accumulatedMs) AS mediaMs, SUM(wallMs) AS wallMs, COUNT(*) AS sessionCount " +
            "FROM playback_sessions WHERE startedAt >= :sinceMs " +
            "GROUP BY bucket ORDER BY bucket DESC",
    )
    fun observeMonthlyStats(sinceMs: Long): Flow<List<UsageStatsRow>>

    /** 按视频聚合（媒体/实际时长 + 会话数） */
    @Query(
        "SELECT s.videoId AS videoId, v.displayName AS displayName, " +
            "SUM(s.accumulatedMs) AS mediaMs, SUM(s.wallMs) AS wallMs, COUNT(*) AS sessionCount " +
            "FROM playback_sessions s JOIN videos v ON v.id = s.videoId " +
            "GROUP BY s.videoId ORDER BY mediaMs DESC",
    )
    fun observePerVideoStats(): Flow<List<PerVideoStatsRow>>
}
