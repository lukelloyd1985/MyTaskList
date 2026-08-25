import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import * as admin from "firebase-admin";

type SupportedLocale = "en" | "sk" | "cs";

/** Mirrors the values(-sk/-cs)/strings.xml translations on the Android
 *  side (see README "Localization") for the two notification kinds this
 *  backend sends - kept here rather than templated from the client since
 *  these are sent from a Cloud Function with no UI context. `untitled`
 *  is what a task's body falls back to when it has no title. */
const NOTIFICATION_STRINGS: Record<SupportedLocale, {
  assigned: { title: string; untitled: string };
  dueSoon: { title: string; untitled: string };
}> = {
  en: {
    assigned: { title: "You were assigned a task", untitled: "New task" },
    dueSoon: { title: "Task due soon", untitled: "Task" },
  },
  sk: {
    assigned: { title: "Bola vám priradená úloha", untitled: "Nová úloha" },
    dueSoon: { title: "Termín úlohy sa blíži", untitled: "Úloha" },
  },
  cs: {
    assigned: { title: "Byl vám přiřazen úkol", untitled: "Nový úkol" },
    dueSoon: { title: "Termín úkolu se blíží", untitled: "Úkol" },
  },
};

function resolveLocale(locale: unknown): SupportedLocale {
  return locale === "sk" || locale === "cs" ? locale : "en";
}

/** Sends a push to every one of a user's registered devices, localized to
 *  their `users/{uid}`.locale (see UserRepository.upsertProfile) with a
 *  fallback to English for profiles that predate that field or use an
 *  unsupported language. `taskTitle` is user-authored content and is
 *  never translated - only the notification's own title, and the body's
 *  fallback for an untitled task, are. */
async function sendToUser(uid: string, kind: "assigned" | "dueSoon", taskTitle?: string) {
  const userSnap = await admin.firestore().collection("users").doc(uid).get();
  const tokens: string[] = userSnap.get("fcmTokens") ?? [];
  if (tokens.length === 0) return;

  const strings = NOTIFICATION_STRINGS[resolveLocale(userSnap.get("locale"))][kind];
  const title = strings.title;
  const body = taskTitle ?? strings.untitled;

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

  try {
    await sendToUser(assigneeId, "assigned", after.title);
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
        await sendToUser(task.assigneeId, "dueSoon", task.title);
      } catch (error) {
        logger.error(`Failed to send due-date reminder for ${doc.ref.path}`, error);
      }
    }
    batch.update(doc.ref, { reminderSent: true });
  }
  await batch.commit();
});
