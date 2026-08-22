package com.mytasks.app.ui.listdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytasks.app.data.model.ListVisibility
import com.mytasks.app.ui.components.VisibilityChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListSettingsScreen(
    onBack: () -> Unit,
    onListDeleted: () -> Unit,
    viewModel: ListDetailViewModel = hiltViewModel(),
) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val inviteState by viewModel.inviteState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var name by remember(list?.id) { mutableStateOf(list?.name.orEmpty()) }
    var inviteEmail by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val deleteError by viewModel.deleteError.collectAsStateWithLifecycle()

    LaunchedEffect(inviteState.errorMessage) {
        inviteState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInviteError()
        }
    }

    LaunchedEffect(deleteError) {
        deleteError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearDeleteError()
        }
    }

    val isOwner = list != null && list?.ownerId == viewModel.currentUid

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("List settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("List name") },
                singleLine = true,
                enabled = isOwner,
                trailingIcon = {
                    if (isOwner && name.isNotBlank() && name != list?.name) {
                        TextButton(onClick = { viewModel.renameList(name.trim()) }) { Text("Save") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Shared list", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Anyone you invite can view and edit this list",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = list?.visibility == ListVisibility.SHARED,
                    enabled = isOwner,
                    onCheckedChange = {
                        viewModel.setVisibility(if (it) ListVisibility.SHARED else ListVisibility.PRIVATE)
                    },
                )
            }

            list?.let { VisibilityChip(it.visibility, modifier = Modifier.padding(top = 8.dp)) }

            if (list?.visibility == ListVisibility.SHARED) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
                Text("Members", style = MaterialTheme.typography.titleMedium)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = inviteEmail,
                        onValueChange = { inviteEmail = it },
                        label = { Text("Invite by email") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (inviteState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp).size(24.dp))
                    } else {
                        Button(
                            onClick = { viewModel.inviteMember(inviteEmail); inviteEmail = "" },
                            enabled = inviteEmail.isNotBlank(),
                            modifier = Modifier.padding(start = 12.dp),
                        ) { Text("Invite") }
                    }
                }

                LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                    list?.let { current ->
                        item {
                            MemberRow(name = "${current.ownerName} (owner)", email = "", onRemove = null)
                        }
                        items(current.members, key = { it.uid }) { member ->
                            MemberRow(
                                name = member.displayName,
                                email = member.email,
                                onRemove = if (isOwner) {
                                    { viewModel.removeMember(member.uid) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            if (isOwner) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Delete list")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this list?") },
            text = { Text("All tasks in \"${list?.name}\" will be permanently deleted for everyone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteList(onListDeleted)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MemberRow(name: String, email: String, onRemove: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(name.ifBlank { email }, style = MaterialTheme.typography.bodyLarge)
            if (email.isNotBlank()) {
                Text(email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove member")
            }
        }
    }
}
