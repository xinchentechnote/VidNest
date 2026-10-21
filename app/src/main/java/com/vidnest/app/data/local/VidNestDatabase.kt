package com.vidnest.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [VideoEntity::class, PlaybackRecordEntity::class, PlaybackSessionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VidNestDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun playbackRecordDao(): PlaybackRecordDao
    abstract fun playbackSessionDao(): PlaybackSessionDao

    companion object {
        const val NAME = "vidnest.db"

        fun build(context: Context): VidNestDatabase =
            Room.databaseBuilder(context.applicationContext, VidNestDatabase::class.java, NAME)
                .build()
    }
}
