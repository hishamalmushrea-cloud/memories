package com.memorymap

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.memorymap.domain.usecase.ShareCapture
import com.memorymap.ui.MemoryMapRoot
import com.memorymap.ui.share.PendingShare
import com.memorymap.ui.theme.MemoryMapTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity app. Everything below this point is Compose.
 *
 * It is also a share target: another app can hand it a run of plain text, which
 * is parked in [PendingShare] here and picked up by the editor once the user is
 * through the lock and sign-in gates. The parsing is delegated to [ShareCapture]
 * so the activity stays a thin Android edge and the decision stays testable.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var pendingShare: PendingShare

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureShare(intent)
        setContent {
            MemoryMapTheme {
                MemoryMapRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The activity is singleTop, so a share arriving while the app is already
        // open comes here rather than to a fresh onCreate. setIntent keeps
        // getIntent() truthful for anything that reads it later.
        setIntent(intent)
        captureShare(intent)
    }

    /** Parks a plain-text share, if that is what this intent carries. */
    private fun captureShare(intent: Intent?) {
        val decision = ShareCapture.from(
            action = intent?.action,
            mimeType = intent?.type,
            sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT),
        )
        if (decision is ShareCapture.Decision.CaptureText) {
            pendingShare.set(decision.text)
        }
    }
}
