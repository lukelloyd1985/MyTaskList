import { Client, TablesDB, Users, Query, Permission, Role, Models } from "node-appwrite";
import { listAllRows } from "./listAll";
import type { FunctionContext } from "./context";

interface ListMemberData {
  uid: string;
  displayName?: string;
  email?: string;
  photoUrl?: string;
}

interface ListDoc extends Models.Row {
  ownerId: string;
  ownerName?: string;
  visibility: "PRIVATE" | "SHARED";
  memberIds?: string[];
  members?: string; // JSON-encoded ListMemberData[] - Appwrite has no array-of-objects attribute
}

interface TaskDoc extends Models.Row {
  listId: string;
  assigneeId?: string;
}

const DATABASE_ID = process.env.APPWRITE_DATABASE_ID ?? "mytasklist";
const USERS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_USERS_ID ?? "users";
const LISTS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_LISTS_ID ?? "lists";
const TASKS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_TASKS_ID ?? "tasks";

function parseMembers(json: string | undefined): ListMemberData[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? (parsed as ListMemberData[]) : [];
  } catch {
    return [];
  }
}

/** Owner gets full read/update/delete; every other member gets read-only -
 *  mirrors the `lists` permission model. Rebuilt wholesale because
 *  Appwrite's per-row permission array isn't incrementally patchable,
 *  it's replaced entirely on every updateRow() call. */
function buildListPermissions(ownerId: string, memberIds: string[]): string[] {
  const permissions = [
    Permission.read(Role.user(ownerId)),
    Permission.update(Role.user(ownerId)),
    Permission.delete(Role.user(ownerId)),
  ];
  for (const memberId of memberIds) {
    if (memberId === ownerId) continue;
    permissions.push(Permission.read(Role.user(memberId)));
  }
  return permissions;
}

async function unassignTasks(tablesDB: TablesDB, listId: string, uid: string) {
  const tasks = await listAllRows<TaskDoc>(tablesDB, DATABASE_ID, TASKS_COLLECTION_ID, [
    Query.equal("listId", listId),
    Query.equal("assigneeId", uid),
  ]);
  await Promise.all(
    tasks.map((task) =>
      tablesDB.updateRow({
        databaseId: DATABASE_ID,
        tableId: TASKS_COLLECTION_ID,
        rowId: task.$id,
        data: { assigneeId: "", assigneeName: "" },
      }),
    ),
  );
}

async function deleteAllTasksForList(tablesDB: TablesDB, listId: string) {
  const tasks = await listAllRows<TaskDoc>(tablesDB, DATABASE_ID, TASKS_COLLECTION_ID, [
    Query.equal("listId", listId),
  ]);
  await Promise.all(
    tasks.map((task) =>
      tablesDB.deleteRow({ databaseId: DATABASE_ID, tableId: TASKS_COLLECTION_ID, rowId: task.$id }),
    ),
  );
}

/**
 * Deletes the caller's own account: on every list they're part of, either
 * hands off ownership or removes their membership, unassigns any tasks
 * still assigned to them, deletes their `users/{uid}` profile, and finally
 * deletes their Appwrite Auth account. Invoked by the Android app via
 * functions.createExecution() (never directly by a client SDK write)
 * because one user must never be able to delete another's account, and
 * because the database's collection permissions don't allow a client to
 * delete `users/{uid}` outright - only this trusted server-side function
 * (using the dynamic per-execution API key) can do this cascade.
 *
 * Ported from functions/src/accountDeletion.ts's deleteAccount(). HTTP
 * invocation - see main.ts's trigger dispatch.
 */
