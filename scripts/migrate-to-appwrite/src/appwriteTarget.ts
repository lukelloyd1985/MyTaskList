/** Write-side access to the target Appwrite project: Auth user
 *  pre-creation, Databases document creation, and the permission-array
 *  builders described in the migration brief's Step 2. */

import {
  Client,
  Databases,
  Users,
  ID,
  Permission,
  Role,
  AppwriteException,
} from "node-appwrite";
import type { Config } from "./env";

export function buildClient(config: Config): Client {
  return new Client()
    .setEndpoint(config.appwriteEndpoint)
    .setProject(config.appwriteProjectId)
    .setKey(config.appwriteApiKey);
}

// --- Permission builders (Step 2 of the brief) ---

export function userDocPermissions(uid: string): string[] {
  return [
    Permission.read(Role.users()),
    Permission.update(Role.user(uid)),
    Permission.delete(Role.user(uid)),
  ];
}

export function listDocPermissions(ownerId: string, memberIds: string[]): string[] {
  const perms = [
    Permission.read(Role.user(ownerId)),
    Permission.update(Role.user(ownerId)),
    Permission.delete(Role.user(ownerId)),
  ];
  for (const memberId of memberIds) {
    if (memberId === ownerId) continue; // owner already has full read access above
    perms.push(Permission.read(Role.user(memberId)));
  }
  return perms;
}

export function taskDocPermissions(ownerId: string, memberIds: string[]): string[] {
  // Owner and every list member all get full CRUD on every task in that
  // list - see Step 2 of the brief ("any list member can fully CRUD
  // tasks"). De-duplicate in case ownerId also appears in memberIds.
  const uids = new Set<string>([ownerId, ...memberIds]);
  const perms: string[] = [];
  for (const uid of uids) {
    perms.push(
      Permission.read(Role.user(uid)),
      Permission.update(Role.user(uid)),
      Permission.delete(Role.user(uid)),
    );
  }
  return perms;
}

/** True when an AppwriteException represents "this exact document/user
 *  already exists" (HTTP 409 Conflict) - the expected outcome when
 *  re-running the script after a partial prior run, since every write
 *  uses a preserved/custom ID. Treated as "already migrated, skip", not
 *  a failure. */
export function isConflictError(err: unknown): boolean {
  return err instanceof AppwriteException && err.code === 409;
}

export function errorMessage(err: unknown): string {
  if (err instanceof AppwriteException) {
    return `AppwriteException(code=${err.code}, type=${err.type ?? "?"}): ${err.message}`;
  }
  if (err instanceof Error) return err.message;
  return String(err);
}

export { ID, Databases, Users };
