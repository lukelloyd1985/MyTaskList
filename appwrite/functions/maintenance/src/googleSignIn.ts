import { Client, Users, AppwriteException } from "node-appwrite";
import { OAuth2Client } from "google-auth-library";
import type { FunctionContext } from "./context";

const GOOGLE_WEB_CLIENT_ID = process.env.GOOGLE_WEB_CLIENT_ID;

interface GoogleSignInRequestBody {
  idToken?: string;
}

function isAlreadyExists(err: unknown): boolean {
  return err instanceof AppwriteException && err.code === 409;
}

/**
 * Exchanges a Google ID token (obtained on-device via Credential Manager's
 * Sign in with Google, see LoginScreen.kt) for an Appwrite session, without
 * ever sending the user through Appwrite's own hosted OAuth2 pages - no
 * appwrite.io URL is shown to the user at any point.
 *
 * Verifies the ID token's signature/audience/issuer against Google's own
 * public keys (google-auth-library's OAuth2Client.verifyIdToken - the same
 * client library Google's own server-side ID token verification guide
 * documents), so the caller's identity is established server-side, not
 * trusted from the client. Uses Appwrite's "custom token" login primitive
 * (Users.createToken - see github.com/appwrite/appwrite server docs
 * "Create Token"): creates the Appwrite Auth user if this is their first
 * sign-in (userId = Google's `sub` claim - stable, unique, well within
 * Appwrite's 36-char ID limit), then mints a one-time token secret. The
 * client exchanges {userId, secret} for a real session via
 * account.createSession(userId, secret) - see AuthRepository.kt.
 *
 * GOOGLE_WEB_CLIENT_ID must be the same Google Cloud OAuth 2.0 Web
 * application Client ID the Android app passes to
 * GetGoogleIdOption.setServerClientId()/GetSignInWithGoogleOption - that's
 * what makes the ID token's `aud` claim match what's verified here. See
 * README "Backend setup".
 *
 * HTTP-invoked at path "/google-sign-in" - see main.ts's trigger dispatch.
 */
export async function googleSignIn({ req, res, error }: FunctionContext) {
  if (!GOOGLE_WEB_CLIENT_ID) {
    error("GOOGLE_WEB_CLIENT_ID environment variable is not set");
    return res.json({ success: false, message: "Sign-in is not configured." }, 500);
  }

  const idToken = (req.bodyJson as GoogleSignInRequestBody | undefined)?.idToken;
  if (!idToken) {
    return res.json({ success: false, message: "Missing idToken." }, 400);
  }

  let payload;
  try {
    const oauthClient = new OAuth2Client();
    const ticket = await oauthClient.verifyIdToken({
      idToken,
      audience: GOOGLE_WEB_CLIENT_ID,
    });
    payload = ticket.getPayload();
  } catch (err) {
    error(`Google ID token verification failed: ${err instanceof Error ? err.message : err}`);
    return res.json({ success: false, message: "Invalid Google credential." }, 401);
  }

  if (!payload?.sub || !payload.email) {
    return res.json({ success: false, message: "Invalid Google credential." }, 401);
  }

  const uid = payload.sub;
  const client = new Client()
    .setEndpoint(process.env.APPWRITE_FUNCTION_API_ENDPOINT ?? "")
    .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID ?? "")
    .setKey(req.headers["x-appwrite-key"] ?? "");
  const users = new Users(client);

  try {
    await users.create({
      userId: uid,
      email: payload.email,
      name: payload.name,
    });
  } catch (err) {
    if (!isAlreadyExists(err)) {
      error(`Failed to create Appwrite user for Google sign-in ${uid}: ${err instanceof Error ? err.stack ?? err.message : err}`);
      return res.json({ success: false, message: "Sign-in failed." }, 500);
    }
  }

  const token = await users.createToken({ userId: uid });

  return res.json({
    success: true,
    userId: uid,
    secret: token.secret,
    photoUrl: payload.picture ?? "",
  });
}