export async function deleteAccount({ req, res, log, error }: FunctionContext) {
  // Appwrite injects the calling user's ID for an execution invoked with a
  // user session/JWT via x-appwrite-user-id - this must be used instead of
  // anything in the request body, which a caller could forge to target
  // someone else's account. [VERIFY]: exact header name for the current
  // Appwrite Functions runtime template.
  const uid = req.headers["x-appwrite-user-id"];
  if (!uid) {
    return res.json({ success: false, message: "You must be signed in to delete your account." }, 401);
  }

  const client = new Client()
    .setEndpoint(process.env.APPWRITE_FUNCTION_API_ENDPOINT ?? "")
    .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID ?? "")
    .setKey(req.headers["x-appwrite-key"] ?? "");

  const tablesDB = new TablesDB(client);
  const users = new Users(client);

  const [ownedLists, memberLists] = await Promise.all([
    listAllRows<ListDoc>(tablesDB, DATABASE_ID, LISTS_COLLECTION_ID, [Query.equal("ownerId", uid)]),
    listAllRows<ListDoc>(tablesDB, DATABASE_ID, LISTS_COLLECTION_ID, [Query.contains("memberIds", uid)]),
  ]);

  for (const list of ownedLists) {
    const memberIds = list.memberIds ?? [];
    const members = parseMembers(list.members);
    const remainingMemberIds = memberIds.filter((id) => id !== uid);

    if (list.visibility === "SHARED" && remainingMemberIds.length > 0) {
      // Someone else still needs this list, so hand ownership to the
      // longest-standing remaining member instead of deleting it out from
      // under them.
      const newOwnerId = remainingMemberIds[0];
      const newOwner = members.find((m) => m.uid === newOwnerId);
      const remainingMembers = members.filter((m) => m.uid !== uid);

      await tablesDB.updateRow({
        databaseId: DATABASE_ID,
        tableId: LISTS_COLLECTION_ID,
        rowId: list.$id,
        data: {
          ownerId: newOwnerId,
          ownerName: newOwner?.displayName ?? "",
          memberIds: remainingMemberIds,
          members: JSON.stringify(remainingMembers),
        },
        permissions: buildListPermissions(newOwnerId, remainingMemberIds),
      });
      // This update also fires the maintenance function's
      // syncListPermissions handler (it watches lists documents.*.update),
      // which fans the new owner/member set out to every task under this
      // list - we don't need to touch task permissions here ourselves.
      await unassignTasks(tablesDB, list.$id, uid);
    } else {
      // Private, or shared with no one left to hand it to - nothing of
      // this list needs to survive the account it belongs to. Appwrite
      // has no recursive-delete primitive like Firestore's
      // recursiveDelete(), so tasks are deleted explicitly before the
      // list itself.
      await deleteAllTasksForList(tablesDB, list.$id);
      await tablesDB.deleteRow({ databaseId: DATABASE_ID, tableId: LISTS_COLLECTION_ID, rowId: list.$id });
    }
  }

  const ownedListIds = new Set(ownedLists.map((l) => l.$id));
  for (const list of memberLists) {
    if (ownedListIds.has(list.$id)) continue; // handled above
    const memberIds = (list.memberIds ?? []).filter((id) => id !== uid);
    const members = parseMembers(list.members).filter((m) => m.uid !== uid);

    await tablesDB.updateRow({
      databaseId: DATABASE_ID,
      tableId: LISTS_COLLECTION_ID,
      rowId: list.$id,
      data: {
        memberIds,
        members: JSON.stringify(members),
      },
      permissions: buildListPermissions(list.ownerId, memberIds),
    });
    await unassignTasks(tablesDB, list.$id, uid);
  }

  await tablesDB.deleteRow({ databaseId: DATABASE_ID, tableId: USERS_COLLECTION_ID, rowId: uid });

  try {
    await users.delete(uid);
  } catch (err) {
    error(
      `Deleted data for ${uid} but failed to delete their Auth account: ${
        err instanceof Error ? err.stack ?? err.message : err
      }`,
    );
    return res.json(
      {
        success: false,
        message: "Your data was deleted, but removing your sign-in account failed. Please contact support.",
      },
      500,
    );
  }

  log(`Deleted account and data for ${uid}`);
  return res.json({ success: true });
}
