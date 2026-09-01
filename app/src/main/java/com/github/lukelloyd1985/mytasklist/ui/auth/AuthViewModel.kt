package com.github.lukelloyd1985.mytasklist.ui.auth

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
import com.github.lukelloyd1985.mytasklist.R
import com.github.lukelloyd1985.mytasklist.data.remote.AppUser
import com.github.lukelloyd1985.mytasklist.data.remote.AuthRepository
import com.github.lukelloyd1985.mytasklist.data.remote.UserRepository

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

    fun onGoogleIdToken(idToken: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            try {
                val user = authRepository.signInWithGoogleIdToken(idToken)
                userRepository.upsertProfile(user)
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

    /** Registers this device's current FCM token as an Appwrite Messaging
     *  push Target. Called reactively from MainActivity whenever
     *  [currentUser] becomes non-null - covers both a fresh interactive
     *  sign-in and an app restart into an already-valid session, since
     *  FCM's onNewToken callback (see MyTaskListMessagingService) only fires
     *  on token rotation, not on every app start. Best-effort: a failure
     *  here must never affect sign-in or navigation. */
    suspend fun registerPushTarget() {
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            authRepository.registerPushTarget(token)
        }
    }
}
