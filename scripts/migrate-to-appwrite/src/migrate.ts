#!/usr/bin/env node
/** One-time migration of MyTasks production data from Firestore (named
 *  database "mytasks") to Appwrite Cloud. See scripts/migrate-to-appwrite/README.md
 *  for the full write-up; this file is the orchestration entry point.
 *
 * Run with `--dry-run` to read and validate everything without writing
 * anything to Appwrite - safe against real production Firestore data.
 */

import { loadConfig } from "./env";
import { getSourceFirestore, fetchAllUsers, fetchAllLists, fetchTasksForList } from "./firestoreSource";
import {
  buildClient,
  ID,
  Databases,
  Users,
  userDocPermissions,
  listDocPermissions,
  taskDocPermissions,
  isConflictError,
  errorMessage,
} from "./appwriteTarget";
import type {
  FirestoreUserDoc,
  FirestoreListDoc,
  FirestoreTaskDoc,
  AppwriteUserDoc,
  AppwriteListDoc,
  AppwriteTaskDoc,
  ListMembership,
  RunStats,
} from "./types";
import { newRunStats } from "./types";

// ---------------------------------------------------------------------
// Validation / shaping helpers
// ---------------------------------------------------------------------

interface Anomaly {
  collection: "users" | "lists" | "tasks";
  id: string;
  issue: string;
}

function checkUser(u: FirestoreUserDoc): Anomaly[] {
  const anomalies: Anomaly[] = [];
  if (!u.email) anomalies.push({ collection: "users", id: u.uid, issue: "missing required field: email" });
  return anomalies;
}

function checkList(l: FirestoreListDoc): Anomaly[] {
  const anomalies: Anomaly[] = [];
  if (!l.ownerId) anomalies.push({ collection: "lists", id: l.id, issue: "missing required field: ownerId" });
  return anomalies;
}

function checkTask(t: FirestoreTaskDoc, knownListIds: Set<string>): Anomaly[] {
  const anomalies: Anomaly[] = [];
  if (!t.listId) {
    anomalies.push({ collection: "tasks", id: t.id, issue: "missing required field: listId" });
  } else if (!knownListIds.has(t.listId)) {
    anomalies.push({
      collection: "tasks",
      id: t.id,
      issue: `listId "${t.listId}" does not correspond to any list that would be migrated`,
    });
  }
  return anomalies;
}

function toAppwriteUser(u: FirestoreUserDoc): AppwriteUserDoc {
  return {
    displayName: u.displayName ?? "",
    email: u.email ?? "",
    photoUrl: u.photoUrl ?? "",
    fcmTokens: [], // always empty - old tokens are invalid post-migration regardless
    locale: u.locale ?? "",
  };
}

function toAppwriteList(l: FirestoreListDoc): AppwriteListDoc {
  return {
    name: l.name ?? "",
    icon: l.icon ?? "checklist",
    colorHex: l.colorHex ?? "#1B5E20",
    visibility: l.visibility ?? "PRIVATE",
    ownerId: l.ownerId ?? "",
    ownerName: l.ownerName ?? "",
    memberIds: l.memberIds ?? [],
    members: JSON.stringify(l.members ?? []),
  };
}

function toIsoOrNull(value: FirestoreTaskDoc["dueAt"]): string | null {
  if (!value) return null;
  // firebase-admin Firestore Timestamp exposes toDate(); guard defensively
  // in case a doc somehow stored something else.
  if (typeof (value as { toDate?: unknown }).toDate === "function") {
    return (value as { toDate(): Date }).toDate().toISOString();
  }
  return null;
}

