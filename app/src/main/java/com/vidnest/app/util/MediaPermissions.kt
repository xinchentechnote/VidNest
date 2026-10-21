package com.vidnest.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 媒体读权限（docs/permissions-and-media-scanning.md §1）：
 * - API 33+：READ_MEDIA_VIDEO
 * - API 24-32：READ_EXTERNAL_STORAGE
 * 不申请 MANAGE_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE。
 */
object MediaPermissions {

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun isGranted(context: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
