package com.mytasks.app.data.oauth

import com.mytasks.app.BuildConfig

enum class GenericOAuthProviderId { LINKEDIN, PROTON }

data class GenericOAuthProviderConfig(
    val id: GenericOAuthProviderId,
    val displayName: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val scopes: List<String>,
) {
    val isConfigured: Boolean
        get() = clientId.isNotBlank() && authorizationEndpoint.isNotBlank() && tokenEndpoint.isNotBlank()
}

/**
 * Firebase Authentication has no built-in provider for LinkedIn or Proton,
 * unlike Google/Facebook/Microsoft. These two are federated via a generic
 * OAuth 2.0 / OIDC "Authorization Code" flow (see [GenericOAuthClient]),
 * whose resulting ID token is exchanged for a Firebase custom token by the
 * `exchangeOAuthToken` Cloud Function (see /functions).
 *
 * LinkedIn's "Sign In with LinkedIn using OpenID Connect" endpoints are
 * public and stable. Proton does not (yet) offer a fully self-serve
 * consumer OAuth program the way Google/Microsoft do, so its endpoint/
 * client ID must be filled in once you have been onboarded - see README.
 */
object OAuthConfig {
    const val REDIRECT_SCHEME = "com.mytasks.app.oauth"
    const val REDIRECT_URI = "$REDIRECT_SCHEME://oauth2redirect"

    val linkedIn = GenericOAuthProviderConfig(
        id = GenericOAuthProviderId.LINKEDIN,
        displayName = "LinkedIn",
        authorizationEndpoint = "https://www.linkedin.com/oauth/v2/authorization",
        tokenEndpoint = "https://www.linkedin.com/oauth/v2/accessToken",
        clientId = BuildConfig.LINKEDIN_CLIENT_ID,
        scopes = listOf("openid", "profile", "email"),
    )

    // Fill in once your app is approved for Proton's OAuth/OIDC program.
    val proton = GenericOAuthProviderConfig(
        id = GenericOAuthProviderId.PROTON,
        displayName = "Proton",
        authorizationEndpoint = "",
        tokenEndpoint = "",
        clientId = BuildConfig.PROTON_CLIENT_ID,
        scopes = listOf("openid", "email"),
    )
}
