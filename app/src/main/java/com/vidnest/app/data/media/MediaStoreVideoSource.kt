package com.vidnest.app.data.media

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaStore 数据源（docs/permissions-and-media-scanning.md §2.1）：
 * - API 29+：按 volume 查询，RELATIVE_PATH 直接取目录；
 * - API 24-28：查 EXTERNAL_CONTENT_URI，目录由 DATA 绝对路径推导；
 * - 单个 volume 查询失败只影响该 volume（由 Scanner 兜底分类错误）。
 */
class MediaStoreVideoSource(private val context: Context) : VideoSource {

    override suspend fun scan(onProgress: (done: Int) -> Unit): SourceScan =
        withContext(Dispatchers.IO) {
            val rows = mutableListOf<ScannedVideo>()
            val volumes = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                for (volume in MediaStore.getExternalVolumeNames(context)) {
                    val base = MediaStore.Video.Media.getContentUri(volume)
                    rows += queryVolume(base, volume, rows.size, onProgress)
                    volumes.add(volume)
                }
            } else {
                rows += queryVolume(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    0,
                    onProgress,
                )
                volumes.add(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            SourceScan(videos = rows, coveredVolumes = volumes)
        }

    private fun queryVolume(
        baseUri: android.net.Uri,
        volumeName: String,
        doneBefore: Int,
        onProgress: (done: Int) -> Unit,
    ): List<ScannedVideo> {
        val projection = buildProjection()
        val rows = mutableListOf<ScannedVideo>()
        context.contentResolver.query(baseUri, projection, null, null, null)?.use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val idxName = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val idxSize = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val idxDuration = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val idxWidth = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val idxHeight = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val idxModified = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val idxData = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val idxRelativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            } else {
                -1
            }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idxId)
                val relativePath = if (idxRelativePath >= 0) {
                    cursor.getString(idxRelativePath)?.trimEnd('/') ?: ""
                } else {
                    ""
                }
                val dataPath = cursor.getString(idxData) ?: ""
                rows += mapRow(
                    mediaStoreId = id,
                    displayName = cursor.getString(idxName) ?: "",
                    sizeBytes = cursor.getLong(idxSize),
                    durationMs = cursor.getLong(idxDuration),
                    width = cursor.getInt(idxWidth),
                    height = cursor.getInt(idxHeight),
                    dateModified = cursor.getLong(idxModified),
                    dataPath = dataPath,
                    relativePath = relativePath,
                    volumeName = volumeName,
                )
                if (rows.size % 100 == 0) onProgress(doneBefore + rows.size)
            }
            onProgress(doneBefore + rows.size)
        }
        return rows
    }

    private fun buildProjection(): Array<String> {
        val base = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATA,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            base + MediaStore.Video.Media.RELATIVE_PATH
        } else {
            base
        }
    }

    companion object {
        /** API 24-28 从 DATA 推导相对目录的候选存储根 */
        val STORAGE_ROOTS: List<String> = listOf(
            "/storage/emulated/0",
            "/sdcard",
        )

        fun mapRow(
            mediaStoreId: Long,
            displayName: String,
            sizeBytes: Long,
            durationMs: Long,
            width: Int,
            height: Int,
            dateModified: Long,
            dataPath: String,
            relativePath: String,
            volumeName: String,
        ): ScannedVideo {
            val folder = relativePath.trimEnd('/').ifBlank {
                computeRelativePathFromData(dataPath)
            }
            return ScannedVideo(
                mediaStoreId = mediaStoreId,
                contentUri = ContentUriBuilder.contentUri(volumeName, mediaStoreId),
                volumeName = volumeName,
                relativePath = folder.ifBlank { UNKNOWN_FOLDER },
                displayName = displayName,
                sizeBytes = sizeBytes,
                durationMs = durationMs,
                width = width,
                height = height,
                dateModified = dateModified,
            )
        }

        /** /storage/emulated/0/DCIM/Camera/v.mp4 → DCIM/Camera；推导失败归入 unknown（不伪造路径） */
        fun computeRelativePathFromData(
            dataPath: String,
            roots: List<String> = STORAGE_ROOTS,
        ): String {
            val dir = dataPath.substringBeforeLast('/', "")
            if (dir.isEmpty()) return ""
            for (root in roots) {
                if (dir == root) return ""
                if (dir.startsWith("$root/")) return dir.removePrefix("$root/")
            }
            return UNKNOWN_FOLDER
        }

        const val UNKNOWN_FOLDER = "unknown"
    }
}
