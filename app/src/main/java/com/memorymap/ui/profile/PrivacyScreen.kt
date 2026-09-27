package com.memorymap.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.memorymap.R
import com.memorymap.ui.common.rememberLocale
import com.memorymap.util.PrivacyText

/**
 * The privacy policy, readable inside the app.
 *
 * Google Play requires the policy to be reachable from the app itself as well as from the
 * store listing, and this screen is that path: profile, then one button. The text is the
 * document in `docs/legal/`, shipped as an asset and shown with its Markdown markers
 * removed - see [PrivacyText].
 *
 * There is no view model here because there is no state to hold: the document is read once
 * per screen composition from the app's own assets. Nothing is fetched, so the policy is
 * readable on a plane, which is the same promise the rest of the app makes.
 */
@Composable
fun PrivacyScreen() {
    val context = LocalContext.current
    val languageTag = rememberLocale().language

    val text = remember(context, languageTag) {
        PrivacyText.asPlainText(PrivacyText.read(context, languageTag))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (text.isBlank()) {
            // A blank screen where a policy belongs reads as "there is no policy", which
            // would be worse than saying the text could not be opened.
            Text(
                text = stringResource(R.string.privacy_unavailable),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
