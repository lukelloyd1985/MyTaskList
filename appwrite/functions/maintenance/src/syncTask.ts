import { Client, TablesDB, Permission, Role, Models } from "node-appwrite";
import type { FunctionContext } from "./context";

const DATABASE_ID = process.env.APPWRITE_DATABASE_ID ?? "mytasklist";
const LISTS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_LISTS_ID ?? "lists";
const TASKS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_TASKS_ID ?? "tasks";

/**
 * Gives a newly created task its parent list's owner + member permissions.
 * The client can only create the row with permissions for its own user
 * (Appwrite rejects grants for roles the caller doesn't hold), so it calls
 * this right after. The caller must be the list's owner or a member.
 *
 * HTTP-invoked at path "/sync-task" - see main.ts's trigger dispatch.
 */
export async function syncTask({ req, res, error }: FunctionContext) {
  const callerId = req.headers["x-appwrite-user-id"];
  if (!callerId) {
    return res.json({ success: false, message: "Not authenticated." }, 401);
  }
  const taskId = (req.bodyJson as { taskId?: string } | undefined)?.taskId;
  if (!taskId) {
    return res.json({ success: false, message: "Invalid request." }, 400);
  }

  const client = new Client()
    .setEndpoint(process.env.APPWRITE_FUNCTION_API_ENDPOINT ?? "")
    .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID ?? "")
    .setKey(req.headers["x-appwrite-key"] ?? "");
  const tablesDB = new TablesDB(client);

  try {
    const task = (await tablesDB.getRow({
      databaseId: DATABASE_ID,
      tableId: TASKS_COLLECTION_ID,
      rowId: taskId,
    })) as Models.Row & { listId: string };
    const list = (await tablesDB.getRow({
      databaseId: DATABASE_ID,
      tableId: LISTS_COLLECTION_ID,
      rowId: task.listId,
    })) as Models.Row & { ownerId: string; memberIds?: string[] };

    const memberIds = list.memberIds ?? [];
    if (callerId !== list.ownerId && !memberIds.includes(callerId)) {
      return res.json({ success: false, message: "Not a member of this list." }, 403);
    }

    const permissions = [...new Set([list.ownerId, ...memberIds])].flatMap((id) => [
      Permission.read(Role.user(id)),
      Permission.update(Role.user(id)),
      Permission.delete(Role.user(id)),
    ]);
    await tablesDB.updateRow({
      databaseId: DATABASE_ID,
      tableId: TASKS_COLLECTION_ID,
      rowId: taskId,
      data: {},
      permissions,
    });
    return res.json({ success: true });
  } catch (err) {
    error(`syncTask failed: ${err instanceof Error ? err.stack ?? err.message : err}`);
    return res.json({ success: false, message: err instanceof Error ? err.message : "Failed." }, 500);
  }
}
