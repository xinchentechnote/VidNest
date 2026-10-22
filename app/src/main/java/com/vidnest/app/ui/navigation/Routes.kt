package com.vidnest.app.ui.navigation

/** 路由定义（navigation-compose） */
object Routes {
    const val LIBRARY = "library"
    const val ALL_VIDEOS = "allVideos"
    const val SETTINGS = "settings"

    // 底部导航页签（默认首页 = 全部）
    const val TAB_ALL = "tab_all"
    const val TAB_FOLDERS = "tab_folders"
    const val TAB_HISTORY = "tab_history"
    const val TAB_STATS = "tab_stats"

    fun folder(path: String) = "folder/${android.net.Uri.encode(path)}"
    fun folderPattern() = "folder/{path}"

    fun detail(videoId: Long) = "detail/$videoId"
    fun detailPattern() = "detail/{videoId}"

    fun player(videoId: Long) = "player/$videoId"
    fun playerPattern() = "player/{videoId}"
}
