package com.vidnest.app

import android.content.Context
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.media.DeviceVideoScanner
import com.vidnest.app.data.media.ScanManager
import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import com.vidnest.app.data.repository.VideoRepository
import com.vidnest.app.data.settings.SettingsRepository
import com.vidnest.app.playback.ProgressWriter
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
    lateinit var progressWriter: ProgressWriter
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var scanManager: ScanManager
        private set

    /** PlayerController 等需要 ApplicationContext 的组件使用 */
    lateinit var appContext: Context
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context, applicationScope: CoroutineScope) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            database = VidNestDatabase.build(context)
            videoRepository = VideoRepository(database.videoDao())
            playbackRecordRepository = PlaybackRecordRepository(database, database.playbackRecordDao())
            playbackSessionRepository = PlaybackSessionRepository(database, database.playbackSessionDao())
            progressWriter = ProgressWriter(database, playbackRecordRepository, playbackSessionRepository)
            settingsRepository = SettingsRepository(context)
            val videoDao = database.videoDao()
            val recordDao = database.playbackRecordDao()
            scanManager = ScanManager(
                context = context,
                settingsRepository = settingsRepository,
                appScope = applicationScope,
            ) { sources ->
                DeviceVideoScanner(
                    db = database,
                    videoDao = videoDao,
                    recordDao = recordDao,
                    sources = sources,
                )
            }
            initialized = true
        }
    }
}
