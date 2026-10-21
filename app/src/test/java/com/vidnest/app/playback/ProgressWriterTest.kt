package com.vidnest.app.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vidnest.app.data.local.PlaybackRecordEntity
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 串行写入器测试：快速连续切换 A/B/C 时写入不乱序、不串号（docs/playback-and-progress.md §2.3）。
 * 哨兵法：向一次性视频 id 提交哨兵写请求，串行 FIFO 保证其完成时此前请求均已落库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressWriterTest {

    private lateinit var db: VidNestDatabase
    private lateinit var writer: ProgressWriter

    /** 哨兵视频 id：不影响真实断言目标 */
    private val sentinelId = 999_999L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VidNestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val recordRepo = PlaybackRecordRepository(db, db.playbackRecordDao())
        val sessionRepo = PlaybackSessionRepository(db, db.playbackSessionDao())
        writer = ProgressWriter(db, recordRepo, sessionRepo)
        // playback_records 有指向 videos 的外键：先建立视频行
        kotlinx.coroutines.runBlocking {
            db.videoDao().upsertAll(listOf(videoRow(1), videoRow(2), videoRow(3)))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun awaitAllPending() {
        writer.submitAndWait(WriteRequest.SaveProgress(sentinelId, 0, 0, null, 0))
    }

    @Test
    fun `快速切换ABC各自位置正确不串号`() = runTest {
        // 模拟快速连续切换：A、B、C 交错提交
        writer.submit(save(1, 10_000))
        writer.submit(save(2, 20_000))
        writer.submit(save(3, 30_000))
        writer.submit(save(1, 11_000))
        writer.submit(save(2, 21_000))
        writer.submit(save(3, 31_000))
        writer.submit(save(1, 12_000))
        awaitAllPending()

        assertEquals(12_000, record(1)!!.positionMs)
        assertEquals(21_000, record(2)!!.positionMs)
        assertEquals(31_000, record(3)!!.positionMs)
    }

    @Test
    fun `同视频多次提交最终值是最后一次`() = runTest {
        repeat(50) { writer.submit(save(1, it.toLong() * 1000)) }
        awaitAllPending()
        assertEquals(49_000, record(1)!!.positionMs)
    }

    @Test
    fun `并发提交交错写请求后各视频最终值正确`() = runTest {
        val requests = buildList {
            repeat(30) { i ->
                add(save(1, i.toLong() * 1000))
                add(save(2, (i + 100).toLong() * 1000))
                add(save(3, (i + 200).toLong() * 1000))
            }
        }
        requests.map { req -> async { writer.submitAndWait(req) } }.awaitAll()
        awaitAllPending()

        assertEquals(29_000, record(1)!!.positionMs)
        assertEquals(129_000, record(2)!!.positionMs)
        assertEquals(229_000, record(3)!!.positionMs)
    }

    @Test
    fun `计数与断点同一事务落库`() = runTest {
        // 先建立视频行（外键）
        db.videoDao().upsertAll(listOf(videoRow(1)))
        // 会话
        val sessionId = "sess-1"
        db.playbackSessionDao().upsert(session(sessionId, videoId = 1))
        writer.submitAndWait(
            WriteRequest.CountReached(
                videoId = 1, positionMs = 8_000, durationMs = 60_000,
                speed = 1.5f, sessionId = sessionId, nowMs = 100,
            ),
        )
        val record = record(1)!!
        assertEquals(1, record.playCount)
        assertEquals(8_000, record.positionMs)
        assertEquals(1.5f, record.lastPlaybackSpeed!!, 0.001f)
        assertEquals(true, db.playbackSessionDao().get(sessionId)!!.counted)
    }

    @Test
    fun `播放结束标记已看完且断点归零`() = runTest {
        db.videoDao().upsertAll(listOf(videoRow(1)))
        writer.submit(save(1, 50_000))
        awaitAllPending()
        writer.submitAndWait(WriteRequest.MarkCompleted(1, 2.0f, nowMs = 200))
        val record = record(1)!!
        assertEquals(true, record.completed)
        assertEquals(0, record.positionMs)
        assertEquals(2.0f, record.lastPlaybackSpeed!!, 0.001f)
    }

    private fun save(videoId: Long, positionMs: Long) = WriteRequest.SaveProgress(
        videoId = videoId,
        positionMs = positionMs,
        durationMs = 600_000,
        speed = null,
        nowMs = 100,
    )

    private suspend fun record(videoId: Long): PlaybackRecordEntity? = db.playbackRecordDao().get(videoId)

    private fun videoRow(id: Long) = com.vidnest.app.data.local.VideoEntity(
        id = id,
        volumeName = "external_primary",
        mediaStoreId = id,
        contentUri = "content://test/$id",
        relativePath = "M",
        displayName = "v$id.mp4",
        sizeBytes = 100,
        durationMs = 60_000,
        width = 0,
        height = 0,
        dateModified = 1,
        lastScannedAt = 1,
    )

    private fun session(sessionId: String, videoId: Long) =
        com.vidnest.app.data.local.PlaybackSessionEntity(
            sessionId = sessionId,
            videoId = videoId,
            accumulatedMs = 0,
            counted = false,
            startedAt = 1,
            updatedAt = 1,
        )
}
