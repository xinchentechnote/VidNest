package com.vidnest.app.data.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 授权目录数据源（docs/permissions-and-media-scanning.md §2.1）：
 * - 用户通过 OPEN_DOCUMENT_TREE 授权（持久化权限），MediaStore 覆盖不到的目录由此扫描；
 * - 身份 = DocumentsContract URI（mediaStoreId 固定 -1）；目录路径为 tree 内相对路径，不伪造绝对路径；
 * - 授权失效时抛 SecurityException，由扫描器归为失败源（既有数据保留）。
 */
class SafVideoSource(
    private val context: Context,
    private val treeUri: Uri,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) : VideoSource {

    override suspend fun scan(onProgress: (done: Int) -> Unit): SourceScan =
        withContext(Dispatchers.IO) {
            val volume = volumeName(treeUri)
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw SecurityException("SAF 授权不可用")
            if (!root.canRead()) throw SecurityException("SAF 授权已失效")
            val treeName = root.name ?: "saf"
            val rows = mutableListOf<ScannedVideo>()
            traverse(root, prefix = treeName, depth = 0, rows = rows, onProgress = onProgress)
            SourceScan(videos = rows, coveredVolumes = listOf(volume))
        }

    private fun traverse(
        dir: DocumentFile,
        prefix: String,
        depth: Int,
        rows: MutableList<ScannedVideo>,
        onProgress: (done: Int) -> Unit,
    ) {
        if (depth > maxDepth) return
        for (child in dir.listFiles()) {
            if (child.name?.startsWith(".") == true) continue
            if (child.isDirectory) {
                traverse(child, "$prefix/${child.name}", depth + 1, rows, onProgress)
            } else if (child.type?.startsWith("video/") == true) {
                rows += mapDocument(child, prefix)
                if (rows.size % 50 == 0) onProgress(rows.size)
            }
        }
    }

    private fun mapDocument(doc: DocumentFile, prefix: String): ScannedVideo {
        val uri = doc.uri
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            uri.lastPathSegment ?: uri.toString()
        }
        // SAF 无时长/分辨率元数据（避免逐文件 MetadataRetriever 的开销），播放端可正常获取
        return ScannedVideo(
            mediaStoreId = -1,
            contentUri = uri.toString(),
            volumeName = volumeName(treeUri),
            relativePath = prefix,
            displayName = doc.name ?: docId,
            sizeBytes = doc.length(),
            durationMs = 0,
            width = 0,
            height = 0,
            dateModified = doc.lastModified() / 1000,
        )
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 5

        /** SAF volume 命名：saf:<treeUri>，与 MediaStore volume 空间隔离 */
        fun volumeName(treeUri: Uri): String = "saf:$treeUri"
    }
}
