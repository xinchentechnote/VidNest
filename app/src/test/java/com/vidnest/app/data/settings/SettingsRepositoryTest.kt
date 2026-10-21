package com.vidnest.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vidnest.app.domain.model.EndBehavior
import com.vidnest.app.domain.model.ResumeBehavior
import com.vidnest.app.domain.model.VideoSortField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地设置存取（DataStore）。
 * 注意：DataStore 单例按进程缓存，测试间状态共享，因此写入顺序测试合并为一个方法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = SettingsRepository(context)

    @Test
    fun `默认值_写入读取_SAF增删`() = runTest {
        // 默认值
        assertEquals(VideoSortField.NAME, repo.defaultSort.first())
        assertEquals(ResumeBehavior.ALWAYS, repo.resumeBehavior.first())
        assertEquals(EndBehavior.FROM_START, repo.endBehavior.first())
        assertTrue(repo.rememberSpeed.first())
        assertEquals(1.0f, repo.playbackSpeed.first(), 0.001f)
        assertTrue(repo.safTrees.first().isEmpty())

        // 写入后读取一致
        repo.setDefaultSort(VideoSortField.LAST_PLAYED)
        repo.setResumeBehavior(ResumeBehavior.ASK)
        repo.setEndBehavior(EndBehavior.ASK)
        repo.setRememberSpeed(false)
        repo.setPlaybackSpeed(2.5f)
        assertEquals(VideoSortField.LAST_PLAYED, repo.defaultSort.first())
        assertEquals(ResumeBehavior.ASK, repo.resumeBehavior.first())
        assertEquals(EndBehavior.ASK, repo.endBehavior.first())
        assertEquals(false, repo.rememberSpeed.first())
        assertEquals(2.5f, repo.playbackSpeed.first(), 0.001f)

        // SAF 目录集合增删
        val treeA = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        val treeB = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        repo.addSafTree(treeA)
        repo.addSafTree(treeB)
        assertEquals(2, repo.safTrees.first().size)
        repo.removeSafTree(treeA)
        assertEquals(setOf(treeB), repo.safTrees.first())
    }
}
