package com.mytasks.app.ui.auth

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import com.mytasks.app.R
import com.mytasks.app.data.oauth.GenericOAuthProviderId
import com.mytasks.app.data.oauth.OAuthConfig
import com.mytasks.app.ui.components.SocialLoginButton

@Composable
fun LoginScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val callbackManager = remember { CallbackManager.Factory.create() }
    val linkedInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onGenericOAuthResult(context, GenericOAuthProviderId.LINKEDIN, result.data)
    }
    val protonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onGenericOAuthResult(context, GenericOAuthProviderId.PROTON, result.data)
    }

    LaunchedEffect(Unit) {
        LoginManager.getInstance().registerCallback(
            callbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    viewModel.onFacebookAccessToken(result.accessToken.token)
                }
                override fun onCancel() = Unit
                override fun onError(error: FacebookException) {
                    scope.launch { snackbarHostState.showSnackbar(error.message ?: "Facebook sign-in failed") }
                }
            },
        )
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Checklist,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Shared task lists for your home, garden, and everything in between",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
            )

            SocialLoginButton(
                text = "Continue with Google",
                iconRes = R.drawable.ic_provider_google,
                modifier = Modifier.padding(bottom = 12.dp),
                enabled = !uiState.isLoading,
            ) {
                scope.launch {
                    signInWithGoogle(context, viewModel, snackbarHostState)
                }
            }

            SocialLoginButton(
                text = "Continue with Microsoft",
                iconRes = R.drawable.ic_provider_microsoft,
                modifier = Modifier.padding(bottom = 12.dp),
                enabled = !uiState.isLoading,
            ) {
                viewModel.signInWithMicrosoft(activity)
            }

            SocialLoginButton(
                text = "Continue with Facebook",
                iconRes = R.drawable.ic_provider_facebook,
                modifier = Modifier.padding(bottom = 12.dp),
                enabled = !uiState.isLoading,
            ) {
                LoginManager.getInstance().logIn(
                    activity as ComponentActivity,
                    callbackManager,
                    listOf("public_profile", "email"),
                )
            }

            SocialLoginButton(
                text = "Continue with LinkedIn",
                iconRes = R.drawable.ic_provider_linkedin,
                modifier = Modifier.padding(bottom = 12.dp),
                enabled = !uiState.isLoading,
            ) {
                linkedInLauncher.launch(viewModel.buildGenericOAuthIntent(context, GenericOAuthProviderId.LINKEDIN))
            }

            SocialLoginButton(
                text = "Continue with Proton",
                iconRes = R.drawable.ic_provider_proton,
                modifier = Modifier.padding(bottom = 12.dp),
                enabled = !uiState.isLoading && OAuthConfig.proton.isConfigured,
            ) {
                protonLauncher.launch(viewModel.buildGenericOAuthIntent(context, GenericOAuthProviderId.PROTON))
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

private suspend fun signInWithGoogle(
    context: android.content.Context,
    viewModel: AuthViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val credentialManager = CredentialManager.create(context)
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(context.getString(R.string.default_web_client_id))
        .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    try {
        val response = credentialManager.getCredential(context, request)
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            viewModel.onGoogleIdToken(googleIdTokenCredential.idToken)
        } else {
            snackbarHostState.showSnackbar("Unexpected credential type from Google")
        }
    } catch (e: GetCredentialException) {
        snackbarHostState.showSnackbar(e.message ?: "Google sign-in was cancelled")
    }
}
