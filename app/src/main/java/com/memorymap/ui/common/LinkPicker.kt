package com.memorymap.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One row of a link picker: an existing name that can be linked or unlinked. */
data class LinkOption(val id: String, val name: String)

/**
 * Links a record to people or places.
 *
 * Every existing name is a chip, and tapping one toggles the link. A new name
 * can be created in place, but only when [canCreate] is true: a person needs
 * nothing but a name, while a place also needs coordinates, so offering to
 * create one without them would produce a record the map cannot show.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkPicker(
    label: String,
    options: List<LinkOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    canCreate: Boolean,
    onCreate: (String) -> Unit,
    emptyLabel: String,
    createFieldLabel: String,
    createActionLabel: String,
    modifier: Modifier = Modifier,
) {
    // The draft lives here, not in the view model: it is a few characters on
    // screen and has no meaning once the record is saved.
    var draft by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)

        if (options.isEmpty()) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    FilterChip(
                        selected = option.id in selectedIds,
                        onClick = { onToggle(option.id) },
                        label = { Text(option.name) },
                    )
                }
            }
        }

        if (canCreate) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(createFieldLabel) },
                trailingIcon = {
                    TextButton(onClick = {
                        val name = draft.trim()
                        if (name.isEmpty()) return@TextButton
                        onCreate(name)
                        draft = ""
                    }) {
                        Text(createActionLabel)
                    }
                },
            )
        }
    }
}
