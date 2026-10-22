package com.vidnest.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room 迁移测试：v1 → v2（playback_sessions 新增 wallMs，默认 0，旧数据保留） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val testDb = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VidNestDatabase::class.java,
    )

    @Test
    fun migrate1To2_keepsSessionsAndAddsWallMs() {
        // 创建 v1 库并写入数据
        helper.createDatabase(testDb, 1).apply {
            execSQL(
                "INSERT INTO videos (volumeName, mediaStoreId, contentUri, relativePath, " +
                    "displayName, sizeBytes, durationMs, width, height, dateModified, lastScannedAt, " +
                    "availabilityStatus) VALUES " +
                    "('v', 1, 'content://test/1', 'M', 'a.mp4', 100, 60000, 0, 0, 1, 1, 0)",
            )
            execSQL(
                "INSERT INTO playback_sessions (sessionId, videoId, accumulatedMs, counted, " +
                    "startedAt, updatedAt) VALUES ('s1', 1, 5000, 1, 100, 200)",
            )
        }

        // 执行迁移并校验 v2 schema
        val db = helper.runMigrationsAndValidate(testDb, 2, true, VidNestDatabase.MIGRATION_1_2)

        // 旧会话保留，wallMs 填默认值 0
        db.query("SELECT accumulatedMs, wallMs, counted FROM playback_sessions WHERE sessionId = 's1'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(5000L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            assertEquals(1, cursor.getInt(2))
        }
        db.close()
    }
}
