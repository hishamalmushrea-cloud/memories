package com.memorymap.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.memorymap.R

/**
 * The front door: shown instead of the app while the lock is on and not passed.
 *
 * The credential is the platform's, not ours. Tapping unlock launches the
 * system's own "confirm it is you" screen - the same PIN, pattern, fingerprint
 * or face the device already asks for - and this screen is told only whether it
 * succeeded. The app never sees, stores or could leak the credential, which is
 * the point: a diary that could read the device code would be a worse thing to
 * have on a phone than no diary.
 *
 * A device with no secure lock set has nothing to confirm against, so the user
 * is let in rather than trapped behind a door that cannot open; the setting that
 * brought them here is still theirs to turn off.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Only a confirmed credential opens the app. A cancelled prompt leaves it
        // locked, and the button can be pressed again.
        if (result.resultCode == Activity.RESULT_OK) onUnlocked()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.lock_screen_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.lock_screen_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            // A device with no PIN, pattern or biometric has nothing to confirm against,
            // so the intent is null and the user is let in rather than trapped behind a
            // door that cannot open. The platform confirm screen is deprecated in favour
            // of BiometricPrompt, which would need a dependency this app does not carry.
            @Suppress("DEPRECATION")
            val intent = if (keyguard.isDeviceSecure) {
                keyguard.createConfirmDeviceCredentialIntent(
                    context.getString(R.string.lock_screen_title),
                    context.getString(R.string.lock_screen_message),
                )
            } else {
                null
            }
            if (intent == null) onUnlocked() else launcher.launch(intent)
        }) {
            Text(stringResource(R.string.lock_action_unlock))
        }
    }
}
