package com.github.lukelloyd1985.mytasklist.ui.listdetail

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import com.github.lukelloyd1985.mytasklist.data.model.ListMember
import com.github.lukelloyd1985.mytasklist.data.model.ListVisibility
import com.github.lukelloyd1985.mytasklist.data.model.TaskItem
import com.github.lukelloyd1985.mytasklist.data.model.TaskList
import com.github.lukelloyd1985.mytasklist.data.model.TaskPriority
import com.github.lukelloyd1985.mytasklist.data.remote.AuthRepository
import com.github.lukelloyd1985.mytasklist.data.remote.ListRepository
import com.github.lukelloyd1985.mytasklist.data.remote.TaskRepository
import com.github.lukelloyd1985.mytasklist.data.remote.UserRepository
import com.github.lukelloyd1985.mytasklist.R
import com.github.lukelloyd1985.mytasklist.di.ApplicationScope
import com.github.lukelloyd1985.mytasklist.notifications.ReminderScheduler

data class InviteUiState(val isLoading: Boolean = false, val errorMessage: String? = null)

@HiltViewModel
class ListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val listRepository: ListRepository,
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository,
    private val reminderScheduler: ReminderScheduler,
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    val listId: String = checkNotNull(savedStateHandle["listId"])
    val currentUid: String? get() = authRepository.currentUser?.uid

    val list: StateFlow<TaskList?> = listRepository.observeList(listId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tasks: StateFlow<List<TaskItem>> = taskRepository.observeTasks(listId)
        // Stable sort: ties (including every task's default order=0
        // before it's ever been manually reordered) keep the underlying
        // query's own order (completed, then dueAt) instead of jumping
        // around, so this only changes anything once someone drags.
        .map { taskItems -> taskItems.sortedBy { it.order } }
        .onEach { taskItems ->
            val uid = currentUid ?: return@onEach
            taskItems.forEach { task ->
                if (task.assigneeId == uid) reminderScheduler.schedule(task) else reminderScheduler.cancel(task.id)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _inviteState = MutableStateFlow(InviteUiState())
    val inviteState: StateFlow<InviteUiState> = _inviteState

    fun assignableMembers(currentList: TaskList?): List<ListMember> {
        val list = currentList ?: return emptyList()
        val ownerMember = ListMember(uid = list.ownerId, displayName = list.ownerName, email = "", photoUrl = "")
        return listOf(ownerMember) + list.members
    }

    fun saveTask(
        taskId: String?,
        title: String,
        description: String,
        assigneeId: String,
        assigneeName: String,
        priority: TaskPriority,
        dueAt: Date?,
        notify: Boolean,
    ) {
        val user = authRepository.currentUser ?: return
        viewModelScope.launch {
            if (taskId == null) {
                val currentList = list.value
                taskRepository.createTask(
                    listId = listId,
                    title = title,
                    description = description,
                    assigneeId = assigneeId,
                    assigneeName = assigneeName,
                    priority = priority,
                    dueAt = dueAt,
                    notify = notify,
                    createdBy = user.uid,
                    createdByName = user.displayName.ifBlank { user.email },
                    listOwnerId = currentList?.ownerId ?: user.uid,
                    listMemberIds = currentList?.memberIds ?: emptyList(),
                )
            } else {
                taskRepository.updateTask(
                    listId = listId,
                    taskId = taskId,
                    title = title,
                    description = description,
                    assigneeId = assigneeId,
                    assigneeName = assigneeName,
                    priority = priority,
                    dueAt = dueAt,
                    notify = notify,
                )
            }
        }
    }

    fun setCompleted(taskId: String, completed: Boolean) {
        viewModelScope.launch { taskRepository.setCompleted(listId, taskId, completed) }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            reminderScheduler.cancel(taskId)
            taskRepository.deleteTask(listId, taskId)
        }
    }

    fun reorderTasks(orderedTaskIds: List<String>) {
        viewModelScope.launch { taskRepository.reorderTasks(listId, orderedTaskIds) }
    }

    fun setVisibility(visibility: ListVisibility) {
        viewModelScope.launch { listRepository.setVisibility(listId, visibility) }
    }

    fun renameList(name: String) {
        viewModelScope.launch { listRepository.renameList(listId, name) }
    }

    // Navigates back immediately rather than waiting for the delete to
    // finish, same as ListsViewModel.createList not waiting on its write.
    // The delete itself runs on applicationScope, not viewModelScope:
    // navigating away clears this screen's ViewModel, which would
    // otherwise cancel the in-flight delete before it ever reaches
    // Appwrite. A failure can't be shown on this screen anymore by the
    // time it's known, so it's reported via a Toast instead.
    fun deleteList(onDeleted: () -> Unit) {
        onDeleted()
        applicationScope.launch {
            try {
                listRepository.deleteList(listId)
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    val message = t.message ?: appContext.getString(R.string.error_delete_list_failed)
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun removeMember(uid: String) {
        viewModelScope.launch { listRepository.removeMember(listId, uid) }
    }

    fun inviteMember(email: String) {
        val normalized = email.trim().lowercase()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            _inviteState.value = InviteUiState(isLoading = true)
            val profile = userRepository.findByEmail(normalized)
            if (profile == null) {
                _inviteState.value = InviteUiState(
                    errorMessage = appContext.getString(R.string.error_invite_no_user_found, normalized),
                )
                return@launch
            }
            listRepository.addMember(
                listId,
                ListMember(
                    uid = profile.uid,
                    displayName = profile.displayName,
                    email = profile.email,
                    photoUrl = profile.photoUrl,
                ),
            )
            _inviteState.value = InviteUiState()
        }
    }

    fun clearInviteError() {
        _inviteState.value = _inviteState.value.copy(errorMessage = null)
    }
}
