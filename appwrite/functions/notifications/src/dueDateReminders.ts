import { Client, TablesDB, Query, Models } from "node-appwrite";
import { sendToUser } from "./sendToUser";
import type { FunctionContext } from "./context";

interface TaskDoc extends Models.Row {
  listId: string;
  title?: string;
  assigneeId?: string;
  completed?: boolean;
}

const DATABASE_ID = process.env.APPWRITE_DATABASE_ID ?? "mytasks";
const TASKS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_TASKS_ID ?? "tasks";

/** Sweeps the (now flat) tasks table for tasks due within the next day
 *  that haven't been reminded about yet - the CRON schedule keeps running
 *  every 15 minutes (see appwrite.json), so a task is caught within about
 *  15 minutes of crossing the 24-hour-out mark, not just as it's about to
 *  become due. A per-list on-device WorkManager reminder (see
 *  ReminderScheduler.kt) also fires locally a day ahead as a fallback for
 *  the currently signed-in device; this is what reaches every other
 *  device / the assignee when they aren't the one who set the reminder.
 *
 *  Ported from functions/src/notifications.ts's dueDateReminders(). The
 *  original needed a Firestore collection-group query across every list's
 *  tasks subcollection; since `tasks` is now a single flat table with
 *  a `listId` field, this is just one ordinary query.
 *
 *  Triggered by the CRON schedule - see main.ts's trigger dispatch. */
export async function dueDateReminders({ req, res, error }: FunctionContext) {
  const client = new Client()
    .setEndpoint(process.env.APPWRITE_FUNCTION_API_ENDPOINT ?? "")
    .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID ?? "")
    .setKey(req.headers["x-appwrite-key"] ?? "");

  const tablesDB = new TablesDB(client);

  const windowEnd = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();

  const dueTasks = await tablesDB.listRows<TaskDoc>({
    databaseId: DATABASE_ID,
    tableId: TASKS_COLLECTION_ID,
    queries: [
      Query.equal("notify", true),
      Query.equal("reminderSent", false),
      Query.lessThanEqual("dueAt", windowEnd),
      Query.limit(100),
    ],
  });

  if (dueTasks.rows.length === 0) {
    return res.json({ success: true, processed: 0 });
  }

  // No Appwrite equivalent of Firestore's atomic batch write exists, so
  // each task's reminderSent flag is updated independently via
  // Promise.all. This is an accepted small atomicity gap: if a send
  // succeeds but the reminderSent update fails (or the function is
  // interrupted), the worst case is the same task's reminder being sent
  // twice on the next sweep - never data corruption or a lost reminder.
  await Promise.all(
    dueTasks.rows.map(async (task) => {
      try {
        if (task.completed) {
          await tablesDB.updateRow({
            databaseId: DATABASE_ID,
            tableId: TASKS_COLLECTION_ID,
            rowId: task.$id,
            data: { reminderSent: true },
          });
          return;
        }

        if (task.assigneeId) {
          try {
            await sendToUser(client, task.assigneeId, "dueSoon", task.title, task.listId, task.$id);
          } catch (err) {
            error(`Failed to send due-date reminder for task ${task.$id}: ${err instanceof Error ? err.stack ?? err.message : err}`);
          }
        }

        await tablesDB.updateRow({
          databaseId: DATABASE_ID,
          tableId: TASKS_COLLECTION_ID,
          rowId: task.$id,
          data: { reminderSent: true },
        });
      } catch (err) {
        error(`Failed to process due-date reminder for task ${task.$id}: ${err instanceof Error ? err.stack ?? err.message : err}`);
      }
    }),
  );

  return res.json({ success: true, processed: dueTasks.rows.length });
}
