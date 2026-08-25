import { initializeApp } from "firebase-admin/app";
import { setGlobalOptions } from "firebase-functions/v2";

initializeApp();

// UK data residency: keeps compute co-located with the Firestore database,
// which must be created in europe-west2 (or deleted and recreated there,
// for a project that already exists in another location) separately in
// Firebase/Cloud Console - see README "Backend setup" step 2. Applies to
// every function in this codebase unless a specific one overrides it.
setGlobalOptions({ region: "europe-west2" });

export { onTaskWrite, dueDateReminders } from "./notifications";
export { deleteAccount } from "./accountDeletion";
