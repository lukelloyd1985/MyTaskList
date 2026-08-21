package com.mytasks.app.ui.listdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytasks.app.data.model.TaskItem
import com.mytasks.app.ui.components.TaskRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(
    onBack: () -> Unit,
    onOpenSettings: (String) -> Unit,
    viewModel: ListDetailViewModel = hiltViewModel(),
) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(list?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenSettings(viewModel.listId) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "List settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingTask = null
                showEditor = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add task")
            }
        },
    ) { padding ->
        val (open, completed) = tasks.partition { !it.completed }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (open.isEmpty() && completed.isEmpty()) {
                item {
                    Text(
                        "No tasks yet - tap + to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }

            items(open, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    onToggleComplete = { viewModel.setCompleted(task.id, it) },
                    onClick = { editingTask = task; showEditor = true },
                )
                HorizontalDivider()
            }

            if (completed.isNotEmpty()) {
                item {
                    Text(
                        "Completed (${completed.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(completed, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onToggleComplete = { viewModel.setCompleted(task.id, it) },
                        onClick = { editingTask = task; showEditor = true },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showEditor) {
        TaskEditorSheet(
            initialTask = editingTask,
            assignableMembers = viewModel.assignableMembers(list),
            onDismiss = { showEditor = false },
            onDelete = editingTask?.let { task ->
                {
                    viewModel.deleteTask(task.id)
                    showEditor = false
                }
            },
            onSave = { taskId, title, description, assigneeId, assigneeName, priority, dueAt, notify ->
                viewModel.saveTask(taskId, title, description, assigneeId, assigneeName, priority, dueAt, notify)
                showEditor = false
            },
        )
    }
}
