package com.vidnest.app.data.media

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/** 扫描产出的一行视频数据（与存储方式无关的中间模型） */
data class ScannedVideo(
    val mediaStoreId: Long,
    val contentUri: String,
    val volumeName: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val dateModified: Long,
)

/** 单个数据源的扫描结果：rows + 覆盖的 volume 列表（即使 0 行也要上报，供缺失标记界定范围） */
data class SourceScan(
    val videos: List<ScannedVideo>,
    val coveredVolumes: List<String>,
)

/**
 * 视频数据源抽象：MediaStore 为主，SAF 授权目录为可选增强（阶段6接入）。
 * 实现必须是 suspend/IO 安全的，且不得伪造路径：取不到目录时归入 unknown。
 */
interface VideoSource {
    /** 扫描全部视频；onProgress 以源内已处理行数回调（用于 UI 进度） */
    suspend fun scan(onProgress: (done: Int) -> Unit = {}): SourceScan
}

/**
 * 内容 URI 构造。使用 MediaStore 的公开 URI 契约：
 * internal → content://media/internal/video/media/{id}
 * 其他 volume → content://media/{volume}/video/media/{id}
 * （external_primary 在 URI 中的历史路径段为 "external"，与系统 EXTERNAL_CONTENT_URI 一致）
 */
object ContentUriBuilder {
    fun contentUri(volume: String, id: Long): String {
        val volumePath = when (volume) {
            MediaStore.VOLUME_INTERNAL -> "internal"
            MediaStore.VOLUME_EXTERNAL, MediaStore.VOLUME_EXTERNAL_PRIMARY -> "external"
            else -> volume
        }
        val base = Uri.parse("content://media/$volumePath/video/media")
        return ContentUris.appendId(base.buildUpon(), id).toString()
    }
}
