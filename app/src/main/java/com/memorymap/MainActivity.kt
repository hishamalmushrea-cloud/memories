package com.memorymap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.memorymap.ui.MemoryMapRoot
import com.memorymap.ui.theme.MemoryMapTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity app. Everything below this point is Compose.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MemoryMapTheme {
                MemoryMapRoot()
            }
        }
    }
}
