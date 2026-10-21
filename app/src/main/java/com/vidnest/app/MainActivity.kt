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
import com.vidnest.app.ui.library.LibraryScreen
import com.vidnest.app.ui.navigation.Routes
import com.vidnest.app.ui.theme.VidNestTheme

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
                            )
                        }
                    }
                }
            }
        }
    }
}
