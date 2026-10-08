import { Client, TablesDB, Permission, Role, Models } from "node-appwrite";
import type { FunctionContext } from "./context";

interface UpdateMembersBody {
  listId?: string;
  action?: "add" | "remove";
  uid?: string;
}

interface Member {
  uid: string;
  displayName: string;
  email: string;
  photoUrl: string;
}

const DATABASE_ID = process.env.APPWRITE_DATABASE_ID ?? "mytasklist";
const LISTS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_LISTS_ID ?? "lists";
const USERS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_USERS_ID ?? "users";

function listPermissions(ownerId: string, memberIds: string[]): string[] {
  return [
    Permission.read(Role.user(ownerId)),
    Permission.update(Role.user(ownerId)),
    Permission.delete(Role.user(ownerId)),
    ...memberIds.filter((id) => id !== ownerId).map((id) => Permission.read(Role.user(id))),
  ];
}

function parseMembers(value: unknown): Member[] {
  if (typeof value !== "string") return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? (parsed as Member[]) : [];
  } catch {
    return [];
  }
}

/**
 * Adds/removes a list member on behalf of the list's owner. Must run
 * server-side: Appwrite only lets a client grant permissions for roles it
 * itself holds, so the owner's app can't write `read(user:<invitee>)` onto
 * a list - it fails with "Permissions must be one of: (any, users, user:<own id>...)".
 *
 * The caller is identified by the x-appwrite-user-id header (only injected
 * by Appwrite for an authenticated caller) and must be the list's owner.
 * Member profile details come from the users table, not the request.
 *
 * HTTP-invoked at path "/update-members" - see main.ts's trigger dispatch.
 */
export async function updateMembers({ req, res, error }: FunctionContext) {
  const callerId = req.headers["x-appwrite-user-id"];
  if (!callerId) {
    return res.json({ success: false, message: "Not authenticated." }, 401);
  }

  const { listId, action, uid } = (req.bodyJson as UpdateMembersBody | undefined) ?? {};
  if (!listId || !uid || (action !== "add" && action !== "remove")) {
    return res.json({ success: false, message: "Invalid request." }, 400);
  }

  const client = new Client()
    .setEndpoint(process.env.APPWRITE_FUNCTION_API_ENDPOINT ?? "")
    .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID ?? "")
    .setKey(req.headers["x-appwrite-key"] ?? "");
  const tablesDB = new TablesDB(client);

  try {
    const list = (await tablesDB.getRow({
      databaseId: DATABASE_ID,
      tableId: LISTS_COLLECTION_ID,
      rowId: listId,
    })) as Models.Row & { ownerId: string; memberIds?: string[]; members?: string };

    if (list.ownerId !== callerId) {
      return res.json({ success: false, message: "Only the list owner can change members." }, 403);
    }

    let memberIds = list.memberIds ?? [];
    let members = parseMembers(list.members);

    if (action === "add") {
      if (!memberIds.includes(uid) && uid !== list.ownerId) {
        const profile = (await tablesDB.getRow({
          databaseId: DATABASE_ID,
          tableId: USERS_COLLECTION_ID,
          rowId: uid,
        })) as Models.Row & Partial<Member>;
        memberIds = [...memberIds, uid];
        members = [
          ...members,
          {
            uid,
            displayName: profile.displayName ?? "",
            email: profile.email ?? "",
            photoUrl: profile.photoUrl ?? "",
          },
        ];
      }
    } else {
      memberIds = memberIds.filter((id) => id !== uid);
      members = members.filter((m) => m.uid !== uid);
    }

    await tablesDB.updateRow({
      databaseId: DATABASE_ID,
      tableId: LISTS_COLLECTION_ID,
      rowId: listId,
      data: { memberIds, members: JSON.stringify(members) },
      permissions: listPermissions(list.ownerId, memberIds),
    });
    return res.json({ success: true });
  } catch (err) {
    error(`updateMembers failed: ${err instanceof Error ? err.stack ?? err.message : err}`);
    return res.json({ success: false, message: err instanceof Error ? err.message : "Failed." }, 500);
  }
}
