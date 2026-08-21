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

    fun createList(name: String, visibility: ListVisibility, onCreated: (String) -> Unit = {}) {
        val user = authRepository.currentUser ?: return
        val owner = ListMember(
            uid = user.uid,
            displayName = user.displayName ?: user.email.orEmpty(),
            email = user.email.orEmpty(),
            photoUrl = user.photoUrl?.toString().orEmpty(),
        )
        viewModelScope.launch {
            val id = listRepository.createList(
                name = name.trim(),
                icon = "checklist",
                colorHex = "#1B5E20",
                visibility = visibility,
                owner = owner,
            )
            onCreated(id)
        }
    }
}
