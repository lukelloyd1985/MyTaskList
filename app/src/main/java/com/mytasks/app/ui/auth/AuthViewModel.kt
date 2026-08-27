package com.mytasks.app.ui.auth

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import com.mytasks.app.R
import com.mytasks.app.data.remote.AppUser
import com.mytasks.app.data.remote.AuthRepository
import com.mytasks.app.data.remote.UserRepository

data class AuthUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val currentUser: StateFlow<AppUser?> = authRepository.authState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = authRepository.currentUser,
    )

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState

    fun signInWithGoogle(activity: Activity) {
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            try {
                val user = authRepository.signInWithGoogle(activity)
                userRepository.upsertProfile(user)
                registerFcmToken(user.uid)
                _uiState.value = AuthUiState(isLoading = false)
            } catch (t: Throwable) {
                _uiState.value = AuthUiState(
                    isLoading = false,
                    errorMessage = t.message ?: appContext.getString(R.string.error_sign_in_failed),
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private suspend fun registerFcmToken(uid: String) {
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            userRepository.addFcmToken(uid, token)
        }
    }
}
