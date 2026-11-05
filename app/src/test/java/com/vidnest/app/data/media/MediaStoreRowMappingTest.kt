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
    fun `目录优先从DATA推导_与文件管理器一致`() {
        // RELATIVE_PATH 可能因网盘移动文件而过期（华为实测），DATA 才是真实位置
        val row = MediaStoreVideoSource.mapRow(
            mediaStoreId = 1,
            displayName = "a.mp4",
            sizeBytes = 1,
            durationMs = 1,
            width = 0,
            height = 0,
            dateModified = 1,
            dataPath = "/storage/emulated/0/Download/BaiduNetdisk/2024/a.mp4",
            relativePath = "Movies/BaiduNetdisk/smooth_video_480p/download/",
            volumeName = "external_primary",
        )
        assertEquals("Download/BaiduNetdisk/2024", row.relativePath)
    }

    @Test
    fun `DATA推导失败时回落RELATIVE_PATH`() {
        val row = MediaStoreVideoSource.mapRow(
            mediaStoreId = 1,
            displayName = "a.mp4",
            sizeBytes = 1,
            durationMs = 1,
            width = 0,
            height = 0,
            dateModified = 1,
            dataPath = "/mnt/unknown_volume/a.mp4",
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
