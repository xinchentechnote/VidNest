package com.vidnest.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VideoEntity::class, PlaybackRecordEntity::class, PlaybackSessionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class VidNestDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun playbackRecordDao(): PlaybackRecordDao
    abstract fun playbackSessionDao(): PlaybackSessionDao

    companion object {
        const val NAME = "vidnest.db"

        /** v1 → v2：会话表新增实际墙钟时长列（播放统计用） */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playback_sessions ADD COLUMN wallMs INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun build(context: Context): VidNestDatabase =
            Room.databaseBuilder(context.applicationContext, VidNestDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
