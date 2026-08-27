/** Shared shapes for the Firestore -> Appwrite migration.
 *
 * Firestore source shapes mirror app/src/main/java/com/mytasks/app/data/model/
 * {UserProfile,TaskList,TaskItem}.kt exactly. Appwrite target shapes mirror
 * the finalized schema described in scripts/migrate-to-appwrite/README.md.
 */

export type ListVisibility = "PRIVATE" | "SHARED";
export type TaskPriority = "LOW" | "MEDIUM" | "HIGH";

/** users/{uid} */
export interface FirestoreUserDoc {
  uid: string; // doc ID, not a stored field
  displayName?: string;
  email?: string;
  photoUrl?: string;
  fcmTokens?: string[];
  locale?: string;
  // lastSignedInAt intentionally not carried forward - see Step 2 of the brief.
}

/** lists/{listId} */
export interface FirestoreListMember {
  uid?: string;
  displayName?: string;
  email?: string;
  photoUrl?: string;
}

export interface FirestoreListDoc {
  id: string; // doc ID, not a stored field
  name?: string;
  icon?: string;
  colorHex?: string;
  visibility?: ListVisibility;
  ownerId?: string;
  ownerName?: string;
  memberIds?: string[];
  members?: FirestoreListMember[];
  // createdAt intentionally not carried forward - see Step 2 of the brief.
}

/** lists/{listId}/tasks/{taskId} */
export interface FirestoreTaskDoc {
  id: string; // doc ID, not a stored field
  listId?: string;
  title?: string;
  description?: string;
  assigneeId?: string;
  assigneeName?: string;
  priority?: TaskPriority;
  dueAt?: FirebaseFirestoreTimestampLike | null;
  notify?: boolean;
  completed?: boolean;
  order?: number;
  createdBy?: string;
  createdByName?: string;
  reminderSent?: boolean;
  // createdAt/updatedAt intentionally not carried forward - see Step 2.
}

/** Minimal structural type for a Firestore Timestamp, so this file doesn't
 *  need to import firebase-admin's concrete class. */
export interface FirebaseFirestoreTimestampLike {
  toDate(): Date;
}

// --- Appwrite target document shapes (what gets written) ---

export interface AppwriteUserDoc {
  displayName: string;
  email: string;
  photoUrl: string;
  fcmTokens: string[]; // always [] - see Step 2 of the brief.
  locale: string;
}

export interface AppwriteListDoc {
  name: string;
  icon: string;
  colorHex: string;
  visibility: ListVisibility;
  ownerId: string;
  ownerName: string;
  memberIds: string[];
  members: string; // JSON.stringify(FirestoreListMember[])
}

export interface AppwriteTaskDoc {
  listId: string;
  title: string;
  description: string;
  assigneeId: string;
  assigneeName: string;
  priority: TaskPriority;
  dueAt: string | null; // ISO string
  notify: boolean;
  completed: boolean;
  order: number;
  createdBy: string;
  createdByName: string;
  reminderSent: boolean;
}

/** Minimal per-list info carried forward in-memory from the lists pass so
 *  the tasks pass can compute permissions without re-fetching each list. */
export interface ListMembership {
  ownerId: string;
  memberIds: string[];
}

export interface RunStats {
  collection: string;
  total: number;
  migrated: number;
  skippedExisting: number;
  failed: number;
  failedIds: string[];
}

export function newRunStats(collection: string): RunStats {
  return { collection, total: 0, migrated: 0, skippedExisting: 0, failed: 0, failedIds: [] };
}
