package com.vidnest.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MediaStore 行映射（含 API 24-28 目录推导）测试（Robolectric 提供真实 MediaStore URI 构造） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreRowMappingTest {

    @Test
    fun `API29目录直接使用RELATIVE_PATH`() {
        val row = MediaStoreVideoSource.mapRow(
            mediaStoreId = 1,
            displayName = "a.mp4",
            sizeBytes = 1,
            durationMs = 1,
            width = 0,
            height = 0,
            dateModified = 1,
            dataPath = "/storage/emulated/0/DCIM/Camera/a.mp4",
            relativePath = "DCIM/Camera/",
            volumeName = "external_primary",
        )
        assertEquals("DCIM/Camera", row.relativePath)
        assertEquals("content://media/external/video/media/1", row.contentUri)
    }

    @Test
    fun `旧版本从DATA推导目录`() {
        val row = MediaStoreVideoSource.mapRow(
            mediaStoreId = 2,
            displayName = "b.mp4",
            sizeBytes = 1,
            durationMs = 1,
            width = 0,
            height = 0,
            dateModified = 1,
            dataPath = "/storage/emulated/0/Movies/movie-b.mp4",
            relativePath = "",
            volumeName = "external_primary",
        )
        assertEquals("Movies", row.relativePath)
    }

    @Test
    fun `根目录文件推导为空目录名`() {
        val folder = MediaStoreVideoSource.computeRelativePathFromData("/storage/emulated/0/a.mp4")
        assertEquals("", folder)
    }

    @Test
    fun `无法推导时归入unknown不伪造路径`() {
        val row = MediaStoreVideoSource.mapRow(
            mediaStoreId = 3,
            displayName = "c.mp4",
            sizeBytes = 1,
            durationMs = 1,
            width = 0,
            height = 0,
            dateModified = 1,
            dataPath = "/mnt/weird/place/c.mp4",
            relativePath = "",
            volumeName = "external_primary",
        )
        assertEquals(MediaStoreVideoSource.UNKNOWN_FOLDER, row.relativePath)
    }

    @Test
    fun `无DATA时也归入unknown`() {
        val folder = MediaStoreVideoSource.computeRelativePathFromData("")
        assertEquals("", folder)
    }
}
