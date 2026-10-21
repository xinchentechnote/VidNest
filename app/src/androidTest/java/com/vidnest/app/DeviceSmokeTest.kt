package com.vidnest.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vidnest.app.data.local.PlaybackRecordEntity
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.local.VideoEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器冒烟测试：验证 Room 在真实 Android 环境下的完整链路。
 * 运行：./gradlew connectedDebugAndroidTest（需连接设备或启动模拟器）
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    private lateinit var db: VidNestDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VidNestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndReadVideoWithRecord() = runBlocking {
        db.videoDao().upsertAll(
            listOf(
                VideoEntity(
                    volumeName = "external_primary",
                    mediaStoreId = 42,
                    contentUri = "content://media/external/video/media/42",
                    relativePath = "DCIM/Camera",
                    displayName = "device-test.mp4",
                    sizeBytes = 12345,
                    durationMs = 65_000,
                    width = 1920,
                    height = 1080,
                    dateModified = 1700000000,
                    lastScannedAt = 1,
                ),
            ),
        )
        val video = db.videoDao().getByUri("content://media/external/video/media/42")
        assertNotNull(video)
        db.playbackRecordDao().upsert(
            PlaybackRecordEntity(
                videoId = video!!.id,
                positionMs = 30_000,
                durationMs = 65_000,
                playCount = 2,
                lastPlayedAt = 100,
                completed = false,
                lastPlaybackSpeed = 1.5f,
                updatedAt = 100,
            ),
        )
        val record = db.playbackRecordDao().get(video.id)
        assertNotNull(record)
        assertEquals(30_000, record!!.positionMs)
        assertEquals(2, record.playCount)

        // 根目录层级聚合在真实 SQLite 上工作
        val roots = db.videoDao().observeRootFolderRows().first()
        assertEquals("DCIM", roots.first().segment)
        assertEquals(1, roots.first().videoCount)
    }
}
