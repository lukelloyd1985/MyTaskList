package com.mytasks.app.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mytasks.app.data.model.ListMember
import com.mytasks.app.data.model.ListVisibility
import com.mytasks.app.data.model.TaskList
import com.mytasks.app.data.remote.AuthRepository
import com.mytasks.app.data.remote.ListRepository

data class ListsUiState(val errorMessage: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ListsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val listRepository: ListRepository,
) : ViewModel() {

    val lists: StateFlow<List<TaskList>> = authRepository.authState
        .flatMapLatest { user ->
            if (user == null) kotlinx.coroutines.flow.flowOf(emptyList()) else listRepository.observeMyLists(user.uid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(ListsUiState())
    val uiState: StateFlow<ListsUiState> = _uiState

    // The create dialog dismisses itself as soon as this is called (see
    // ListsScreen) rather than waiting for this write to finish - lists
    // are offline-first (see FirebaseModule), so the new list shows up via
    // `lists` above the moment it's applied locally, with or without a
    // network round trip. Any failure surfaces afterward via uiState
    // instead of leaving the dialog open with no feedback.
    fun createList(name: String, visibility: ListVisibility) {
        val user = authRepository.currentUser ?: return
        val owner = ListMember(
            uid = user.uid,
            displayName = user.displayName ?: user.email.orEmpty(),
            email = user.email.orEmpty(),
            photoUrl = user.photoUrl?.toString().orEmpty(),
        )
        viewModelScope.launch {
            try {
                listRepository.createList(
                    name = name.trim(),
                    icon = "checklist",
                    colorHex = "#1B5E20",
                    visibility = visibility,
                    owner = owner,
                )
            } catch (t: Throwable) {
                _uiState.value = ListsUiState(errorMessage = t.message ?: "Couldn't create list")
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
