import { Client } from "node-appwrite";
import { sendToUser } from "./sendToUser";
import type { FunctionContext } from "./context";

interface TaskDoc {
  $id: string;
  title?: string;
  assigneeId?: string;
}

/** Notifies a task's assignee whenever the task document is written to and
 *  currently has a non-empty assigneeId, so they find out even if the app
 *  isn't open.
 *
 *  DELIBERATE SIMPLIFICATION vs. the original Firestore trigger
 *  (functions/src/notifications.ts's onTaskWrite): Firestore's
 *  onDocumentWritten gave us both the before- and after-images of the
 *  document, so the original code only notified when assigneeId actually
 *  *changed* (`assigneeId !== previousAssigneeId`). Appwrite's database
 *  event payload only carries the document as it now stands - there is no
 *  "before" snapshot available to a function - so we can't do that diff
 *  here. As implemented, ANY update to an already-assigned task (editing
 *  its title, due date, priority, etc.) will re-send an "assigned" push to
 *  the same assignee. If that proves annoying in practice, adding a
 *  `lastNotifiedAssigneeId` field to the task (set here after a successful
 *  send, compared against `assigneeId` before sending) would restore the
 *  original de-duplicated behavior - not implemented now to keep this port
 *  a faithful/minimal translation of the specified behavior.
 *
 *  Triggered by the database event on tasks documents.*.update - see
 *  main.ts's trigger dispatch. */
export async function onTaskWrite({ req, res, error }: FunctionContext) {
  const client = new Client()
    .setEndpoint(process.env.APPWRITE_FUNCTION_API_ENDPOINT ?? "")
    .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID ?? "")
    .setKey(req.headers["x-appwrite-key"] ?? "");

  const task = req.bodyJson as TaskDoc | undefined;
  const assigneeId = task?.assigneeId;

  if (!task || !assigneeId) {
    return res.json({ success: true, skipped: true });
  }

  try {
    await sendToUser(client, assigneeId, "assigned", task.title);
  } catch (err) {
    error(`Failed to notify assignee ${assigneeId}: ${err instanceof Error ? err.stack ?? err.message : err}`);
  }

  return res.json({ success: true });
}
