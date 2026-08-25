package com.mytasks.app.ui.listdetail

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytasks.app.R
import com.mytasks.app.data.model.TaskItem
import com.mytasks.app.ui.components.TaskRow
import kotlin.math.roundToInt

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
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenSettings(viewModel.listId) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.list_settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingTask = null
                showEditor = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_task))
            }
        },
    ) { padding ->
        val (open, completed) = tasks.partition { !it.completed }

        // Local, optimistic copy of the open tasks' order for smooth
        // drag feedback - resynced from the ViewModel's authoritative
        // `open` list whenever it changes, except mid-drag (so a
        // Firestore snapshot landing while dragging doesn't yank the
        // list out from under the user's finger).
        var localOpen by remember { mutableStateOf(open) }
        var draggingTaskId by remember { mutableStateOf<String?>(null) }
        var dragOffsetY by remember { mutableStateOf(0f) }
        val itemHeights = remember { mutableStateMapOf<String, Int>() }

        LaunchedEffect(open) {
            if (draggingTaskId == null) localOpen = open
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (open.isEmpty() && completed.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.tasks_empty_state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }

            items(localOpen, key = { it.id }) { task ->
                val isDragging = task.id == draggingTaskId
                TaskRow(
                    task = task,
                    onToggleComplete = { viewModel.setCompleted(task.id, it) },
                    onClick = { editingTask = task; showEditor = true },
                    dragHandleModifier = Modifier.pointerInput(task.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingTaskId = task.id
                                dragOffsetY = 0f
                            },
                            onDragEnd = {
                                draggingTaskId = null
                                dragOffsetY = 0f
                                if (localOpen.map { it.id } != open.map { it.id }) {
                                    viewModel.reorderTasks(localOpen.map { it.id })
                                }
                            },
                            onDragCancel = {
                                draggingTaskId = null
                                dragOffsetY = 0f
                                localOpen = open
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragOffsetY += dragAmount.y
                            val draggedId = draggingTaskId ?: return@detectDragGesturesAfterLongPress
                            val currentIndex = localOpen.indexOfFirst { it.id == draggedId }
                            if (currentIndex == -1) return@detectDragGesturesAfterLongPress

                            if (dragOffsetY > 0 && currentIndex < localOpen.lastIndex) {
                                val nextHeight = itemHeights[localOpen[currentIndex + 1].id] ?: return@detectDragGesturesAfterLongPress
                                if (dragOffsetY > nextHeight / 2f) {
                                    localOpen = localOpen.toMutableList().apply {
                                        add(currentIndex + 1, removeAt(currentIndex))
                                    }
                                    dragOffsetY -= nextHeight
                                }
                            } else if (dragOffsetY < 0 && currentIndex > 0) {
                                val prevHeight = itemHeights[localOpen[currentIndex - 1].id] ?: return@detectDragGesturesAfterLongPress
                                if (-dragOffsetY > prevHeight / 2f) {
                                    localOpen = localOpen.toMutableList().apply {
                                        add(currentIndex - 1, removeAt(currentIndex))
                                    }
                                    dragOffsetY += prevHeight
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .onGloballyPositioned { itemHeights[task.id] = it.size.height }
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset { IntOffset(0, if (isDragging) dragOffsetY.roundToInt() else 0) }
                        .animateItem(),
                )
                HorizontalDivider()
            }

            if (completed.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.completed_count, completed.size),
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
