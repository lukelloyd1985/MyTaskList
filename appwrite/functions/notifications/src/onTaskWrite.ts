import { Client, TablesDB } from "node-appwrite";
import { sendToUser } from "./sendToUser";
import type { FunctionContext } from "./context";

/** Assignees of a task, falling back to the legacy single assigneeId for
 *  tasks written before multi-assignee support. */
export function taskAssigneeIds(task: { assigneeId?: string; assigneeIds?: string[] }): string[] {
  if (task.assigneeIds && task.assigneeIds.length > 0) return task.assigneeIds;
  return task.assigneeId ? [task.assigneeId] : [];
}

interface TaskDoc {
  $id: string;
  listId: string;
  title?: string;
  assigneeId?: string; // legacy single assignee
  assigneeIds?: string[];
  notifiedAssigneeIds?: string[];
}

const DATABASE_ID = process.env.APPWRITE_DATABASE_ID ?? "mytasklist";
const TASKS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_TASKS_ID ?? "tasks";

/** Notifies each assignee who has just been assigned to a task - not on
 *  every other write to it (completing, reordering, editing, permission
 *  syncs...), which all fire the same task-update event.
 *
 *  Appwrite's event payload has no "before" snapshot, so the task keeps a
 *  `notifiedAssigneeIds` column: the assignees already notified. Only
 *  assignees missing from it are notified, and it's then set to the current
 *  assignee list (which also forgets removed assignees, so re-assigning
 *  them later notifies again). It's written *before* sending, from a fresh
 *  read, so the update event that write triggers - and any concurrent
 *  event - finds nothing new to send.
 *
 *  Triggered by the database event on tasks documents.*.update - see
 *  main.ts's trigger dispatch. */
export async function onTaskWrite({ req, res, error }: FunctionContext) {
  const client = new Client()
    .setEndpoint(process.env.APPWRITE_FUNCTION_API_ENDPOINT ?? "")
    .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID ?? "")
    .setKey(req.headers["x-appwrite-key"] ?? "");

  const payload = req.bodyJson as TaskDoc | undefined;
  if (!payload || !payload.$id) {
    return res.json({ success: true, skipped: true });
  }

  const tablesDB = new TablesDB(client);
  let task: TaskDoc;
  try {
    task = (await tablesDB.getRow({
      databaseId: DATABASE_ID,
      tableId: TASKS_COLLECTION_ID,
      rowId: payload.$id,
    })) as unknown as TaskDoc;
  } catch (err) {
    error(`Failed to read task ${payload.$id}: ${err instanceof Error ? err.stack ?? err.message : err}`);
    return res.json({ success: false }, 500);
  }

  const assigneeIds = taskAssigneeIds(task);
  const notified = task.notifiedAssigneeIds ?? [];
  const newlyAssigned = assigneeIds.filter((id) => !notified.includes(id));

  const unchanged =
    assigneeIds.length === notified.length && assigneeIds.every((id) => notified.includes(id));
  if (unchanged) {
    return res.json({ success: true, skipped: true });
  }

  try {
    await tablesDB.updateRow({
      databaseId: DATABASE_ID,
      tableId: TASKS_COLLECTION_ID,
      rowId: task.$id,
      data: { notifiedAssigneeIds: assigneeIds },
    });
  } catch (err) {
    error(`Failed to record notified assignees on ${task.$id}: ${err instanceof Error ? err.stack ?? err.message : err}`);
    return res.json({ success: false }, 500);
  }

  await Promise.all(
    newlyAssigned.map(async (assigneeId) => {
      try {
        await sendToUser(client, assigneeId, "assigned", task.title, task.listId, task.$id);
      } catch (err) {
        error(`Failed to notify assignee ${assigneeId}: ${err instanceof Error ? err.stack ?? err.message : err}`);
      }
    }),
  );

  return res.json({ success: true, notified: newlyAssigned.length });
}
