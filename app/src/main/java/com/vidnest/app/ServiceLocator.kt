package com.vidnest.app

import android.content.Context
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import com.vidnest.app.data.repository.VideoRepository

/**
 * 轻量手工 DI（docs/architecture.md §3）：构造器注入为主，
 * Application 启动时按依赖顺序组装，全应用共享单例。
 */
object ServiceLocator {

    lateinit var database: VidNestDatabase
        private set
    lateinit var videoRepository: VideoRepository
        private set
    lateinit var playbackRecordRepository: PlaybackRecordRepository
        private set
    lateinit var playbackSessionRepository: PlaybackSessionRepository
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            database = VidNestDatabase.build(context)
            videoRepository = VideoRepository(database.videoDao())
            playbackRecordRepository = PlaybackRecordRepository(database, database.playbackRecordDao())
            playbackSessionRepository = PlaybackSessionRepository(database, database.playbackSessionDao())
            initialized = true
        }
    }
}
