import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import * as admin from "firebase-admin";

async function sendToUser(uid: string, title: string, body: string) {
  const userSnap = await admin.firestore().collection("users").doc(uid).get();
  const tokens: string[] = userSnap.get("fcmTokens") ?? [];
  if (tokens.length === 0) return;

  const response = await admin.messaging().sendEachForMulticast({
    tokens,
    notification: { title, body },
    android: { priority: "high" },
  });

  const staleTokens = response.responses
    .map((r, i) => (r.success ? null : tokens[i]))
    .filter((t): t is string => t !== null);
  if (staleTokens.length > 0) {
    await admin
      .firestore()
      .collection("users")
      .doc(uid)
      .update({ fcmTokens: admin.firestore.FieldValue.arrayRemove(...staleTokens) });
  }
}

/** Notifies a task's assignee whenever they're newly assigned (or
 *  reassigned) to a task, so they find out even if the app isn't open. */
export const onTaskWrite = onDocumentWritten("lists/{listId}/tasks/{taskId}", async (event) => {
  const before = event.data?.before?.data();
  const after = event.data?.after?.data();
  if (!after) return; // task deleted

  const assigneeId: string | undefined = after.assigneeId;
  const previousAssigneeId: string | undefined = before?.assigneeId;
  if (!assigneeId || assigneeId === previousAssigneeId) return;

  const title = after.title ?? "New task";
  try {
    await sendToUser(assigneeId, "You were assigned a task", title);
  } catch (error) {
    logger.error(`Failed to notify assignee ${assigneeId}`, error);
  }
});

/** Sweeps every list's tasks for ones due within the next 15 minutes that
 *  haven't been reminded about yet. A per-list on-device WorkManager
 *  reminder (see ReminderScheduler.kt) also fires locally as a fallback
 *  for the currently signed-in device; this is what reaches every other
 *  device / the assignee when they aren't the one who set the reminder. */
export const dueDateReminders = onSchedule("every 15 minutes", async () => {
  const windowEnd = admin.firestore.Timestamp.fromMillis(Date.now() + 15 * 60 * 1000);

  const dueTasks = await admin
    .firestore()
    .collectionGroup("tasks")
    .where("notify", "==", true)
    .where("reminderSent", "==", false)
    .where("dueAt", "<=", windowEnd)
    .get();

  if (dueTasks.empty) return;

  const batch = admin.firestore().batch();
  for (const doc of dueTasks.docs) {
    const task = doc.data();
    if (task.completed) {
      batch.update(doc.ref, { reminderSent: true });
      continue;
    }
    if (task.assigneeId) {
      try {
        await sendToUser(task.assigneeId, "Task due soon", task.title ?? "Task");
      } catch (error) {
        logger.error(`Failed to send due-date reminder for ${doc.ref.path}`, error);
      }
    }
    batch.update(doc.ref, { reminderSent: true });
  }
  await batch.commit();
});
