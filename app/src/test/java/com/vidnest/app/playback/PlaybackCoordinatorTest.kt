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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 断点保存时机测试（docs/playback-and-progress.md §2.1）：
 * 5s 周期保存、暂停立即保存、结束置 completed 归零、保存位置经 sanitize。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackCoordinatorTest {

    private lateinit var db: VidNestDatabase
    private lateinit var writer: ProgressWriter
    private lateinit var coordinator: PlaybackCoordinator

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
        coordinator = PlaybackCoordinator(recordRepo, sessionRepo, writer)
        // playback_records 有指向 videos 的外键：先建立视频行
        kotlinx.coroutines.runBlocking {
            db.videoDao().upsertAll(listOf(videoRow(1), videoRow(2), videoRow(3)))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

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

    private suspend fun awaitAllPending() {
        writer.submitAndWait(WriteRequest.SaveProgress(sentinelId, 0, 0, null, 0))
    }

    @Test
    fun `播放中每5秒保存一次`() = runTest {
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        // 4.9s 内的 tick 不触发保存
        coordinator.onTick(positionMs = 4_000, durationMs = 60_000, speed = 1f, isPlaying = true, mediaDeltaMs = 600, nowMs = 4_900)
        awaitAllPending()
        assertNull(db.playbackRecordDao().get(1))
        // 5s 后的 tick 触发保存
        coordinator.onTick(positionMs = 5_200, durationMs = 60_000, speed = 1f, isPlaying = true, mediaDeltaMs = 600, nowMs = 5_200)
        awaitAllPending()
        assertEquals(5_200, db.playbackRecordDao().get(1)!!.positionMs)
    }

    @Test
    fun `暂停时立即保存`() = runTest {
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        coordinator.onTick(2_000, 60_000, 1f, true, mediaDeltaMs = 600, nowMs = 2_000)
        coordinator.onPause(nowMs = 2_500)
        awaitAllPending()
        assertEquals(2_000, db.playbackRecordDao().get(1)!!.positionMs)
    }

    @Test
    fun `暂停期间不累计保存周期`() = runTest {
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        coordinator.onPause(nowMs = 1_000)
        awaitAllPending()
        // 暂停状态下 tick（isPlaying=false）不触发保存
        coordinator.onTick(2_000, 60_000, 1f, isPlaying = false, mediaDeltaMs = 0, nowMs = 10_000)
        awaitAllPending()
        assertEquals(1_000, db.playbackRecordDao().get(1)!!.updatedAt)
    }

    @Test
    fun `播放结束置已看完且断点归零`() = runTest {
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        coordinator.onTick(58_000, 60_000, 1f, true, mediaDeltaMs = 600, nowMs = 1_000)
        coordinator.onEnded(nowMs = 1_500)
        awaitAllPending()
        val record = db.playbackRecordDao().get(1)!!
        assertEquals(true, record.completed)
        assertEquals(0, record.positionMs)
    }

    @Test
    fun `保存位置超出时长被截断`() = runTest {
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        coordinator.onTick(70_000, 60_000, 1f, true, mediaDeltaMs = 600, nowMs = 5_000)
        awaitAllPending()
        assertEquals(60_000, db.playbackRecordDao().get(1)!!.positionMs)
    }

    @Test
    fun `切换视频前flush当前视频`() = runTest {
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        coordinator.onTick(3_000, 60_000, 1f, true, mediaDeltaMs = 600, nowMs = 3_000)
        coordinator.onSwitchAway(nowMs = 3_100)
        awaitAllPending()
        assertEquals(3_000, db.playbackRecordDao().get(1)!!.positionMs)
        // 新视频会话重新开始
        coordinator.begin(2, resumePositionMs = 0, nowMs = 4_000)
        coordinator.onTick(500, 60_000, 1f, true, mediaDeltaMs = 600, nowMs = 5_000)
        awaitAllPending()
        assertNull(db.playbackRecordDao().get(2))
    }

    @Test
    fun `未知时长时sanitize不截断`() = runTest {
        coordinator.begin(1, resumePositionMs = 0, nowMs = 0)
        coordinator.onTick(30_000, 0, 1f, true, mediaDeltaMs = 600, nowMs = 5_000)
        awaitAllPending()
        assertNotNull(db.playbackRecordDao().get(1))
        assertEquals(30_000, db.playbackRecordDao().get(1)!!.positionMs)
    }
}
