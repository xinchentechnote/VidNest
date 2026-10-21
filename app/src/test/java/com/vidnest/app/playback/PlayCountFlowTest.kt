package com.vidnest.app.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放次数规则端到端测试（coordinator + writer + Room，docs/playback-and-progress.md §4）：
 * 阈值计数一次、暂停/seek 不计、倍速不影响、跨协调器领养不重复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayCountFlowTest {

    private lateinit var db: VidNestDatabase
    private lateinit var recordRepo: PlaybackRecordRepository
    private lateinit var sessionRepo: PlaybackSessionRepository
    private lateinit var writer: ProgressWriter

    private val sentinelId = 999_999L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VidNestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordRepo = PlaybackRecordRepository(db, db.playbackRecordDao())
        sessionRepo = PlaybackSessionRepository(db, db.playbackSessionDao())
        writer = ProgressWriter(db, recordRepo, sessionRepo)
        kotlinx.coroutines.runBlocking {
            db.videoDao().upsertAll(listOf(videoRow(1)))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun videoRow(id: Long) = com.vidnest.app.data.local.VideoEntity(
        id = id, volumeName = "v", mediaStoreId = id, contentUri = "content://test/$id",
        relativePath = "M", displayName = "v$id", sizeBytes = 1, durationMs = 60_000,
        width = 0, height = 0, dateModified = 1, lastScannedAt = 1,
    )

    /** 时长 60s 视频：阈值 = 10s */
    private fun tickMs(i: Int) = i * 500L

    private suspend fun awaitAllPending() {
        writer.submitAndWait(WriteRequest.SaveProgress(sentinelId, 0, 0, null, 0))
    }

    @Test
    fun `累计10秒才计数且只计一次`() = runTest {
        val coordinator = PlaybackCoordinator(recordRepo, sessionRepo, writer)
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        // 18 个 tick × 600ms 媒体增量 = 10.8s；前 16 个（9.6s）不计数
        for (i in 1..16) {
            coordinator.onTick(600L * i, 60_000, 1f, isPlaying = true, mediaDeltaMs = 600, nowMs = tickMs(i))
        }
        awaitAllPending()
        assertEquals(0, recordRepo.get(1)!!.playCount)
        for (i in 17..24) {
            coordinator.onTick(600L * i, 60_000, 1f, isPlaying = true, mediaDeltaMs = 600, nowMs = tickMs(i))
        }
        awaitAllPending()
        assertEquals(1, recordRepo.get(1)!!.playCount)
        // 继续播放也不再计数
        for (i in 25..30) {
            coordinator.onTick(600L * i, 60_000, 1f, isPlaying = true, mediaDeltaMs = 600, nowMs = tickMs(i))
        }
        awaitAllPending()
        assertEquals(1, recordRepo.get(1)!!.playCount)
    }

    @Test
    fun `暂停与seek不产生累计`() = runTest {
        val coordinator = PlaybackCoordinator(recordRepo, sessionRepo, writer)
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        // 暂停 tick：isPlaying=false，delta=0
        for (i in 1..30) {
            coordinator.onTick(5_000, 60_000, 1f, isPlaying = false, mediaDeltaMs = 0, nowMs = tickMs(i))
        }
        // seek 跳变：controller 给出的 delta 为 0
        coordinator.onTick(30_000, 60_000, 1f, isPlaying = true, mediaDeltaMs = 0, nowMs = 20_000)
        coordinator.onPause(nowMs = 21_000)
        awaitAllPending()
        assertEquals(0, recordRepo.get(1)!!.playCount)
    }

    @Test
    fun `倍速播放按媒体时间累计_不影响计数规则`() = runTest {
        val coordinator = PlaybackCoordinator(recordRepo, sessionRepo, writer)
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        // 3x 倍速：每 500ms tick 媒体增量 1500ms；7 个 tick = 10.5s ≥ 10s
        for (i in 1..7) {
            coordinator.onTick(1_500L * i, 60_000, 3f, isPlaying = true, mediaDeltaMs = 1_500, nowMs = tickMs(i))
        }
        awaitAllPending()
        assertEquals(1, recordRepo.get(1)!!.playCount)
        assertEquals(3f, recordRepo.get(1)!!.lastPlaybackSpeed!!, 0.001f)
    }

    @Test
    fun `进程被杀后会话领养继续累计不重复计数`() = runTest {
        // 第一次播放：累计 8s（<10s）后被杀（未计数会话已通过 SessionTick 落库）
        val first = PlaybackCoordinator(recordRepo, sessionRepo, writer)
        first.begin(1, resumePositionMs = 0, nowMs = 0)
        for (i in 1..13) {
            first.onTick(600L * i, 60_000, 1f, true, mediaDeltaMs = 600, nowMs = tickMs(i))
        }
        first.onPause(nowMs = tickMs(14)) // flush SessionTick
        awaitAllPending()
        assertEquals(0, recordRepo.get(1)!!.playCount)

        // 进程重启：新协调器领养会话继续累计 3s → 计数一次
        val second = PlaybackCoordinator(recordRepo, sessionRepo, writer)
        second.begin(1, resumePositionMs = 8_000, nowMs = 100_000)
        for (i in 1..6) {
            second.onTick(9_000 + 500L * i, 60_000, 1f, true, mediaDeltaMs = 500, nowMs = 100_000 + tickMs(i))
        }
        awaitAllPending()
        assertEquals(1, recordRepo.get(1)!!.playCount)
    }

    @Test
    fun `播放失败放弃会话不计数`() = runTest {
        val coordinator = PlaybackCoordinator(recordRepo, sessionRepo, writer)
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        coordinator.onTick(600, 60_000, 1f, true, mediaDeltaMs = 600, nowMs = 500)
        coordinator.onError(nowMs = 600)
        awaitAllPending()
        assertEquals(0, recordRepo.get(1)!!.playCount)
        // 会话被放弃：新的 begin 创建全新会话（累计从 0 开始）
        val sessions = db.playbackSessionDao().let { dao ->
            kotlinx.coroutines.runBlocking { dao.findAdoptable(1, notBefore = 0) }
        }
        assertEquals(null, sessions)
    }

    @Test
    fun `结束后重播_新会话再次计数`() = runTest {
        val first = PlaybackCoordinator(recordRepo, sessionRepo, writer)
        first.begin(1, resumePositionMs = 0, nowMs = 0)
        for (i in 1..18) {
            first.onTick(600L * i, 60_000, 1f, true, mediaDeltaMs = 600, nowMs = tickMs(i))
        }
        first.onEnded(nowMs = 10_000)
        awaitAllPending()
        assertEquals(1, recordRepo.get(1)!!.playCount)
        assertTrue(recordRepo.get(1)!!.completed)
        assertEquals(0, recordRepo.get(1)!!.positionMs)

        // 重新打开：completed → 从头；新会话再累计 10s → 第二次计数
        val second = PlaybackCoordinator(recordRepo, sessionRepo, writer)
        second.begin(1, resumePositionMs = 0, nowMs = 100_000)
        for (i in 1..18) {
            second.onTick(600L * i, 60_000, 1f, true, mediaDeltaMs = 600, nowMs = 100_000 + tickMs(i))
        }
        awaitAllPending()
        assertEquals(2, recordRepo.get(1)!!.playCount)
    }

    @Test
    fun `计数与断点同步落库`() = runTest {
        val coordinator = PlaybackCoordinator(recordRepo, sessionRepo, writer)
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        for (i in 1..18) {
            coordinator.onTick(600L * i, 60_000, 1f, true, mediaDeltaMs = 600, nowMs = tickMs(i))
        }
        awaitAllPending()
        val record = recordRepo.get(1)!!
        assertEquals(1, record.playCount)
        // 计数发生时（tick17，累计 10.2s）同步保存了断点；tick18 未到保存周期
        assertEquals(10_200, record.positionMs)
        assertFalse(record.completed)
        // 会话标记为已计数
        val session = db.playbackSessionDao().findAdoptable(1, notBefore = 0)
        assertEquals(null, session) // counted 会话不可被领养
    }
}
