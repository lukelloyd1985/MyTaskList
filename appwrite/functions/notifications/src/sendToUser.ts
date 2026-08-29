import { Client, Databases, Messaging, ID } from "node-appwrite";
import { NOTIFICATION_STRINGS, resolveLocale } from "./notificationStrings";

const DATABASE_ID = process.env.APPWRITE_DATABASE_ID ?? "mytasks";
const USERS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_USERS_ID ?? "users";

export type NotificationKind = "assigned" | "dueSoon";

interface UserDoc {
  locale?: string;
}

/** Sends a push to every device the user has registered as an Appwrite
 *  Messaging push Target (see AuthRepository.kt's registerPushTarget on
 *  the Android side), localized to their `users/{uid}`.locale (see
 *  UserRepository.upsertProfile) with a fallback to English for profiles
 *  that predate that field or use an unsupported language. `taskTitle` is
 *  user-authored content and is never translated - only the
 *  notification's own title, and the body's fallback for an untitled
 *  task, are.
 *
 *  Delivery itself - dispatching to FCM, retrying, pruning dead tokens -
 *  is handled entirely by Appwrite's Messaging service and the FCM
 *  Provider configured in Console (see README "Backend setup"); this
 *  function only decides what to send and to whom. This replaced an
 *  earlier version that called FCM's HTTP v1 API directly with a
 *  service-account credential minted via google-auth-library - switched
 *  to Appwrite Messaging to drop that dependency (it was part of what
 *  caused the TS18028 build failures worked through earlier) and the
 *  hand-rolled dead-token-pruning logic in favor of Appwrite's own. */
export async function sendToUser(
  client: Client,
  uid: string,
  kind: NotificationKind,
  taskTitle?: string,
): Promise<void> {
  const databases = new Databases(client);
  const messaging = new Messaging(client);

  const user = (await databases.getDocument(DATABASE_ID, USERS_COLLECTION_ID, uid)) as UserDoc;
  const strings = NOTIFICATION_STRINGS[resolveLocale(user.locale)][kind];
  const title = strings.title;
  const body = taskTitle && taskTitle.length > 0 ? taskTitle : strings.untitled;

  await messaging.createPush({
    messageId: ID.unique(),
    users: [uid],
    title,
    body,
  });
}
