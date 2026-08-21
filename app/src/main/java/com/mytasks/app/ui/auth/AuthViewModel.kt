package com.mytasks.app.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import com.mytasks.app.data.oauth.GenericOAuthClient
import com.mytasks.app.data.oauth.GenericOAuthProviderId
import com.mytasks.app.data.oauth.OAuthTokenExchanger
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
    private val oauthClient: GenericOAuthClient,
    private val oauthExchanger: OAuthTokenExchanger,
) : ViewModel() {

    val currentUser: StateFlow<FirebaseUser?> = authRepository.authState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = authRepository.currentUser,
    )

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState

    fun onGoogleIdToken(idToken: String) = signInWith { authRepository.signInWithGoogleIdToken(idToken) }

    fun onFacebookAccessToken(accessToken: String) =
        signInWith { authRepository.signInWithFacebookAccessToken(accessToken) }

    fun signInWithMicrosoft(activity: Activity) = signInWith { authRepository.signInWithMicrosoft(activity) }

    fun buildGenericOAuthIntent(context: Context, provider: GenericOAuthProviderId): Intent {
        val config = if (provider == GenericOAuthProviderId.LINKEDIN) {
            com.mytasks.app.data.oauth.OAuthConfig.linkedIn
        } else {
            com.mytasks.app.data.oauth.OAuthConfig.proton
        }
        return oauthClient.buildAuthorizationIntent(context, config)
    }

    fun onGenericOAuthResult(context: Context, provider: GenericOAuthProviderId, resultIntent: Intent?) {
        if (resultIntent == null) return
        signInWith {
            val oauthResult = oauthClient.exchangeToken(context, resultIntent)
            val idToken = oauthResult.idToken ?: error("${provider.name} did not return an ID token")
            val customToken = oauthExchanger.exchangeForFirebaseCustomToken(provider, idToken)
            authRepository.signInWithCustomToken(customToken)
        }
    }

    fun signOut() {
        authRepository.signOut()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun signInWith(block: suspend () -> FirebaseUser) {
        viewModelScope.launch {
            _uiState.value = AuthUiState(isLoading = true)
            try {
                val user = block()
                userRepository.upsertProfile(user)
                registerFcmToken(user.uid)
                _uiState.value = AuthUiState(isLoading = false)
            } catch (t: Throwable) {
                _uiState.value = AuthUiState(isLoading = false, errorMessage = t.message ?: "Sign-in failed")
            }
        }
    }

    private suspend fun registerFcmToken(uid: String) {
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            userRepository.addFcmToken(uid, token)
        }
    }
}
