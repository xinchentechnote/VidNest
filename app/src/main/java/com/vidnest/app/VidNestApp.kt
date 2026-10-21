package com.vidnest.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VidNestApp : Application() {

    /** 应用级作用域：扫描等长任务使用；UI 生命周期一律用 viewModelScope */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this, applicationScope)
        ServiceLocator.scanManager.registerMediaStoreObserver()
        // 清理已计数与超过领养窗口的过期会话（崩溃安全计数的一部分）
        applicationScope.launch {
            runCatching {
                ServiceLocator.playbackSessionRepository.cleanup(System.currentTimeMillis())
            }
        }
    }
}
