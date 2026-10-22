package com.vidnest.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidnest.app.data.local.PerVideoStatsRow
import com.vidnest.app.data.local.UsageStatsRow
import com.vidnest.app.data.repository.PlaybackSessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 统计周期粒度 */
enum class StatsPeriod(val label: String, val sinceMs: Long) {
    DAY("按天", 30L * 24 * 60 * 60 * 1000),
    WEEK("按周", 26L * 7 * 24 * 60 * 60 * 1000),
    MONTH("按月", 730L * 24 * 60 * 60 * 1000),
}

/** 统计视图：按时间 / 按视频 */
enum class StatsMode(val label: String) {
    TIME("按时间"),
    VIDEO("按视频"),
}

/** 一行统计：媒体时长（倍速后消耗的内容时长）与实际墙钟时长 */
data class StatsRowUi(
    val label: String,
    val mediaMs: Long,
    val wallMs: Long,
    val sessionCount: Int,
    val extra: String = "",
)

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.DAY,
    val mode: StatsMode = StatsMode.TIME,
    val rows: List<StatsRowUi> = emptyList(),
    val totalMediaMs: Long = 0,
    val totalWallMs: Long = 0,
    val hasData: Boolean = false,
) {
    /** 平均倍速 = 媒体时长 / 实际时长 */
    val averageSpeed: Float
        get() = if (totalWallMs > 0) totalMediaMs.toFloat() / totalWallMs else 1f
}

class StatsViewModel(
    private val sessionRepository: PlaybackSessionRepository,
) : ViewModel() {

    private val period = MutableStateFlow(StatsPeriod.DAY)
    private val mode = MutableStateFlow(StatsMode.TIME)

    private val timeRows = period.flatMapLatest { p ->
        val since = System.currentTimeMillis() - p.sinceMs
        when (p) {
            StatsPeriod.DAY -> sessionRepository.observeDailyStats(since)
            StatsPeriod.WEEK -> sessionRepository.observeWeeklyStats(since)
            StatsPeriod.MONTH -> sessionRepository.observeMonthlyStats(since)
        }
    }

    private val videoRows = sessionRepository.observePerVideoStats()

    val uiState: StateFlow<StatsUiState> = combine(
        timeRows,
        videoRows,
        period,
        mode,
    ) { time, perVideo, p, m ->
        val rows = when (m) {
            StatsMode.TIME -> time.map { StatsRowUi(bucketLabel(p, it.bucket), it.mediaMs, it.wallMs, it.sessionCount) }
            StatsMode.VIDEO -> perVideo.map {
                StatsRowUi(it.displayName, it.mediaMs, it.wallMs, it.sessionCount, "倍速观看")
            }
        }
        StatsUiState(
            period = p,
            mode = m,
            rows = rows,
            totalMediaMs = rows.sumOf { it.mediaMs },
            totalWallMs = rows.sumOf { it.wallMs },
            hasData = rows.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun onPeriodSelected(value: StatsPeriod) {
        period.value = value
    }

    fun onModeSelected(value: StatsMode) {
        mode.value = value
    }

    private fun bucketLabel(period: StatsPeriod, bucket: String): String = when (period) {
        StatsPeriod.DAY -> {
            val parts = bucket.split("-")
            "${parts.getOrNull(1)?.toInt() ?: 0}月${parts.getOrNull(2)?.toInt() ?: 0}日"
        }
        StatsPeriod.WEEK -> {
            val parts = bucket.split("-W")
            "${parts.getOrNull(0) ?: bucket}年 第${parts.getOrNull(1) ?: "?"}周"
        }
        StatsPeriod.MONTH -> {
            val parts = bucket.split("-")
            "${parts.getOrNull(0) ?: bucket}年${parts.getOrNull(1)?.toInt() ?: 0}月"
        }
    }
}
