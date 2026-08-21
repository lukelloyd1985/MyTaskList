package com.mytasks.app.ui.listdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date
import com.mytasks.app.data.model.ListMember
import com.mytasks.app.data.model.TaskItem
import com.mytasks.app.data.model.TaskPriority
import com.mytasks.app.util.DateTimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    initialTask: TaskItem?,
    assignableMembers: List<ListMember>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (
        taskId: String?,
        title: String,
        description: String,
        assigneeId: String,
        assigneeName: String,
        priority: TaskPriority,
        dueAt: Date?,
        notify: Boolean,
    ) -> Unit,
) {
    var title by remember { mutableStateOf(initialTask?.title.orEmpty()) }
    var description by remember { mutableStateOf(initialTask?.description.orEmpty()) }
    var priority by remember { mutableStateOf(initialTask?.priority ?: TaskPriority.MEDIUM) }
    var dueAt by remember { mutableStateOf(initialTask?.dueAt) }
    var notify by remember { mutableStateOf(initialTask?.notify ?: false) }
    var assigneeMenuExpanded by remember { mutableStateOf(false) }
    var selectedMember by remember {
        mutableStateOf(assignableMembers.firstOrNull { it.uid == initialTask?.assigneeId })
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf<Long?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                if (initialTask == null) "New task" else "Edit task",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Notes (optional)") },
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            ExposedDropdownMenuBox(
                expanded = assigneeMenuExpanded,
                onExpandedChange = { assigneeMenuExpanded = it },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                OutlinedTextField(
                    value = selectedMember?.displayName ?: "Unassigned",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Assign to") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = assigneeMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = assigneeMenuExpanded,
                    onDismissRequest = { assigneeMenuExpanded = false },
                ) {
                    assignableMembers.forEach { member ->
                        DropdownMenuItem(
                            text = { Text(member.displayName.ifBlank { member.email }) },
                            onClick = {
                                selectedMember = member
                                assigneeMenuExpanded = false
                            },
                        )
                    }
                }
            }

            Text(
                "Priority",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskPriority.entries.forEach { p ->
                    FilterChip(
                        selected = priority == p,
                        onClick = { priority = p },
                        label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                    Text(dueAt?.let { DateTimeUtils.formatDueDate(it) } ?: "Set due date")
                }
                if (dueAt != null) {
                    TextButton(onClick = { dueAt = null; notify = false }) { Text("Clear") }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Remind me", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = notify, onCheckedChange = { notify = it }, enabled = dueAt != null)
            }

            Button(
                onClick = {
                    onSave(
                        initialTask?.id,
                        title.trim(),
                        description.trim(),
                        selectedMember?.uid.orEmpty(),
                        selectedMember?.displayName.orEmpty(),
                        priority,
                        dueAt,
                        notify,
                    )
                },
                enabled = title.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            ) {
                Text("Save task")
            }

            if (initialTask != null && onDelete != null) {
                TextButton(
                    onClick = onDelete,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp),
                ) {
                    Text("Delete task")
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueAt?.time ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pendingDateMillis = datePickerState.selectedDateMillis
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val initialCalendar = Calendar.getInstance().apply { dueAt?.let { time = it } }
        val timePickerState = rememberTimePickerState(
            initialHour = initialCalendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = initialCalendar.get(Calendar.MINUTE),
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val millis = pendingDateMillis ?: dueAt?.time ?: System.currentTimeMillis()
                    dueAt = DateTimeUtils.combine(millis, timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        )
    }
}
