package com.vidnest.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 播放记录 DAO。所有写操作只允许两类：
 * 1. @Upsert 全行写入 —— 调用方必须在同一事务内先读取再修改（ProgressWriter 串行化保证无竞态）；
 * 2. 定向 UPDATE —— 不覆盖其他字段。
 * 禁止用 UI 层构造的过期全行对象直接 upsert 覆盖 playCount。
 */
@Dao
interface PlaybackRecordDao {

    @Upsert
    suspend fun upsert(record: PlaybackRecordEntity)

    @Upsert
    suspend fun upsertAll(records: List<PlaybackRecordEntity>)

    @Query("SELECT * FROM playback_records WHERE videoId = :videoId")
    suspend fun get(videoId: Long): PlaybackRecordEntity?

    @Query("SELECT * FROM playback_records WHERE videoId = :videoId")
    fun observe(videoId: Long): Flow<PlaybackRecordEntity?>

    @Query("SELECT * FROM playback_records")
    suspend fun getAll(): List<PlaybackRecordEntity>

    @Query("SELECT * FROM playback_records WHERE videoId IN (:videoIds)")
    suspend fun getByVideoIds(videoIds: List<Long>): List<PlaybackRecordEntity>

    @Query("DELETE FROM playback_records WHERE videoId = :videoId")
    suspend fun delete(videoId: Long)
}
