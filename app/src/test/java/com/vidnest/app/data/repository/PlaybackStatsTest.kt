package com.vidnest.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vidnest.app.data.local.PlaybackSessionEntity
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.local.VideoEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 播放统计聚合（按天/周/月、按视频；媒体时长 vs 实际时长） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackStatsTest {

    private lateinit var db: VidNestDatabase
    private lateinit var repo: PlaybackSessionRepository

    private val day = 24L * 60 * 60 * 1000
    // 固定"今天"锚点（避免时区边界影响桶断言，只断言求和与行数）
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VidNestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PlaybackSessionRepository(db, db.playbackSessionDao())
        db.videoDao().upsertAll(listOf(videoRow(1, "课程A"), videoRow(2, "课程B")))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun videoRow(id: Long, name: String) = VideoEntity(
        id = id, volumeName = "v", mediaStoreId = id, contentUri = "content://test/$id",
        relativePath = "M", displayName = name, sizeBytes = 1, durationMs = 60_000,
        width = 0, height = 0, dateModified = 1, lastScannedAt = 1,
    )

    private fun session(id: String, videoId: Long, mediaMs: Long, wallMs: Long, startedAt: Long) =
        PlaybackSessionEntity(
            sessionId = id, videoId = videoId, accumulatedMs = mediaMs, wallMs = wallMs,
            counted = true, startedAt = startedAt, updatedAt = startedAt,
        )

    @Test
    fun `按天周月聚合媒体时长与实际时长`() = runTest {
        val today = now - 1000
        val yesterday = now - day
        val lastMonth = now - 70 * day
        db.playbackSessionDao().upsertAll(
            listOf(
                session("a", 1, 3_600_000, 1_800_000, today),          // 2x 听课 1 小时内容
                session("b", 2, 1_800_000, 1_800_000, today - 60_000), // 1x 看 30 分钟
                session("c", 1, 600_000, 600_000, yesterday),
                session("d", 2, 7_200_000, 3_600_000, lastMonth),
            ),
        )
        // 按天：近 7 天应有两个桶（今天、昨天）
        val daily = repo.observeDailyStats(now - 7 * day).first()
        assertEquals(2, daily.size)
        val todayRow = daily.first()
        assertEquals(5_400_000, todayRow.mediaMs)
        assertEquals(3_600_000, todayRow.wallMs)
        assertEquals(2, todayRow.sessionCount)
        // 按周（8 周窗口含今天与昨天，不含 70 天前）/ 按月：总量一致
        val weekly = repo.observeWeeklyStats(now - 8 * 7 * day).first()
        assertEquals(6_000_000, weekly.sumOf { it.mediaMs })
        val monthly = repo.observeMonthlyStats(now - 400 * day).first()
        assertEquals(13_200_000, monthly.sumOf { it.mediaMs })
        assertEquals(7_800_000, monthly.sumOf { it.wallMs })
    }

    @Test
    fun `按视频聚合含名称与两类时长`() = runTest {
        val today = now - 1000
        db.playbackSessionDao().upsertAll(
            listOf(
                session("a", 1, 3_600_000, 1_800_000, today),
                session("b", 1, 600_000, 600_000, today - day),
                session("c", 2, 1_800_000, 1_800_000, today),
            ),
        )
        val perVideo = repo.observePerVideoStats().first()
        assertEquals(2, perVideo.size)
        val top = perVideo.first() // 按媒体时长倒序
        assertEquals("课程A", top.displayName)
        assertEquals(4_200_000, top.mediaMs)
        assertEquals(2_400_000, top.wallMs)
        assertEquals(2, top.sessionCount)
    }
}
