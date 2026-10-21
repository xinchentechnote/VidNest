package com.vidnest.app.playback

import androidx.room.withTransaction
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 数据库写入请求。入队时刻即捕获完整快照，与调用方协程生命周期无关。 */
sealed interface WriteRequest {
    /** 定期/暂停/切换/退出时保存断点 */
    data class SaveProgress(
        val videoId: Long,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float?,
        val nowMs: Long,
    ) : WriteRequest

    /** 播放结束：completed 置位、断点归零（下次从头） */
    data class MarkCompleted(
        val videoId: Long,
        val speed: Float?,
        val nowMs: Long,
    ) : WriteRequest

    /** 会话达到计数阈值：playCount+1 与断点在同一事务落库（每会话至多一次） */
    data class CountReached(
        val videoId: Long,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float?,
        val sessionId: String,
        val nowMs: Long,
    ) : WriteRequest

    /** 会话累计播放时长落库（崩溃安全） */
    data class SessionTick(
        val sessionId: String,
        val accumulatedMs: Long,
        val nowMs: Long,
    ) : WriteRequest

    /** 放弃会话（播放失败等） */
    data class DropSession(val sessionId: String) : WriteRequest
}

/**
 * 串行数据库写入器（docs/architecture.md §4、docs/playback-and-progress.md §2.3）。
 *
 * 防串号设计：
 * 1. 所有断点/会话写请求经单一串行调度器（limitedParallelism(1)），顺序确定；
 * 2. 请求是不可变快照，不引用共享可变状态，UI 协程取消不影响落库；
 * 3. 不同 videoId 各自按主键 upsert，物理上不可能串号；
 * 4. 计数与断点在同一事务内更新。
 */
class ProgressWriter(
    private val db: VidNestDatabase,
    private val recordRepository: PlaybackRecordRepository,
    private val sessionRepository: PlaybackSessionRepository,
) {

    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val writeScope = CoroutineScope(SupervisorJob() + writeDispatcher)

    /** 异步提交：立即返回，写入按提交顺序串行执行 */
    fun submit(request: WriteRequest) {
        writeScope.launch { execute(request) }
    }

    /** 同步等待写入完成（测试与关键路径使用） */
    suspend fun submitAndWait(request: WriteRequest) =
        withContext(writeDispatcher) { execute(request) }

    private suspend fun execute(request: WriteRequest) {
        try {
            when (request) {
                is WriteRequest.SaveProgress -> recordRepository.saveProgress(
                    videoId = request.videoId,
                    positionMs = request.positionMs,
                    durationMs = request.durationMs,
                    speed = request.speed,
                    nowMs = request.nowMs,
                )

                is WriteRequest.MarkCompleted -> recordRepository.markCompleted(
                    videoId = request.videoId,
                    speed = request.speed,
                    nowMs = request.nowMs,
                )

                is WriteRequest.CountReached -> db.withTransaction {
                    recordRepository.applyCountAndProgress(
                        videoId = request.videoId,
                        positionMs = request.positionMs,
                        durationMs = request.durationMs,
                        speed = request.speed,
                        nowMs = request.nowMs,
                    )
                    sessionRepository.markCounted(request.sessionId, request.nowMs)
                }

                is WriteRequest.SessionTick -> sessionRepository.updateAccumulated(
                    sessionId = request.sessionId,
                    accumulatedMs = request.accumulatedMs,
                    nowMs = request.nowMs,
                )

                is WriteRequest.DropSession -> sessionRepository.delete(request.sessionId)
            }
        } catch (t: Throwable) {
            // 单次写失败不影响应用运行；下一个保存周期会重写最新位置
            android.util.Log.w("ProgressWriter", "progress write failed: ${request::class.simpleName}", t)
        }
    }
}
