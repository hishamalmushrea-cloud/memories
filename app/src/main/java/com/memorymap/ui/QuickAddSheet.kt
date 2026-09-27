package com.memorymap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.memorymap.R
import com.memorymap.navigation.Routes

/**
 * The quick-add sheet. The rule it exists to satisfy: adding a simple note must
 * never require opening several screens first.
 *
 * Phase 1 exposes the two text actions, which are fully local. The media actions
 * say out loud that they arrive in Phase 3 rather than pretending to work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.quick_add_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )

            QuickAddRow(
                icon = Icons.Outlined.Place,
                label = stringResource(R.string.quick_add_memory),
                onClick = { onNavigate(Routes.memoryEditor()) },
            )
            QuickAddRow(
                icon = Icons.Outlined.Article,
                label = stringResource(R.string.quick_add_entry),
                onClick = { onNavigate(Routes.entryEditor(date = java.time.LocalDate.now().toString())) },
            )

            // Phase 3 delivers camera, microphone and video capture.
            listOf(
                Icons.Outlined.PhotoCamera to R.string.quick_add_photo,
                Icons.Outlined.GraphicEq to R.string.quick_add_audio,
                Icons.Outlined.Videocam to R.string.quick_add_video,
            ).forEach { (icon, labelRes) ->
                QuickAddRow(
                    icon = icon,
                    label = stringResource(labelRes),
                    trailing = stringResource(R.string.coming_phase_3),
                    onClick = {
                        Toast.makeText(context, context.getString(R.string.coming_phase_3), Toast.LENGTH_SHORT).show()
                    },
                )
            }

            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun QuickAddRow(
    icon: ImageVector,
    label: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
