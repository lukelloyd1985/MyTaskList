package com.mytasks.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mytasks.app.data.remote.AuthRepository

data class ProfileUiState(
    val isDeleting: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    val currentUser: StateFlow<FirebaseUser?> = authRepository.authState.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        authRepository.currentUser,
    )

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState

    fun signOut() = authRepository.signOut()

    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.value = ProfileUiState(isDeleting = true)
            try {
                authRepository.deleteAccount()
                // authState emits null once signed out, which MyTasksRoot
                // observes to switch back to LoginScreen - no navigation
                // call needed here.
            } catch (t: Throwable) {
                _uiState.value = ProfileUiState(errorMessage = t.message ?: "Couldn't delete your account")
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
