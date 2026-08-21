/**
 * Configuration for the OAuth/OIDC providers Firebase Auth doesn't support
 * natively. Google, Facebook, and Microsoft are handled entirely on-device
 * (see AuthRepository.kt) and never touch this file.
 *
 * Values are read from environment variables so real client IDs never need
 * to be committed. Set them with, e.g.:
 *   firebase functions:secrets:set LINKEDIN_CLIENT_ID
 * or via `firebase functions:config:set` / a `.env` file for local testing.
 * See the README for the full setup walkthrough.
 */
export interface ProviderConfig {
  issuer: string;
  jwksUri: string;
  clientId: string;
}

function env(name: string): string {
  return process.env[name] ?? "";
}

export const providerConfigs: Record<string, ProviderConfig> = {
  linkedin: {
    issuer: "https://www.linkedin.com/oauth",
    jwksUri: "https://www.linkedin.com/oauth/openid/jwks",
    clientId: env("LINKEDIN_CLIENT_ID"),
  },
  // Proton does not (yet) offer a fully self-serve OAuth/OIDC program for
  // third-party consumer apps the way Google/Microsoft/LinkedIn do. Fill
  // these in once you've been onboarded - see README "Proton sign-in".
  proton: {
    issuer: env("PROTON_ISSUER"),
    jwksUri: env("PROTON_JWKS_URI"),
    clientId: env("PROTON_CLIENT_ID"),
  },
};
