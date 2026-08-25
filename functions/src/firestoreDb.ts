import { getFirestore } from "firebase-admin/firestore";

/** Must match firebase.json's `firestore.database` and the
 *  FIRESTORE_DATABASE_ID constant in the Android app's FirebaseModule.kt -
 *  Firestore requires every client to explicitly name a non-default
 *  database, there's no per-project "current" database it falls back to. */
export const FIRESTORE_DATABASE_ID = "mytasks";

export function db() {
  return getFirestore(FIRESTORE_DATABASE_ID);
}
