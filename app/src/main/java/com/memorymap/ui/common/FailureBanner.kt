package com.memorymap.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.memorymap.R

/**
 * One line saying that an action failed, under whatever the screen is about.
 *
 * It exists because of what its absence looked like: a delete that failed and said
 * nothing is, to the person who asked for it, a delete that worked and then a row
 * that came back. [messageRes] is a stable resource id rather than text, so the
 * same state renders correctly in Arabic and in English.
 *
 * Dismissible, because the person has usually moved on by the time they read it.
 */
@Composable
fun FailureBanner(messageRes: Int?, onDismiss: () -> Unit) {
    if (messageRes == null) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                )
            }
        }
    }
}
