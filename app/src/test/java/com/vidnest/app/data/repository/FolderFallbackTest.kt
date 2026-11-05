package com.vidnest.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.local.VideoEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 文件夹页自愈兜底：按目录名重定位真实路径（路径字符串不一致时） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderFallbackTest {

    private lateinit var db: VidNestDatabase
    private lateinit var repo: VideoRepository

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VidNestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = VideoRepository(db.videoDao())
        db.videoDao().upsertAll(
            listOf(
                videoRow(1, "M/download", "s1.mp4"),
                videoRow(2, "M/download", "s2.mp4"),
                videoRow(3, "M/download/sub", "s3.mp4"),
                videoRow(4, "M/其他/download", "s4.mp4"),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun videoRow(id: Long, path: String, name: String) = VideoEntity(
        id = id, volumeName = "v", mediaStoreId = id, contentUri = "content://test/$id",
        relativePath = path, displayName = name, sizeBytes = 1, durationMs = 60_000,
        width = 0, height = 0, dateModified = 1, lastScannedAt = 1,
    )

    @Test
    fun `按目录名找到最顶层匹配路径`() = runTest {
        // 传入路径损坏（如 "M/downlod"）时按名兜底 → 找到 "M/download"（最短）
        assertEquals("M/download", repo.findFolderPathByName("download"))
    }

    @Test
    fun `找不到时返回null`() = runTest {
        assertNull(repo.findFolderPathByName("不存在的目录"))
    }

    @Test
    fun `兜底路径下的内容查询正常`() = runTest {
        val path = repo.findFolderPathByName("download")!!
        val videos = repo.observeFolderVideos(path, com.vidnest.app.domain.model.VideoSortField.NAME).first()
        assertEquals(2, videos.size) // M/download 直含 s1/s2
        val subs = repo.observeChildFolders(path).first()
        assertEquals(1, subs.size)   // sub
        assertEquals("M/download/sub", subs.first().path)
    }
}
