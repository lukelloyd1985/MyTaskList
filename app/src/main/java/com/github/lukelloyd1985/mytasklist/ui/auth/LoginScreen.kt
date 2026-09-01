package com.github.lukelloyd1985.mytasklist.ui.auth

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.github.lukelloyd1985.mytasklist.BuildConfig
import com.github.lukelloyd1985.mytasklist.R
import com.github.lukelloyd1985.mytasklist.ui.components.SocialLoginButton
import kotlinx.coroutines.launch

private const val TAG = "GoogleSignIn"

@Composable
fun LoginScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                text = stringResource(R.string.login_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
            )

            SocialLoginButton(
                text = stringResource(R.string.continue_with_google),
                iconRes = R.drawable.ic_provider_google,
                modifier = Modifier.padding(bottom = 12.dp),
                enabled = !uiState.isLoading,
            ) {
                scope.launch {
                    signInWithGoogle(context, viewModel, snackbarHostState)
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

// GetCredentialException's own .message is often just a generic summary
// ("[16] Account reauth failed") - the actually-useful detail (e.g. a
// wrapped ApiException's status code/message from Play Services) lives
// in .cause, which the error snackbar previously discarded entirely. On
// a device with no adb access, this is the only way to see it without a
// logcat capture.
private fun detailMessage(e: GetCredentialException): String {
    val cause = e.cause
    return when {
        cause != null -> "${e.message ?: e.type} (cause: $cause)"
        else -> e.message ?: "cancelled"
    }
}

private suspend fun signInWithGoogle(
    context: Context,
    viewModel: AuthViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
    if (webClientId.isBlank()) {
        // MYTASKLIST_GOOGLE_WEB_CLIENT_ID wasn't set at build time (see README
        // "Backend setup" step 6/11) - GetGoogleIdOption.Builder().build()
        // throws IllegalArgumentException on a blank server client ID,
        // uncaught by the GetCredentialException catch below, so this must
        // be checked before constructing it rather than relying on that
        // catch.
        Log.e(TAG, "GOOGLE_WEB_CLIENT_ID is blank - Google sign-in is not configured for this build")
        snackbarHostState.showSnackbar(context.getString(R.string.error_sign_in_failed))
        return
    }
    val credentialManager = CredentialManager.create(context)

    // GetGoogleIdOption's bottom-sheet flow only offers accounts Android
    // already has some signal for. On a real device with a Google account
    // that hasn't used this exact flow before - or isn't surfaced for
    // other reasons - it throws NoCredentialException ("No credentials
    // available") rather than falling back on its own. Per Google's docs,
    // the fix is to retry with GetSignInWithGoogleOption, which shows the
    // full account picker instead: https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(webClientId)
        .build()
    val primaryRequest = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    try {
        val response = credentialManager.getCredential(context, primaryRequest)
        Log.i(TAG, "Primary GetGoogleIdOption flow returned a credential")
        handleGoogleCredential(context, response.credential, viewModel, snackbarHostState)
    } catch (e: NoCredentialException) {
        Log.w(TAG, "Primary flow found no credential (type=${e.type}), falling back to GetSignInWithGoogleOption", e)
        val fallbackOption = GetSignInWithGoogleOption.Builder(serverClientId = webClientId).build()
        val fallbackRequest = GetCredentialRequest.Builder().addCredentialOption(fallbackOption).build()
        try {
            val response = credentialManager.getCredential(context, fallbackRequest)
            Log.i(TAG, "Fallback GetSignInWithGoogleOption flow returned a credential")
            handleGoogleCredential(context, response.credential, viewModel, snackbarHostState)
        } catch (e2: GetCredentialException) {
            Log.e(TAG, "Fallback flow failed: type=${e2.type} message=${e2.message} cause=${e2.cause}", e2)
            snackbarHostState.showSnackbar(
                context.getString(R.string.error_google_signin_failed, e2.type, detailMessage(e2)),
            )
        }
    } catch (e: GetCredentialException) {
        Log.e(TAG, "Primary flow failed: type=${e.type} message=${e.message} cause=${e.cause}", e)
        snackbarHostState.showSnackbar(
            context.getString(R.string.error_google_signin_failed, e.type, detailMessage(e)),
        )
    }
}

private suspend fun handleGoogleCredential(
    context: Context,
    credential: Credential,
    viewModel: AuthViewModel,
    snackbarHostState: SnackbarHostState,
) {
    if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
        viewModel.onGoogleIdToken(googleIdTokenCredential.idToken)
    } else {
        Log.e(TAG, "Unexpected credential type from Google: ${credential.type}")
        snackbarHostState.showSnackbar(context.getString(R.string.error_unexpected_credential_type, credential.type))
    }
}
