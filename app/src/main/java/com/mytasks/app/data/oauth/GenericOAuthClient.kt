package com.mytasks.app.data.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OAuthResult(val idToken: String?, val accessToken: String?)

/**
 * Thin wrapper around AppAuth's authorization-code flow for OAuth/OIDC
 * providers Firebase doesn't support natively (LinkedIn, Proton). The
 * caller is responsible for launching [buildAuthorizationIntent] via
 * `ActivityResultContracts.StartActivityForResult` and feeding the result
 * `Intent` back into [exchangeToken].
 */
@Singleton
class GenericOAuthClient @Inject constructor() {

    fun buildAuthorizationIntent(context: Context, config: GenericOAuthProviderConfig): Intent {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(config.authorizationEndpoint),
            Uri.parse(config.tokenEndpoint),
        )
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            config.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(OAuthConfig.REDIRECT_URI),
        ).setScopes(config.scopes).build()

        val service = net.openid.appauth.AuthorizationService(context)
        return service.getAuthorizationRequestIntent(request)
    }

    suspend fun exchangeToken(context: Context, resultIntent: Intent): OAuthResult {
        val response = AuthorizationResponse.fromIntent(resultIntent)
        val exception = AuthorizationException.fromIntent(resultIntent)
        if (response == null) {
            throw exception ?: IllegalStateException("OAuth authorization returned no response")
        }

        val service = net.openid.appauth.AuthorizationService(context)
        return suspendCancellableCoroutine { continuation ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, ex ->
                service.dispose()
                if (tokenResponse != null) {
                    continuation.resume(OAuthResult(tokenResponse.idToken, tokenResponse.accessToken))
                } else {
                    continuation.resumeWithException(ex ?: IllegalStateException("Token exchange failed"))
                }
            }
        }
    }
}
