import { onCall, HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import * as admin from "firebase-admin";
import { createRemoteJWKSet, jwtVerify } from "jose";
import { providerConfigs } from "./providerConfig";

interface ExchangeRequest {
  provider: string;
  idToken: string;
}

const jwksCache = new Map<string, ReturnType<typeof createRemoteJWKSet>>();

function jwksFor(jwksUri: string) {
  let jwks = jwksCache.get(jwksUri);
  if (!jwks) {
    jwks = createRemoteJWKSet(new URL(jwksUri));
    jwksCache.set(jwksUri, jwks);
  }
  return jwks;
}

/**
 * Verifies a LinkedIn/Proton OIDC ID token and mints a Firebase custom
 * token for the equivalent Firebase Auth user, so the Android app can
 * complete sign-in with `FirebaseAuth.signInWithCustomToken`. See
 * AuthRepository.kt / GenericOAuthClient.kt for the client-side half of
 * this flow.
 */
export const exchangeOAuthToken = onCall(
  { region: "us-central1", cors: false },
  async (request) => {
    const data = request.data as ExchangeRequest;
    const provider = data?.provider?.toLowerCase();
    const idToken = data?.idToken;

    if (!provider || !idToken) {
      throw new HttpsError("invalid-argument", "provider and idToken are required");
    }

    const config = providerConfigs[provider];
    if (!config || !config.clientId || !config.jwksUri) {
      throw new HttpsError(
        "failed-precondition",
        `Provider "${provider}" is not configured on the server. See README.`,
      );
    }

    let claims;
    try {
      const { payload } = await jwtVerify(idToken, jwksFor(config.jwksUri), {
        issuer: config.issuer || undefined,
        audience: config.clientId,
      });
      claims = payload;
    } catch (error) {
      logger.warn(`OIDC verification failed for provider=${provider}`, error);
      throw new HttpsError("unauthenticated", "Could not verify identity token");
    }

    const subject = claims.sub;
    if (!subject) {
      throw new HttpsError("unauthenticated", "Identity token had no subject");
    }

    // Namespaced so a LinkedIn and a Proton account can never collide with
    // each other or with Google/Facebook/Microsoft-issued Firebase uids.
    const uid = `${provider}:${subject}`;
    const email = typeof claims.email === "string" ? claims.email : undefined;
    const displayName = typeof claims.name === "string" ? claims.name : undefined;
    const photoURL = typeof claims.picture === "string" ? claims.picture : undefined;

    const auth = admin.auth();
    try {
      await auth.updateUser(uid, { email, displayName, photoURL });
    } catch (error) {
      await auth.createUser({ uid, email, displayName, photoURL });
    }

    const customToken = await auth.createCustomToken(uid, { provider });
    return { customToken };
  },
);
