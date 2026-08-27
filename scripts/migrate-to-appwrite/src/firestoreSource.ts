/** Read-only access to the source Firestore data. Never writes anything -
 *  safe to point at real production data, including with --dry-run. */

import { initializeApp, cert, applicationDefault } from "firebase-admin/app";
import { getFirestore, Firestore } from "firebase-admin/firestore";
import type {
  FirestoreUserDoc,
  FirestoreListDoc,
  FirestoreTaskDoc,
} from "./types";

let firestoreSingleton: Firestore | undefined;

/** Initializes firebase-admin against the named "mytasks" Firestore
 *  database (never the default database - see functions/src/firestoreDb.ts,
 *  which this mirrors). Credentials come from GOOGLE_APPLICATION_CREDENTIALS
 *  via Application Default Credentials, same as
 *  .github/workflows/deploy-firebase.yml. */
export function getSourceFirestore(databaseId: string): Firestore {
  if (firestoreSingleton) return firestoreSingleton;

  const keyFilePath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  initializeApp(
    keyFilePath
      ? { credential: cert(keyFilePath) }
      : { credential: applicationDefault() },
  );

  firestoreSingleton = getFirestore(databaseId);
  return firestoreSingleton;
}

export async function fetchAllUsers(db: Firestore): Promise<FirestoreUserDoc[]> {
  const snap = await db.collection("users").get();
  return snap.docs.map((doc) => ({ uid: doc.id, ...(doc.data() as object) }) as FirestoreUserDoc);
}

export async function fetchAllLists(db: Firestore): Promise<FirestoreListDoc[]> {
  const snap = await db.collection("lists").get();
  return snap.docs.map((doc) => ({ id: doc.id, ...(doc.data() as object) }) as FirestoreListDoc);
}

/** Reads the tasks subcollection for a single list. Called per-list (after
 *  the lists pass) rather than via a collectionGroup query, so that each
 *  task can be immediately matched against its already-known parent list
 *  for permission computation without a second lookup. */
export async function fetchTasksForList(
  db: Firestore,
  listId: string,
): Promise<FirestoreTaskDoc[]> {
  const snap = await db.collection("lists").doc(listId).collection("tasks").get();
  return snap.docs.map((doc) => ({ id: doc.id, ...(doc.data() as object) }) as FirestoreTaskDoc);
}
