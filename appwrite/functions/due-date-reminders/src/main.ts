import { Client, Databases, Query, Models } from "node-appwrite";
import { sendToUser } from "./sendToUser";

interface TaskDoc extends Models.Document {
  title?: string;
  assigneeId?: string;
  completed?: boolean;
}

interface FunctionContext {
  req: { headers: Record<string, string> };
  res: { json: (data: unknown, statusCode?: number) => unknown };
  log: (message: unknown) => void;
  error: (message: unknown) => void;
}

const DATABASE_ID = process.env.APPWRITE_DATABASE_ID ?? "mytasks";
const TASKS_COLLECTION_ID = process.env.APPWRITE_COLLECTION_TASKS_ID ?? "tasks";

/** Sweeps the (now flat) tasks collection for tasks due within the next 15
 *  minutes that haven't been reminded about yet. A per-list on-device
 *  WorkManager reminder (see ReminderScheduler.kt) also fires locally as a
 *  fallback for the currently signed-in device; this is what reaches every
 *  other device / the assignee when they aren't the one who set the
 *  reminder.
 *
 *  Ported from functions/src/notifications.ts's dueDateReminders(). The
 *  original needed a Firestore collection-group query across every list's
 *  tasks subcollection; since `tasks` is now a single flat collection with
 *  a `listId` field, this is just one ordinary query. */
export default async ({ req, res, log, error }: FunctionContext) => {
  const client = new Client()
    .setEndpoint(process.env.APPWRITE_FUNCTION_API_ENDPOINT ?? "")
    .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID ?? "")
    .setKey(req.headers["x-appwrite-key"] ?? "");

  const databases = new Databases(client);

  const windowEnd = new Date(Date.now() + 15 * 60 * 1000).toISOString();

  const dueTasks = await databases.listDocuments<TaskDoc>(
    DATABASE_ID,
    TASKS_COLLECTION_ID,
    [
      Query.equal("notify", true),
      Query.equal("reminderSent", false),
      Query.lessThanEqual("dueAt", windowEnd),
      Query.limit(100),
    ],
  );

  if (dueTasks.documents.length === 0) {
    return res.json({ success: true, processed: 0 });
  }

  // No Appwrite equivalent of Firestore's atomic batch write exists, so
  // each task's reminderSent flag is updated independently via
  // Promise.all. This is an accepted small atomicity gap: if a send
  // succeeds but the reminderSent update fails (or the function is
  // interrupted), the worst case is the same task's reminder being sent
  // twice on the next sweep - never data corruption or a lost reminder.
  await Promise.all(
    dueTasks.documents.map(async (task) => {
      try {
        if (task.completed) {
          await databases.updateDocument(DATABASE_ID, TASKS_COLLECTION_ID, task.$id, {
            reminderSent: true,
          });
          return;
        }

        if (task.assigneeId) {
          try {
            await sendToUser(databases, task.assigneeId, "dueSoon", task.title);
          } catch (err) {
            error(`Failed to send due-date reminder for task ${task.$id}: ${err instanceof Error ? err.stack ?? err.message : err}`);
          }
        }

        await databases.updateDocument(DATABASE_ID, TASKS_COLLECTION_ID, task.$id, {
          reminderSent: true,
        });
      } catch (err) {
        error(`Failed to process due-date reminder for task ${task.$id}: ${err instanceof Error ? err.stack ?? err.message : err}`);
      }
    }),
  );

  return res.json({ success: true, processed: dueTasks.documents.length });
};
