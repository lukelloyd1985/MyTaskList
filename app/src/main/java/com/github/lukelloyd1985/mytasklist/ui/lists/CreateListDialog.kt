package com.github.lukelloyd1985.mytasklist.ui.lists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.lukelloyd1985.mytasklist.R
import com.github.lukelloyd1985.mytasklist.data.model.ListVisibility

@Composable
fun CreateListDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, visibility: ListVisibility) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf(ListVisibility.PRIVATE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_list)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_list_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .selectableGroup(),
                ) {
                    Text(stringResource(R.string.label_visibility), style = MaterialTheme.typography.labelLarge)
                    VisibilityOption(
                        label = stringResource(R.string.visibility_option_private),
                        selected = visibility == ListVisibility.PRIVATE,
                        onSelect = { visibility = ListVisibility.PRIVATE },
                    )
                    VisibilityOption(
                        label = stringResource(R.string.visibility_option_shared),
                        selected = visibility == ListVisibility.SHARED,
                        onSelect = { visibility = ListVisibility.SHARED },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim(), visibility) },
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun VisibilityOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
