package com.vidnest.app.data.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.vidnest.app.util.MediaPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫描编排：状态机 + 单飞（同一时刻至多一次扫描）+ 冷启动一次自动扫描 + MediaStore 变更监听。
 * 扫描在应用级作用域执行，离开页面不取消。
 */
class ScanManager(
    private val context: Context,
    private val scanner: DeviceVideoScanner,
    private val appScope: CoroutineScope,
) {

    sealed interface State {
        data object Idle : State
        data class Scanning(val foundSoFar: Int) : State
        data class Completed(val result: DeviceVideoScanner.Result) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scanInFlight = AtomicBoolean(false)

    /** 冷启动后仅自动扫描一次；手动重扫不受限 */
    @Volatile
    private var autoScannedThisProcess = false

    private var observerJob: Job? = null

    fun startScan(auto: Boolean = false) {
        if (auto && autoScannedThisProcess) return
        if (!scanInFlight.compareAndSet(false, true)) return
        appScope.launch {
            try {
                if (!MediaPermissions.isGranted(context)) {
                    _state.value = State.Failed("尚未授予视频访问权限")
                    return@launch
                }
                _state.value = State.Scanning(0)
                val result = scanner.fullScan { found ->
                    _state.value = State.Scanning(found)
                }
                autoScannedThisProcess = true
                _state.value = if (result.totalFound == 0 && result.hasFailures) {
                    State.Failed(result.failedSources.first())
                } else {
                    State.Completed(result)
                }
            } catch (t: Throwable) {
                // 任何异常都不允许清空已有索引：数据库写入在事务中，失败即回滚
                _state.value = State.Failed(t.message ?: "扫描失败")
            } finally {
                scanInFlight.set(false)
            }
        }
    }

    /** 监听 MediaStore 变更，防抖后增量重扫（docs §2.2：绝不在进页面时扫描） */
    fun registerMediaStoreObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scheduleDebouncedScan()
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
    }

    private fun scheduleDebouncedScan() {
        observerJob?.cancel()
        observerJob = appScope.launch {
            delay(DEBOUNCE_MS)
            if (MediaPermissions.isGranted(context)) {
                startScan(auto = false)
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 3_000L
    }
}
