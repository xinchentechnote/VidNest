package com.vidnest.app

import android.content.Context
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.media.DeviceVideoScanner
import com.vidnest.app.data.media.MediaStoreVideoSource
import com.vidnest.app.data.media.ScanManager
import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import com.vidnest.app.data.repository.VideoRepository
import kotlinx.coroutines.CoroutineScope

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
    lateinit var scanManager: ScanManager
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context, applicationScope: CoroutineScope) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            database = VidNestDatabase.build(context)
            videoRepository = VideoRepository(database.videoDao())
            playbackRecordRepository = PlaybackRecordRepository(database, database.playbackRecordDao())
            playbackSessionRepository = PlaybackSessionRepository(database, database.playbackSessionDao())
            val scanner = DeviceVideoScanner(
                db = database,
                videoDao = database.videoDao(),
                recordDao = database.playbackRecordDao(),
                sources = listOf(MediaStoreVideoSource(context)),
            )
            scanManager = ScanManager(context, scanner, applicationScope)
            initialized = true
        }
    }
}
