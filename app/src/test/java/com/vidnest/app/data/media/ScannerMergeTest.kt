package com.vidnest.app.data.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vidnest.app.data.local.PlaybackRecordDao
import com.vidnest.app.data.local.PlaybackRecordEntity
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.local.VideoDao
import com.vidnest.app.data.local.VideoEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 扫描合并策略测试（docs/permissions-and-media-scanning.md §2.3）：
 * 幂等、新增、缺失标记、移动/重命名记录迁移、部分失败保留数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerMergeTest {

    private lateinit var db: VidNestDatabase
    private lateinit var videoDao: VideoDao
    private lateinit var recordDao: PlaybackRecordDao
    private lateinit var scanner: DeviceVideoScanner

    private var sourceRows: List<ScannedVideo> = emptyList()
    private var sourceError: Throwable? = null

    private val fakeSource = object : VideoSource {
        override suspend fun scan(onProgress: (Int) -> Unit): SourceScan {
            sourceError?.let { throw it }
            onProgress(sourceRows.size)
            return SourceScan(
                videos = sourceRows,
                coveredVolumes = if (sourceError == null) listOf("external_primary") else emptyList(),
            )
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VidNestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        videoDao = db.videoDao()
        recordDao = db.playbackRecordDao()
        scanner = DeviceVideoScanner(db, videoDao, recordDao, listOf(fakeSource))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(
        id: Long,
        path: String = "Movies",
        name: String = "a.mp4",
        size: Long = 1000,
        volume: String = "external_primary",
    ) = ScannedVideo(
        mediaStoreId = id,
        contentUri = ContentUriBuilder.contentUri(volume, id),
        volumeName = volume,
        relativePath = path,
        displayName = name,
        sizeBytes = size,
        durationMs = 60_000,
        width = 1280,
        height = 720,
        dateModified = 100,
    )

    @Test
    fun `重复扫描同一批不产生重复行`() = runTest {
        sourceRows = listOf(row(1), row(2))
        scanner.fullScan()
        val first = scanner.fullScan()
        assertEquals(2, videoDao.getAll().size)
        assertEquals(2, first.totalFound)
        assertEquals(0, first.added)
        assertEquals(2, first.updated)
    }

    @Test
    fun `新增文件入库_消失文件标缺失但保留记录`() = runTest {
        sourceRows = listOf(row(1))
        scanner.fullScan()
        val id1 = videoDao.getByUri(row(1).contentUri)!!.id
        recordDao.upsert(
            PlaybackRecordEntity(
                videoId = id1, positionMs = 30_000, durationMs = 60_000, playCount = 2,
                lastPlayedAt = 5, completed = false, lastPlaybackSpeed = null, updatedAt = 5,
            ),
        )
        // 文件消失
        sourceRows = emptyList()
        val result = scanner.fullScan()
        assertEquals(1, result.missing)
        assertEquals(VideoEntity.STATUS_MISSING, videoDao.getById(id1)!!.availabilityStatus)
        val record = recordDao.get(id1)
        assertNotNull(record)
        assertEquals(30_000, record!!.positionMs)
        // 文件回来：同一 URI 恢复可用，记录继续使用
        sourceRows = listOf(row(1))
        scanner.fullScan()
        assertEquals(VideoEntity.STATUS_AVAILABLE, videoDao.getById(id1)!!.availabilityStatus)
        assertEquals(30_000, recordDao.get(id1)!!.positionMs)
    }

    @Test
    fun `移动或重命名后迁移播放记录`() = runTest {
        sourceRows = listOf(row(1, path = "Movies", name = "old.mp4"))
        scanner.fullScan()
        val oldId = videoDao.getByUri(row(1).contentUri)!!.id
        recordDao.upsert(
            PlaybackRecordEntity(
                videoId = oldId, positionMs = 42_000, durationMs = 60_000, playCount = 3,
                lastPlayedAt = 9, completed = false, lastPlaybackSpeed = 1.5f, updatedAt = 9,
            ),
        )
        // 文件移动：新 mediaStoreId、新路径
        sourceRows = listOf(row(7, path = "Download", name = "old.mp4"))
        val result = scanner.fullScan()
        assertEquals(1, result.transferred)
        assertNull(videoDao.getById(oldId))
        val newId = videoDao.getByUri(row(7).contentUri)!!.id
        val record = recordDao.get(newId)
        assertNotNull(record)
        assertEquals(42_000, record!!.positionMs)
        assertEquals(3, record.playCount)
        assertNull(recordDao.get(oldId))
    }

    @Test
    fun `多候选同名同大小不迁移记录`() = runTest {
        sourceRows = listOf(row(1, name = "clip.mp4", size = 500), row(2, name = "clip.mp4", size = 500))
        scanner.fullScan()
        val id1 = videoDao.getByUri(row(1).contentUri)!!.id
        recordDao.upsert(
            PlaybackRecordEntity(
                videoId = id1, positionMs = 10_000, durationMs = 60_000, playCount = 1,
                lastPlayedAt = 1, completed = false, lastPlaybackSpeed = null, updatedAt = 1,
            ),
        )
        // 两个候选同时消失，一个同名同大小的新文件出现 → 候选不唯一，不迁移
        sourceRows = listOf(row(9, name = "clip.mp4", size = 500))
        val result = scanner.fullScan()
        assertEquals(0, result.transferred)
    }

    @Test
    fun `扫描源异常不清空已有数据`() = runTest {
        sourceRows = listOf(row(1))
        scanner.fullScan()
        assertEquals(1, videoDao.getAll().size)
        sourceError = SecurityException("denied")
        val result = scanner.fullScan()
        assertTrue(result.hasFailures)
        assertEquals(1, videoDao.getAll().size)
        assertEquals(VideoEntity.STATUS_AVAILABLE, videoDao.getAll().first().availabilityStatus)
        sourceError = null
    }

    @Test
    fun `进度回调被调用`() = runTest {
        sourceRows = listOf(row(1), row(2), row(3))
        var lastProgress = 0
        scanner.fullScan { lastProgress = it }
        assertEquals(3, lastProgress)
    }
}
