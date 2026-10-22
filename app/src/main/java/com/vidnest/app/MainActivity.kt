package com.vidnest.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vidnest.app.ui.detail.VideoDetailScreen
import com.vidnest.app.ui.folder.FolderScreen
import com.vidnest.app.ui.library.LibraryScreen
import com.vidnest.app.ui.navigation.Routes
import com.vidnest.app.ui.player.PlayerScreen
import com.vidnest.app.ui.settings.SettingsScreen
import com.vidnest.app.ui.theme.VidNestTheme
import com.vidnest.app.ui.videos.AllVideosScreen

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
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = Routes.LIBRARY,
                    ) {
                        composable(Routes.LIBRARY) {
                            LibraryScreen(
                                onFolderClick = { path ->
                                    navController.navigate(Routes.folder(path))
                                },
                                onAllVideosClick = {
                                    navController.navigate(Routes.ALL_VIDEOS)
                                },
                                onSettingsClick = {
                                    navController.navigate(Routes.SETTINGS)
                                },
                            )
                        }
                        composable(Routes.folderPattern()) { entry ->
                            val path = entry.arguments?.getString("path").orEmpty()
                            FolderScreen(
                                folderPath = android.net.Uri.decode(path),
                                onBack = { navController.popBackStack() },
                                onVideoClick = { videoId ->
                                    navController.navigate(Routes.player(videoId))
                                },
                                onVideoInfoClick = { videoId ->
                                    navController.navigate(Routes.detail(videoId))
                                },
                                onSubFolderClick = { subPath ->
                                    navController.navigate(Routes.folder(subPath))
                                },
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Routes.ALL_VIDEOS) {
                            AllVideosScreen(
                                onBack = { navController.popBackStack() },
                                onVideoClick = { videoId ->
                                    navController.navigate(Routes.player(videoId))
                                },
                                onVideoInfoClick = { videoId ->
                                    navController.navigate(Routes.detail(videoId))
                                },
                                onOpenFolderClick = { folderPath ->
                                    navController.navigate(Routes.folder(folderPath))
                                },
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
                    }
                }
            }
        }
    }
}
