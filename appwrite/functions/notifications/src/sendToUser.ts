import { Databases } from "node-appwrite";
import { GoogleAuth } from "google-auth-library";
import { NOTIFICATION_STRINGS, resolveLocale } from "./notificationStrings";

const DATABASE_ID = process.env.APPWRITE_DATABASE_ID ?? "mytasks";
const USERS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_USERS_ID ?? "users";
const FCM_PROJECT_ID = process.env.FCM_PROJECT_ID;

export type NotificationKind = "assigned" | "dueSoon";

interface UserDoc {
  fcmTokens?: string[];
  locale?: string;
}

let cachedAuth: GoogleAuth | null = null;

/** Lazily builds (and memoizes across warm invocations) a GoogleAuth
 *  instance from the FCM service-account JSON in FCM_SERVICE_ACCOUNT_JSON.
 *  We talk to FCM's HTTP v1 API directly with a minted OAuth2 token rather
 *  than pulling in firebase-admin, which assumes it's running inside a
 *  Firebase project/runtime and isn't meant for use from here. */
function getGoogleAuth(): GoogleAuth {
  if (cachedAuth) return cachedAuth;

  const rawServiceAccount = process.env.FCM_SERVICE_ACCOUNT_JSON;
  if (!rawServiceAccount) {
    throw new Error("FCM_SERVICE_ACCOUNT_JSON environment variable is not set");
  }
  const credentials = JSON.parse(rawServiceAccount);
  cachedAuth = new GoogleAuth({
    credentials,
    scopes: ["https://www.googleapis.com/auth/firebase.messaging"],
  });
  return cachedAuth;
}

async function mintAccessToken(): Promise<string> {
  const client = await getGoogleAuth().getClient();
  const tokenResponse = await client.getAccessToken();
  if (!tokenResponse.token) {
    throw new Error("Failed to mint an FCM access token");
  }
  return tokenResponse.token;
}

/** True for FCM v1 error codes that mean the token is permanently dead
 *  (app uninstalled, token rotated, malformed) - the same class of error
 *  the original firebase-admin sendEachForMulticast() call treated as
 *  "prune this token" via response.success === false. */
function isDeadTokenError(errorBody: unknown): boolean {
  if (!errorBody || typeof errorBody !== "object") return false;
  const details = (errorBody as { error?: { details?: unknown[] } }).error?.details;
  if (!Array.isArray(details)) return false;
  return details.some((d) => {
    if (!d || typeof d !== "object") return false;
    const errorCode = (d as { errorCode?: string }).errorCode;
    return errorCode === "UNREGISTERED" || errorCode === "INVALID_ARGUMENT";
  });
}

/** Sends a push to every one of a user's registered devices, localized to
 *  their `users/{uid}`.locale (see UserRepository.upsertProfile on the
 *  Android side) with a fallback to English for profiles that predate that
 *  field or use an unsupported language. `taskTitle` is user-authored
 *  content and is never translated - only the notification's own title,
 *  and the body's fallback for an untitled task, are.
 *
 *  Ported from functions/src/notifications.ts's sendToUser(), but sends via
 *  FCM's HTTP v1 API directly instead of firebase-admin's Messaging
 *  service, since this now runs outside a Firebase project. */
export async function sendToUser(
  databases: Databases,
  uid: string,
  kind: NotificationKind,
  taskTitle?: string,
): Promise<void> {
  const user = (await databases.getDocument(DATABASE_ID, USERS_COLLECTION_ID, uid)) as UserDoc;
  const tokens = user.fcmTokens ?? [];
  if (tokens.length === 0) return;

  if (!FCM_PROJECT_ID) {
    throw new Error("FCM_PROJECT_ID environment variable is not set");
  }

  const strings = NOTIFICATION_STRINGS[resolveLocale(user.locale)][kind];
  const title = strings.title;
  const body = taskTitle && taskTitle.length > 0 ? taskTitle : strings.untitled;

  const accessToken = await mintAccessToken();
  const endpoint = `https://fcm.googleapis.com/v1/projects/${FCM_PROJECT_ID}/messages:send`;

  const staleTokens: string[] = [];
  await Promise.all(
    tokens.map(async (token) => {
      const response = await fetch(endpoint, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token,
            notification: { title, body },
            android: { priority: "high" },
          },
        }),
      });

      if (!response.ok) {
        const errorBody = await response.json().catch(() => null);
        if (isDeadTokenError(errorBody)) {
          staleTokens.push(token);
        }
      }
    }),
  );

  if (staleTokens.length > 0) {
    const remaining = tokens.filter((t) => !staleTokens.includes(t));
    await databases.updateDocument(DATABASE_ID, USERS_COLLECTION_ID, uid, {
      fcmTokens: remaining,
    });
  }
}
