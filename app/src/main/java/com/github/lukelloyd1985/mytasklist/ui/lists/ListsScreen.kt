package com.github.lukelloyd1985.mytasklist.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.lukelloyd1985.mytasklist.R
import com.github.lukelloyd1985.mytasklist.data.model.TaskList
import com.github.lukelloyd1985.mytasklist.ui.components.VisibilityChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    onOpenList: (String) -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Local, optimistic copy of the order for smooth drag feedback - resynced
    // from the ViewModel whenever it changes, except mid-drag.
    var localLists by remember { mutableStateOf(lists) }
    var draggingListId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(lists) {
        if (draggingListId == null) localLists = lists
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lists_title)) },
                actions = {
                    IconButton(onClick = onOpenProfile) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.cd_profile))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.new_list), modifier = Modifier.padding(start = 8.dp))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (lists.isEmpty()) {
            EmptyListsState(padding)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(localLists, key = { it.id }) { list ->
                    val isDragging = list.id == draggingListId
                    ListCard(
                        list = list,
                        onClick = { onOpenList(list.id) },
                        dragHandleModifier = Modifier.pointerInput(list.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingListId = list.id
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    draggingListId = null
                                    dragOffsetY = 0f
                                    if (localLists.map { it.id } != lists.map { it.id }) {
                                        viewModel.reorderLists(localLists.map { it.id })
                                    }
                                },
                                onDragCancel = {
                                    draggingListId = null
                                    dragOffsetY = 0f
                                    localLists = lists
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                                val draggedId = draggingListId ?: return@detectDragGesturesAfterLongPress
                                val currentIndex = localLists.indexOfFirst { it.id == draggedId }
                                if (currentIndex == -1) return@detectDragGesturesAfterLongPress

                                // Card height plus the 12dp gap between cards.
                                val gap = 12.dp.toPx()
                                if (dragOffsetY > 0 && currentIndex < localLists.lastIndex) {
                                    val next = (itemHeights[localLists[currentIndex + 1].id] ?: return@detectDragGesturesAfterLongPress) + gap
                                    if (dragOffsetY > next / 2f) {
                                        localLists = localLists.toMutableList().apply {
                                            add(currentIndex + 1, removeAt(currentIndex))
                                        }
                                        dragOffsetY -= next
                                    }
                                } else if (dragOffsetY < 0 && currentIndex > 0) {
                                    val prev = (itemHeights[localLists[currentIndex - 1].id] ?: return@detectDragGesturesAfterLongPress) + gap
                                    if (-dragOffsetY > prev / 2f) {
                                        localLists = localLists.toMutableList().apply {
                                            add(currentIndex - 1, removeAt(currentIndex))
                                        }
                                        dragOffsetY += prev
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .onGloballyPositioned { itemHeights[list.id] = it.size.height }
                            .zIndex(if (isDragging) 1f else 0f)
                            .offset { IntOffset(0, if (isDragging) dragOffsetY.roundToInt() else 0) }
                            .animateItem(),
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateListDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, visibility ->
                showCreateDialog = false
                viewModel.createList(name, visibility)
            },
        )
    }
}

@Composable
private fun EmptyListsState(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.ChecklistRtl,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            stringResource(R.string.lists_empty_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.lists_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ListCard(
    list: TaskList,
    onClick: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                Text(list.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Box(modifier = Modifier.padding(top = 8.dp)) {
                    Column {
                        VisibilityChip(list.visibility)
                        if (list.visibility.name == "SHARED" && list.members.isNotEmpty()) {
                            val memberCount = list.members.size + 1
                            Text(
                                pluralStringResource(R.plurals.list_members_count, memberCount, memberCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.cd_drag_to_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .then(dragHandleModifier),
            )
        }
    }
}