function toAppwriteTask(t: FirestoreTaskDoc): AppwriteTaskDoc {
  return {
    listId: t.listId ?? "",
    title: t.title ?? "",
    description: t.description ?? "",
    assigneeId: t.assigneeId ?? "",
    assigneeName: t.assigneeName ?? "",
    priority: t.priority ?? "MEDIUM",
    dueAt: toIsoOrNull(t.dueAt),
    notify: t.notify ?? false,
    completed: t.completed ?? false,
    order: t.order ?? 0,
    createdBy: t.createdBy ?? "",
    createdByName: t.createdByName ?? "",
    reminderSent: t.reminderSent ?? false,
  };
}

// ---------------------------------------------------------------------
// Write helpers - each wraps a single create call so a 409 (document or
// user already exists) is treated as "already migrated, skip" rather
// than a fatal error. This is what makes the whole script safely
// re-runnable after a partial failure, without a separate checkpoint
// file: every ID is deterministic (the original Firebase UID / Firestore
// doc ID), so a second run naturally lands on the same conflicts for
// whatever already made it across and only makes progress on the rest.
// ---------------------------------------------------------------------

type WriteOutcome = "created" | "skipped-existing" | "failed";

async function createAuthUser(
  users: Users,
  uid: string,
  email: string,
  displayName: string,
): Promise<WriteOutcome> {
  try {
    await users.create(ID.custom(uid), email || undefined, undefined, undefined, displayName || undefined);
    // These users' emails were already verified via Google Sign-In in
    // Firebase. Marking them verified here is what should let Appwrite's
    // own Google OAuth2 flow link to this same account (by matching
    // email) the next time the real user signs in through the app's new
    // Appwrite OAuth flow, instead of creating a duplicate account.
    //
    // *** UNVERIFIED ASSUMPTION - CONFIRM BEFORE PRODUCTION CUTOVER ***
    // This email-matching account-linking behavior is standard documented
    // Appwrite OAuth2 behavior, but it has NOT been exercised against a
    // live Appwrite test project as part of writing this script (no
    // Appwrite project was available in this environment). Before relying
    // on it for the real cutover, do a real test sign-in with a Google
    // account that has a pre-created, email-verified Appwrite user (as
    // this script creates) and confirm the OAuth sign-in lands on that
    // SAME $id rather than creating a second, separate user. If it turns
    // out Appwrite creates a fresh account instead of linking, this
    // script's "$id preserves the Firebase uid" strategy breaks for
    // real users and would need a follow-up reconciliation pass
    // (out of scope for this script - flagging only).
    await users.updateEmailVerification(uid, true);
    return "created";
  } catch (err) {
    if (isConflictError(err)) return "skipped-existing";
    throw err;
  }
}

async function createDocument(
  databases: Databases,
  databaseId: string,
  collectionId: string,
  docId: string,
  data: object,
  permissions: string[],
): Promise<WriteOutcome> {
  try {
    await databases.createDocument(databaseId, collectionId, ID.custom(docId), data, permissions);
    return "created";
  } catch (err) {
    if (isConflictError(err)) return "skipped-existing";
    throw err;
  }
}

function record(stats: RunStats, id: string, outcome: WriteOutcome) {
  stats.total += 1;
  if (outcome === "created") stats.migrated += 1;
  else if (outcome === "skipped-existing") stats.skippedExisting += 1;
  else {
    stats.failed += 1;
    stats.failedIds.push(id);
  }
}

function printStats(stats: RunStats) {
  console.log(
    `${stats.collection}: ${stats.migrated}/${stats.total} migrated, ` +
      `${stats.skippedExisting} skipped (already exist), ${stats.failed} failed`,
  );
}

// ---------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------

