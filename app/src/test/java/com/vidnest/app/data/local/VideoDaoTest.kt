package com.vidnest.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 数据层基础测试（Robolectric + 内存库）：
 * upsert 幂等、文件夹聚合、缺失标记、联表查询。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoDaoTest {

    private lateinit var db: VidNestDatabase
    private lateinit var videoDao: VideoDao
    private lateinit var recordDao: PlaybackRecordDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VidNestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        videoDao = db.videoDao()
        recordDao = db.playbackRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun video(
        id: Long = 0,
        uri: String,
        path: String,
        name: String,
        size: Long = 1_000,
        duration: Long = 60_000,
        scannedAt: Long = 100,
        status: Int = VideoEntity.STATUS_AVAILABLE,
    ) = VideoEntity(
        id = id,
        volumeName = "external_primary",
        mediaStoreId = uri.hashCode().toLong(),
        contentUri = uri,
        relativePath = path,
        displayName = name,
        sizeBytes = size,
        durationMs = duration,
        width = 1920,
        height = 1080,
        dateModified = 1700000000,
        lastScannedAt = scannedAt,
        availabilityStatus = status,
    )

    @Test
    fun `重复upsert同一URI不产生重复行`() = runTest {
        val v = video(uri = "content://media/1", path = "Movies", name = "a.mp4")
        videoDao.upsertAll(listOf(v.copy(id = 0, lastScannedAt = 100)))
        val inserted = videoDao.getByUri("content://media/1")!!
        videoDao.upsertAll(listOf(v.copy(id = inserted.id, lastScannedAt = 200, sizeBytes = 2_000)))
        assertEquals(1, videoDao.getAll().size)
        val after = videoDao.getByUri("content://media/1")!!
        assertEquals(inserted.id, after.id)
        assertEquals(2_000, after.sizeBytes)
    }

    @Test
    fun `同名视频不同目录是不同记录`() = runTest {
        videoDao.upsertAll(
            listOf(
                video(uri = "content://media/1", path = "Movies", name = "same.mp4"),
                video(uri = "content://media/2", path = "DCIM/Camera", name = "same.mp4"),
            ),
        )
        assertEquals(2, videoDao.getAll().size)
        assertEquals(1, videoDao.observeVideoRows(
            com.vidnest.app.data.repository.VideoRepository.buildQuery(
                com.vidnest.app.domain.model.VideoSortField.NAME,
                folderPath = "Movies",
            ),
        ).first().size)
    }

    @Test
    fun `联表查询正确映射播放记录（回归：Embedded前缀匹配）`() = runTest {
        videoDao.upsertAll(listOf(video(uri = "content://media/1", path = "Movies", name = "a.mp4")))
        val id1 = videoDao.getByUri("content://media/1")!!.id
        recordDao.upsert(
            PlaybackRecordEntity(
                videoId = id1, positionMs = 30_000, durationMs = 60_000, playCount = 7,
                lastPlayedAt = 999, completed = true, lastPlaybackSpeed = 2.0f, updatedAt = 999,
            ),
        )
        val repo = com.vidnest.app.data.repository.VideoRepository(videoDao)
        val videos = repo.observeAllVideos(null, com.vidnest.app.domain.model.VideoSortField.NAME).first()
        assertEquals(1, videos.size)
        val playback = videos.first().playback
        assertNotNull(playback)
        assertEquals(30_000, playback!!.positionMs)
        assertEquals(7, playback.playCount)
        assertEquals(true, playback.completed)
        assertEquals(2.0f, playback.lastPlaybackSpeed!!, 0.001f)
        assertEquals(com.vidnest.app.domain.model.WatchStatus.COMPLETED, videos.first().watchStatus)
    }

    @Test
    fun `根目录层级聚合包含子目录的数量与大小`() = runTest {
        val repo = com.vidnest.app.data.repository.VideoRepository(videoDao)
        videoDao.upsertAll(
            listOf(
                video(uri = "content://media/1", path = "Movies", name = "a.mp4", size = 100),
                video(uri = "content://media/2", path = "Movies/sub", name = "b.mp4", size = 300),
                video(uri = "content://media/3", path = "DCIM/Camera", name = "c.mp4", size = 50),
                // 含 % _ emoji 的目录名（LIKE 通配符与 Unicode 分段回归）
                video(uri = "content://media/4", path = "100%高清/考点+难点 📚", name = "d.mp4", size = 10),
            ),
        )
        val id1 = videoDao.getByUri("content://media/1")!!.id
        recordDao.upsert(
            PlaybackRecordEntity(
                videoId = id1, positionMs = 10, durationMs = 60_000, playCount = 2,
                lastPlayedAt = 555, completed = false, lastPlaybackSpeed = null, updatedAt = 555,
            ),
        )
        val roots = repo.observeRootFolders().first()
        assertEquals(3, roots.size)
        val movies = roots.first { it.displayName == "Movies" }
        assertEquals(2, movies.videoCount) // 含子目录
        assertEquals(400L, movies.totalBytes)
        assertEquals(555L, movies.lastPlayedAtMs)
        assertEquals(1, roots.first { it.displayName == "DCIM" }.videoCount)
        assertEquals(1, roots.first { it.displayName == "100%高清" }.videoCount)

        // 子目录：Movies 下的直接子目录
        val children = repo.observeChildFolders("Movies").first()
        assertEquals(1, children.size)
        assertEquals("Movies/sub", children.first().path)
        assertEquals("sub", children.first().displayName)
        assertEquals(1, children.first().videoCount)
        // 含 % 的路径：子目录查询不受通配符影响
        val tricky = repo.observeChildFolders("100%高清").first()
        assertEquals(1, tricky.size)
        assertEquals("考点+难点 📚", tricky.first().displayName)
    }

    @Test
    fun `根目录无子目录时子目录查询为空`() = runTest {
        val repo = com.vidnest.app.data.repository.VideoRepository(videoDao)
        videoDao.upsertAll(listOf(video(uri = "content://media/1", path = "Movies", name = "a.mp4")))
        assertTrue(repo.observeChildFolders("Movies").first().isEmpty())
    }

    @Test
    fun `MISSING视频不参与文件夹聚合但记录保留`() = runTest {
        val v = video(uri = "content://media/1", path = "Movies", name = "a.mp4")
        videoDao.upsertAll(listOf(v))
        val id = videoDao.getByUri("content://media/1")!!.id
        recordDao.upsert(
            PlaybackRecordEntity(
                videoId = id, positionMs = 42_000, durationMs = 60_000, playCount = 1,
                lastPlayedAt = 1, completed = false, lastPlaybackSpeed = null, updatedAt = 1,
            ),
        )
        videoDao.markMissingByScanBatch(scanBatch = 200, volumeNames = listOf("external_primary"))
        assertEquals(VideoEntity.STATUS_MISSING, videoDao.getById(id)!!.availabilityStatus)
        val repo = com.vidnest.app.data.repository.VideoRepository(videoDao)
        assertEquals(0, repo.observeRootFolders().first().size)
        // 记录仍在
        assertNotNull(recordDao.get(id))
    }

    @Test
    fun `markAvailable恢复可用状态`() = runTest {
        videoDao.upsertAll(listOf(video(uri = "content://media/1", path = "M", name = "a.mp4", scannedAt = 100)))
        videoDao.markMissingByScanBatch(200, listOf("external_primary"))
        assertEquals(1, videoDao.countMissing())
        videoDao.markAvailable(listOf("content://media/1"))
        assertEquals(0, videoDao.countMissing())
    }

    @Test
    fun `清理失效索引删除MISSING行并级联删除记录`() = runTest {
        val a = video(uri = "content://media/1", path = "M", name = "a.mp4", scannedAt = 100)
        val b = video(uri = "content://media/2", path = "M", name = "b.mp4", scannedAt = 150)
        videoDao.upsertAll(listOf(a, b))
        val idA = videoDao.getByUri("content://media/1")!!.id
        val idB = videoDao.getByUri("content://media/2")!!.id
        recordDao.upsert(
            PlaybackRecordEntity(
                videoId = idA, positionMs = 1, durationMs = 60_000, playCount = 1,
                lastPlayedAt = 1, completed = false, lastPlaybackSpeed = null, updatedAt = 1,
            ),
        )
        videoDao.markMissingByScanBatch(200, listOf("external_primary")) // b 也被标记（其 scannedAt=150 < 200）
        // 重新扫描后 a 回来了，b 消失
        videoDao.upsertAll(listOf(a.copy(id = idA, lastScannedAt = 300)))
        assertEquals(1, videoDao.deleteMissing())
        assertNull(videoDao.getById(idB))
        assertNull(recordDao.get(idB))
        assertNotNull(videoDao.getById(idA))
        assertNotNull(recordDao.get(idA))
    }

    @Test
    fun `原始查询排序与搜索正确`() = runTest {
        videoDao.upsertAll(
            listOf(
                video(uri = "content://media/1", path = "M", name = "banana.mp4", size = 30),
                video(uri = "content://media/2", path = "M", name = "apple.mp4", size = 20),
                video(uri = "content://media/3", path = "D", name = "cherry_100%.mp4", size = 10),
            ),
        )
        val repo = com.vidnest.app.data.repository.VideoRepository(videoDao)
        val byName = repo.observeAllVideos(null, com.vidnest.app.domain.model.VideoSortField.NAME).first()
        assertEquals(listOf("apple.mp4", "banana.mp4", "cherry_100%.mp4"), byName.map { it.displayName })
        val bySize = repo.observeAllVideos(null, com.vidnest.app.domain.model.VideoSortField.SIZE).first()
        assertEquals(listOf(30L, 20L, 10L), bySize.map { it.sizeBytes })
        // LIKE 转义：% 是字面量
        val search = repo.observeAllVideos("100%", com.vidnest.app.domain.model.VideoSortField.NAME).first()
        assertEquals(listOf("cherry_100%.mp4"), search.map { it.displayName })
    }

    @Test
    fun `按目录查询只返回该目录`() = runTest {
        videoDao.upsertAll(
            listOf(
                video(uri = "content://media/1", path = "M", name = "a.mp4"),
                video(uri = "content://media/2", path = "D", name = "b.mp4"),
            ),
        )
        val repo = com.vidnest.app.data.repository.VideoRepository(videoDao)
        val rows = repo.observeFolderVideos("M", com.vidnest.app.domain.model.VideoSortField.NAME).first()
        assertEquals(1, rows.size)
        assertEquals("a.mp4", rows.first().displayName)
    }
}
