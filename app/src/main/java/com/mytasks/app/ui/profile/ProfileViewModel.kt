package com.mytasks.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import com.mytasks.app.data.remote.AuthRepository

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    val currentUser: StateFlow<FirebaseUser?> = authRepository.authState.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        authRepository.currentUser,
    )

    fun signOut() = authRepository.signOut()
}
