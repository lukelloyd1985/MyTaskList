import { Client, TablesDB, Query, Permission, Role, Models } from "node-appwrite";
import { listAllRows } from "./listAll";
import type { FunctionContext } from "./context";

interface ListDoc extends Models.Row {
  ownerId: string;
  memberIds?: string[];
}

interface TaskDoc extends Models.Row {
  listId: string;
}

const DATABASE_ID = process.env.APPWRITE_DATABASE_ID ?? "mytasks";
const TASKS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_TASKS_ID ?? "tasks";

/** Every current owner + member of the list gets full read/update/delete
 *  on each of its tasks - matches the current Firestore rule, where any
 *  list member can fully CRUD tasks under it. */
function buildTaskPermissions(ownerId: string, memberIds: string[]): string[] {
  const ids = new Set([ownerId, ...memberIds]);
  const permissions: string[] = [];
  for (const id of ids) {
    permissions.push(Permission.read(Role.user(id)));
    permissions.push(Permission.update(Role.user(id)));
    permissions.push(Permission.delete(Role.user(id)));
  }
  return permissions;
}

/**
 * NEW function - not a port of any Firebase code. It exists because
 * Appwrite permissions are static ACLs stored on each row, not a
 * live rule evaluation against a parent document the way Firestore's
 * security rules were (the old firestore.rules `parentList()` lookup
 * re-checked list membership on every single task read/write). Appwrite
 * has nothing equivalent for rows in a different, flat table,
 * so whenever a list's ownerId/memberIds change, every task under that
 * list has to have its permissions explicitly recomputed and pushed here
 * - otherwise a removed member would silently keep access to that list's
 * tasks (or a newly added member would not yet have access) until
 * something else happened to touch each task.
 *
 * Database event on lists documents.*.update - see main.ts's trigger
 * dispatch.
 */
export async function syncListPermissions({ req, res, error }: FunctionContext) {
  const list = req.bodyJson as ListDoc | undefined;
  if (!list || !list.$id) {
    return res.json({ success: true, skipped: true });
  }

  const client = new Client()
    .setEndpoint(process.env.APPWRITE_FUNCTION_API_ENDPOINT ?? "")
    .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID ?? "")
    .setKey(req.headers["x-appwrite-key"] ?? "");

  const tablesDB = new TablesDB(client);

  const memberIds = list.memberIds ?? [];
  const permissions = buildTaskPermissions(list.ownerId, memberIds);

  const tasks = await listAllRows<TaskDoc>(tablesDB, DATABASE_ID, TASKS_COLLECTION_ID, [
    Query.equal("listId", list.$id),
  ]);

  if (tasks.length === 0) {
    return res.json({ success: true, synced: 0 });
  }

  try {
    // Only the permissions array is replaced here - no data fields are
    // touched, so this can't clobber a concurrent edit to the task
    // itself. No Appwrite batch-write primitive exists, so (as with the
    // due-date reminder sweep) this is a Promise.all of independent
    // updates - an accepted small atomicity gap, not a data-corruption
    // risk.
    await Promise.all(
      tasks.map((task) =>
        tablesDB.updateRow({
          databaseId: DATABASE_ID,
          tableId: TASKS_COLLECTION_ID,
          rowId: task.$id,
          data: {},
          permissions,
        }),
      ),
    );
  } catch (err) {
    error(
      `Failed to sync permissions for one or more tasks under list ${list.$id}: ${
        err instanceof Error ? err.stack ?? err.message : err
      }`,
    );
    return res.json({ success: false }, 500);
  }

  return res.json({ success: true, synced: tasks.length });
}
