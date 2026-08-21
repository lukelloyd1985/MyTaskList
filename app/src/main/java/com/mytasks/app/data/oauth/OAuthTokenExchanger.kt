package com.mytasks.app.data.oauth

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls the `exchangeOAuthToken` Cloud Function (see /functions/src/index.ts)
 * which verifies a LinkedIn/Proton ID token against the provider's JWKS and
 * mints a Firebase custom token, letting an otherwise-unsupported OIDC
 * provider sign a user into Firebase Auth.
 */
@Singleton
class OAuthTokenExchanger @Inject constructor(
    private val functions: FirebaseFunctions,
) {
    suspend fun exchangeForFirebaseCustomToken(provider: GenericOAuthProviderId, idToken: String): String {
        val payload = mapOf(
            "provider" to provider.name.lowercase(),
            "idToken" to idToken,
        )
        val result = functions.getHttpsCallable("exchangeOAuthToken").call(payload).await()
        val data = result.data as? Map<*, *> ?: error("Unexpected response from exchangeOAuthToken")
        return data["customToken"] as? String ?: error("exchangeOAuthToken did not return a customToken")
    }
}
