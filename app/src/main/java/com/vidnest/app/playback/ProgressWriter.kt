package com.vidnest.app.playback

import androidx.room.withTransaction
import com.vidnest.app.data.local.VidNestDatabase
import com.vidnest.app.data.repository.PlaybackRecordRepository
import com.vidnest.app.data.repository.PlaybackSessionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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

    /** 会话累计播放时长落库（崩溃安全）：媒体时长 + 实际墙钟时长 */
    data class SessionTick(
        val sessionId: String,
        val accumulatedMs: Long,
        val wallMs: Long,
        val nowMs: Long,
    ) : WriteRequest

    /** 放弃会话（播放失败等） */
    data class DropSession(val sessionId: String) : WriteRequest
}

/**
 * 串行数据库写入器（docs/architecture.md §4、docs/playback-and-progress.md §2.3）。
 *
 * 防串号设计：
 * 1. 写请求经单一 actor（Channel UNLIMITED + 单消费者）执行——**严格 FIFO**，
 *    不依赖调度器的顺序假设（limitedParallelism 并不保证 FIFO，CI 已实测暴露）；
 * 2. 请求是不可变快照，不引用共享可变状态，UI 协程取消不影响落库；
 * 3. 不同 videoId 各自按主键 upsert，物理上不可能串号；
 * 4. 计数与断点在同一事务内更新。
 */
class ProgressWriter(
    private val db: VidNestDatabase,
    private val recordRepository: PlaybackRecordRepository,
    private val sessionRepository: PlaybackSessionRepository,
) {

    private sealed interface Msg {
        data class Work(val request: WriteRequest) : Msg
        data class Barrier(val done: CompletableDeferred<Unit>) : Msg
    }

    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val channel = Channel<Msg>(Channel.UNLIMITED)
    private val actorScope = CoroutineScope(SupervisorJob() + writeDispatcher)

    init {
        actorScope.launch {
            for (msg in channel) {
                when (msg) {
                    is Msg.Work -> execute(msg.request)
                    is Msg.Barrier -> msg.done.complete(Unit)
                }
            }
        }
    }

    /** 异步提交：立即返回，写入按提交顺序串行执行 */
    fun submit(request: WriteRequest) {
        channel.trySend(Msg.Work(request))
    }

    /**
     * 等待队列中所有已提交请求执行完成（屏障消息在 actor 中排在既有请求之后）。
     * 测试与关键路径使用。
     */
    suspend fun awaitIdle() {
        val barrier = CompletableDeferred<Unit>()
        channel.send(Msg.Barrier(barrier))
        barrier.await()
    }

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
                    wallMs = request.wallMs,
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
