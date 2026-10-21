package com.vidnest.app.ui.navigation

/** 路由定义（navigation-compose） */
object Routes {
    const val LIBRARY = "library"
    const val ALL_VIDEOS = "allVideos"
    const val SETTINGS = "settings"

    fun folder(path: String) = "folder/${android.net.Uri.encode(path)}"
    fun folderPattern() = "folder/{path}"

    fun detail(videoId: Long) = "detail/$videoId"
    fun detailPattern() = "detail/{videoId}"

    fun player(videoId: Long) = "player/$videoId"
    fun playerPattern() = "player/{videoId}"
}