async function main() {
  const dryRun = process.argv.includes("--dry-run");
  const config = loadConfig({ dryRun });

  console.log(`MyTasks Firestore -> Appwrite migration${dryRun ? " (DRY RUN - no writes will be made)" : ""}`);
  console.log(`Source: Firestore database "${config.firestoreDatabaseId}"`);
  console.log(
    `Target: Appwrite database "${config.appwriteDatabaseId}" @ ${dryRun ? "(not connected in dry-run)" : config.appwriteEndpoint}`,
  );
  console.log("");

  const db = getSourceFirestore(config.firestoreDatabaseId);

  // --- Read everything up front (read-only, safe against production) ---
  console.log("Reading users...");
  const firestoreUsers = await fetchAllUsers(db);
  console.log(`Reading lists...`);
  const firestoreLists = await fetchAllLists(db);

  console.log("Reading tasks for each list...");
  const tasksByListId = new Map<string, FirestoreTaskDoc[]>();
  for (const list of firestoreLists) {
    tasksByListId.set(list.id, await fetchTasksForList(db, list.id));
  }
  const allTasks = [...tasksByListId.values()].flat();

  // --- Validate ---
  const knownListIds = new Set(firestoreLists.map((l) => l.id));
  const anomalies: Anomaly[] = [
    ...firestoreUsers.flatMap(checkUser),
    ...firestoreLists.flatMap(checkList),
    ...allTasks.flatMap((t) => checkTask(t, knownListIds)),
  ];

  console.log("");
  console.log(
    `Found ${firestoreUsers.length} users, ${firestoreLists.length} lists, ${allTasks.length} tasks.`,
  );
  if (anomalies.length > 0) {
    console.log(`\n${anomalies.length} anomaly(ies) found:`);
    for (const a of anomalies) {
      console.log(`  [${a.collection}/${a.id}] ${a.issue}`);
    }
  } else {
    console.log("No anomalies found.");
  }

  if (dryRun) {
    console.log("\n--- DRY RUN SUMMARY (no writes were made) ---");
    console.log(`users:  ${firestoreUsers.length} would be migrated`);
    console.log(`lists:  ${firestoreLists.length} would be migrated`);
    console.log(`tasks:  ${allTasks.length} would be migrated`);

    console.log("\nSample computed permissions:");
    for (const list of firestoreLists.slice(0, 2)) {
      const perms = listDocPermissions(list.ownerId ?? "", list.memberIds ?? []);
      console.log(`  lists/${list.id} (owner=${list.ownerId ?? "?"}, members=${(list.memberIds ?? []).join(",") || "none"}):`);
      console.log(`    ${JSON.stringify(perms)}`);
    }
    for (const list of firestoreLists.slice(0, 2)) {
      const listTasks = tasksByListId.get(list.id) ?? [];
      if (listTasks.length === 0) continue;
      const perms = taskDocPermissions(list.ownerId ?? "", list.memberIds ?? []);
      console.log(`  tasks in list ${list.id} (${listTasks.length} task(s)):`);
      console.log(`    ${JSON.stringify(perms)}`);
    }
    if (firestoreUsers.length > 0) {
      const u = firestoreUsers[0];
      console.log(`  users/${u.uid}:`);
      console.log(`    ${JSON.stringify(userDocPermissions(u.uid))}`);
    }

    console.log(`\n${anomalies.length} anomaly(ies) total (see above).`);
    console.log("\nDry run complete. No data was written to Appwrite.");
    return;
  }

  // --- Real run ---
  const client = buildClient(config);
  const users = new Users(client);
  const databases = new Databases(client);

  // Step 1 + 2: pre-create Auth users, then migrate users/ docs.
  const userAuthStats = newRunStats("users (auth accounts)");
  const userDocStats = newRunStats("users");
  for (const u of firestoreUsers) {
    if (!u.email) {
      // Already reported as an anomaly above; can't create an Appwrite
      // Auth user with no email and no phone/password, so skip both the
      // auth account and the profile doc for this record.
      record(userAuthStats, u.uid, "failed");
      record(userDocStats, u.uid, "failed");
      continue;
    }
    try {
      const outcome = await createAuthUser(users, u.uid, u.email, u.displayName ?? "");
      record(userAuthStats, u.uid, outcome);
    } catch (err) {
      console.error(`[users/${u.uid}] failed to create Auth user: ${errorMessage(err)}`);
      record(userAuthStats, u.uid, "failed");
      // Still attempt the profile doc below - Auth user creation and
      // profile doc creation are independent failure modes worth
      // reporting separately, and a retry of just the doc could still
      // succeed if the Auth user already exists from a prior partial run.
    }

    try {
      const outcome = await createDocument(
        databases,
        config.appwriteDatabaseId,
        config.appwriteUsersCollectionId,
        u.uid,
        toAppwriteUser(u),
        userDocPermissions(u.uid),
      );
      record(userDocStats, u.uid, outcome);
    } catch (err) {
      console.error(`[users/${u.uid}] failed to create profile document: ${errorMessage(err)}`);
      record(userDocStats, u.uid, "failed");
    }
  }
  printStats(userAuthStats);
  printStats(userDocStats);

  // Step 3: migrate lists/, carrying ownerId/memberIds forward in-memory
  // for the tasks pass.
  const listStats = newRunStats("lists");
  const membershipByListId = new Map<string, ListMembership>();
  for (const l of firestoreLists) {
    const ownerId = l.ownerId ?? "";
    const memberIds = l.memberIds ?? [];
    membershipByListId.set(l.id, { ownerId, memberIds });

    try {
      const outcome = await createDocument(
        databases,
        config.appwriteDatabaseId,
        config.appwriteListsCollectionId,
        l.id,
        toAppwriteList(l),
        listDocPermissions(ownerId, memberIds),
      );
      record(listStats, l.id, outcome);
    } catch (err) {
      console.error(`[lists/${l.id}] failed to create document: ${errorMessage(err)}`);
      record(listStats, l.id, "failed");
    }
  }
  printStats(listStats);

  // Step 4: migrate tasks/, permissions from the in-memory membership map.
  const taskStats = newRunStats("tasks");
  for (const [listId, tasks] of tasksByListId) {
    const membership = membershipByListId.get(listId);
    if (!membership) {
      // Should be unreachable (tasksByListId is keyed from the same
      // firestoreLists we just iterated), but guard defensively rather
      // than crash the whole run.
      for (const t of tasks) {
        console.error(`[tasks/${t.id}] no membership info for parent list ${listId}; skipping`);
        record(taskStats, t.id, "failed");
      }
      continue;
    }

    const permissions = taskDocPermissions(membership.ownerId, membership.memberIds);
    for (const t of tasks) {
      try {
        const outcome = await createDocument(
          databases,
          config.appwriteDatabaseId,
          config.appwriteTasksCollectionId,
          t.id,
          toAppwriteTask(t),
          permissions,
        );
        record(taskStats, t.id, outcome);
      } catch (err) {
        console.error(`[tasks/${t.id}] failed to create document: ${errorMessage(err)}`);
        record(taskStats, t.id, "failed");
      }
    }
  }
  printStats(taskStats);

  // --- Final summary ---
  console.log("\n--- FINAL SUMMARY ---");
  for (const stats of [userAuthStats, userDocStats, listStats, taskStats]) {
    printStats(stats);
  }

  const allFailed = [
    ...userAuthStats.failedIds.map((id) => `users(auth)/${id}`),
    ...userDocStats.failedIds.map((id) => `users/${id}`),
    ...listStats.failedIds.map((id) => `lists/${id}`),
    ...taskStats.failedIds.map((id) => `tasks/${id}`),
  ];
  if (allFailed.length > 0) {
    console.log(`\n${allFailed.length} record(s) failed and should be investigated/retried:`);
    for (const id of allFailed) console.log(`  ${id}`);
  } else {
    console.log("\nAll records migrated or already existed. No failures.");
  }

  if (anomalies.length > 0) {
    console.log(`\nReminder: ${anomalies.length} anomaly(ies) were flagged before writing began (see above).`);
  }
}

main().catch((err) => {
  console.error("Migration failed with an unexpected error:", err);
  process.exitCode = 1;
});
