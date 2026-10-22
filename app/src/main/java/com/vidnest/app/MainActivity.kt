package com.vidnest.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vidnest.app.data.media.ScanService
import com.vidnest.app.ui.detail.VideoDetailScreen
import com.vidnest.app.ui.folder.FolderScreen
import com.vidnest.app.ui.history.HistoryScreen
import com.vidnest.app.ui.library.LibraryScreen
import com.vidnest.app.ui.navigation.Routes
import com.vidnest.app.ui.player.PlayerScreen
import com.vidnest.app.ui.settings.SettingsScreen
import com.vidnest.app.ui.stats.StatsScreen
import com.vidnest.app.ui.theme.VidNestTheme
import com.vidnest.app.ui.videos.AllVideosScreen
import com.vidnest.app.util.MediaPermissions

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VidNestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    VidNestApp()
                }
            }
        }
    }

    @Composable
    private fun VidNestApp() {
        val context = LocalContext.current
        var permissionGranted by remember { mutableStateOf(MediaPermissions.isGranted(context)) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            permissionGranted = result.values.all { it }
            if (permissionGranted) {
                ScanService.start(context, auto = true)
            }
        }

        // 首次进入：未授权则请求
        LaunchedEffect(Unit) {
            if (!permissionGranted) {
                permissionLauncher.launch(MediaPermissions.requiredPermissions())
            } else {
                ScanService.start(context, auto = true)
            }
        }

        if (!permissionGranted) {
            PermissionGate(
                onRequest = { permissionLauncher.launch(MediaPermissions.requiredPermissions()) },
            )
            return
        }

        val navController = rememberNavController()
        val bottomTabs = listOf(
            BottomTab(Routes.TAB_ALL, "全部", Icons.Filled.PlayCircle),
            BottomTab(Routes.TAB_FOLDERS, "文件夹", Icons.Filled.Folder),
            BottomTab(Routes.TAB_HISTORY, "播放历史", Icons.Filled.History),
            BottomTab(Routes.TAB_STATS, "播放统计", Icons.Filled.BarChart),
        )
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val showBottomBar = currentRoute in bottomTabs.map { it.route }

        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        bottomTabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                VidNestNavHost(navController)
            }
        }
    }

    @Composable
    private fun VidNestNavHost(navController: NavHostController) {
        NavHost(
            navController = navController,
            startDestination = Routes.TAB_ALL,
        ) {
            composable(Routes.TAB_ALL) {
                AllVideosScreen(
                    onVideoClick = { videoId -> navController.navigate(Routes.player(videoId)) },
                    onVideoInfoClick = { videoId -> navController.navigate(Routes.detail(videoId)) },
                    onOpenFolderClick = { folderPath -> navController.navigate(Routes.folder(folderPath)) },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.TAB_FOLDERS) {
                LibraryScreen(
                    onFolderClick = { path -> navController.navigate(Routes.folder(path)) },
                    onSubFolderClick = { path -> navController.navigate(Routes.folder(path)) },
                )
            }
            composable(Routes.TAB_HISTORY) {
                HistoryScreen(
                    onVideoClick = { videoId -> navController.navigate(Routes.player(videoId)) },
                    onVideoInfoClick = { videoId -> navController.navigate(Routes.detail(videoId)) },
                )
            }
            composable(Routes.TAB_STATS) {
                StatsScreen()
            }
            composable(Routes.folderPattern()) { entry ->
                val path = entry.arguments?.getString("path").orEmpty()
                FolderScreen(
                    folderPath = android.net.Uri.decode(path),
                    onBack = { navController.popBackStack() },
                    onVideoClick = { videoId -> navController.navigate(Routes.player(videoId)) },
                    onVideoInfoClick = { videoId -> navController.navigate(Routes.detail(videoId)) },
                    onSubFolderClick = { subPath -> navController.navigate(Routes.folder(subPath)) },
                )
            }
            composable(Routes.detailPattern()) { entry ->
                val videoId = entry.arguments?.getString("videoId")?.toLongOrNull() ?: 0L
                VideoDetailScreen(
                    videoId = videoId,
                    onBack = { navController.popBackStack() },
                    onPlay = { navController.navigate(Routes.player(videoId)) },
                )
            }
            composable(Routes.playerPattern()) { entry ->
                val videoId = entry.arguments?.getString("videoId")?.toLongOrNull() ?: 0L
                PlayerScreen(
                    videoId = videoId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private data class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** 未授权时的全屏引导（所有页面统一拦截） */
@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.VideoLibrary,
                contentDescription = null,
                modifier = Modifier.padding(bottom = 12.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("需要访问视频文件", style = MaterialTheme.typography.titleMedium)
            Text(
                "VidNest 是完全离线的本地视频播放器，仅读取设备上的视频列表用于浏览和播放，"
                    + "不会修改或上传任何文件。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            androidx.compose.material3.Button(onClick = onRequest) {
                Text("授权视频访问")
            }
        }
    }
}
