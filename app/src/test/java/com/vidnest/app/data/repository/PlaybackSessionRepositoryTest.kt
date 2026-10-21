package com.vidnest.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vidnest.app.data.local.PlaybackSessionEntity
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.local.VideoEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 会话仓库：领养、清理、每会话至多计一次（docs/playback-and-progress.md §4） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSessionRepositoryTest {

    private lateinit var db: VidNestDatabase
    private lateinit var repo: PlaybackSessionRepository

    private val now = 1_000_000_000L

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VidNestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PlaybackSessionRepository(db, db.playbackSessionDao())
        db.videoDao().upsertAll(listOf(videoRow(1)))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun videoRow(id: Long) = VideoEntity(
        id = id, volumeName = "v", mediaStoreId = id, contentUri = "content://test/$id",
        relativePath = "M", displayName = "v$id", sizeBytes = 1, durationMs = 60_000,
        width = 0, height = 0, dateModified = 1, lastScannedAt = 1,
    )

    @Test
    fun `新建会话`() = runTest {
        val s = repo.adoptOrCreate(videoId = 1, nowMs = now)
        assertTrue(s.sessionId.isNotBlank())
        assertEquals(0, s.accumulatedMs)
        assertFalse(s.counted)
    }

    @Test
    fun `领养24小时内未计数会话并延续累计`() = runTest {
        val first = repo.adoptOrCreate(1, now)
        repo.updateAccumulated(first.sessionId, 6_000, now + 1_000)
        val adopted = repo.adoptOrCreate(1, now + 2_000)
        assertEquals(first.sessionId, adopted.sessionId)
        assertEquals(6_000, adopted.accumulatedMs)
    }

    @Test
    fun `超过24小时的未计数会话不被领养`() = runTest {
        val first = repo.adoptOrCreate(1, now)
        val twoDaysLater = now + 2 * 24 * 60 * 60 * 1000
        val fresh = repo.adoptOrCreate(1, twoDaysLater)
        org.junit.Assert.assertNotEquals(first.sessionId, fresh.sessionId)
        assertEquals(0, fresh.accumulatedMs)
    }

    @Test
    fun `已计数会话不再被领养_重播开新会话`() = runTest {
        val first = repo.adoptOrCreate(1, now)
        repo.markCounted(first.sessionId, now + 100)
        val second = repo.adoptOrCreate(1, now + 200)
        org.junit.Assert.assertNotEquals(first.sessionId, second.sessionId)
        assertFalse(second.counted)
    }

    @Test
    fun `markCounted幂等`() = runTest {
        val s = repo.adoptOrCreate(1, now)
        repo.markCounted(s.sessionId, now + 1)
        repo.markCounted(s.sessionId, now + 2)
        val after = db.playbackSessionDao().get(s.sessionId)!!
        assertTrue(after.counted)
        // 已计数会话不再累计
        repo.updateAccumulated(s.sessionId, 99_999, now + 3)
        assertEquals(0, db.playbackSessionDao().get(s.sessionId)!!.accumulatedMs)
    }

    @Test
    fun `cleanup删除已计数与过期未计数会话`() = runTest {
        val counted = repo.adoptOrCreate(1, now)
        repo.markCounted(counted.sessionId, now)
        val stale = db.playbackSessionDao().get(counted.sessionId)!!
        // 过期未计数会话
        db.playbackSessionDao().upsert(
            PlaybackSessionEntity(
                sessionId = "stale", videoId = 1, accumulatedMs = 1_000,
                counted = false, startedAt = 1, updatedAt = now - 25 * 60 * 60 * 1000,
            ),
        )
        val removed = repo.cleanup(now + 1)
        assertEquals(2, removed)
        assertNull(db.playbackSessionDao().get(counted.sessionId))
        assertNull(db.playbackSessionDao().get("stale"))
        assertNotNull(db.videoDao().getById(1))
    }
}
