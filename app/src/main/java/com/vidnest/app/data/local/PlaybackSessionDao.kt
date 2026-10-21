package com.vidnest.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PlaybackSessionDao {

    @Upsert
    suspend fun upsert(session: PlaybackSessionEntity)

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
}
