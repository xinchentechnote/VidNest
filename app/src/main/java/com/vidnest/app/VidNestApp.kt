package com.vidnest.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VidNestApp : Application() {

    /** 应用级作用域：扫描等长任务使用；UI 生命周期一律用 viewModelScope */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
