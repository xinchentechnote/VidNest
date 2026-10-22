package com.vidnest.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidnest.app.data.repository.VideoRepository
import com.vidnest.app.domain.model.Video
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.Locale

/** 历史分组：一天一组（今天 / 昨天 / M月d日） */
data class HistoryGroup(
    val dayLabel: String,
    val videos: List<Video>,
)

data class HistoryUiState(
    val entries: List<HistoryGroup> = emptyList(),
)

class HistoryViewModel(
    videoRepository: VideoRepository,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = videoRepository.observeHistory()
        .map { videos -> HistoryUiState(groupByDay(videos)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    companion object {
        fun groupByDay(videos: List<Video>): List<HistoryGroup> {
            val cal = Calendar.getInstance()
            val today = dayKey(cal)
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val yesterday = dayKey(cal)
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)

            return videos
                .filter { it.playback?.lastPlayedAtMs != null }
                .groupBy { video ->
                    val playedCal = Calendar.getInstance().apply {
                        timeInMillis = video.playback!!.lastPlayedAtMs!!
                    }
                    when (dayKey(playedCal)) {
                        today -> "今天"
                        yesterday -> "昨天"
                        else -> if (playedCal.get(Calendar.YEAR) == currentYear) {
                            String.format(Locale.CHINA, "%d月%d日",
                                playedCal.get(Calendar.MONTH) + 1, playedCal.get(Calendar.DAY_OF_MONTH))
                        } else {
                            String.format(Locale.CHINA, "%d年%d月%d日",
                                playedCal.get(Calendar.YEAR), playedCal.get(Calendar.MONTH) + 1,
                                playedCal.get(Calendar.DAY_OF_MONTH))
                        }
                    }
                }
                .map { (label, list) -> HistoryGroup(label, list) }
        }

        private fun dayKey(cal: Calendar): String =
            String.format(Locale.US, "%04d-%03d", cal.get(Calendar.YEAR), cal.get(Calendar.DAY_OF_YEAR))
    }
}
