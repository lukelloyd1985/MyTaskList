import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import { Timestamp, FieldValue } from "firebase-admin/firestore";
import { getMessaging, SendResponse } from "firebase-admin/messaging";
import { db, FIRESTORE_DATABASE_ID } from "./firestoreDb";

type SupportedLocale = "en" | "sk" | "cs" | "fr" | "de" | "es" | "it" | "ru";

/** Mirrors the values(-sk/-cs/-fr/-de/-es/-it/-ru)/strings.xml translations
 *  on the Android side (see README "Localization") for the two
 *  notification kinds this backend sends - kept here rather than
 *  templated from the client since these are sent from a Cloud Function
 *  with no UI context. `untitled` is what a task's body falls back to
 *  when it has no title. */
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
  fr: {
    assigned: { title: "Une tâche vous a été attribuée", untitled: "Nouvelle tâche" },
    dueSoon: { title: "Échéance de tâche proche", untitled: "Tâche" },
  },
  de: {
    assigned: { title: "Ihnen wurde eine Aufgabe zugewiesen", untitled: "Neue Aufgabe" },
    dueSoon: { title: "Aufgabe bald fällig", untitled: "Aufgabe" },
  },
  es: {
    assigned: { title: "Se te asignó una tarea", untitled: "Nueva tarea" },
    dueSoon: { title: "Tarea próxima a vencer", untitled: "Tarea" },
  },
  it: {
    assigned: { title: "Ti è stata assegnata un'attività", untitled: "Nuova attività" },
    dueSoon: { title: "Attività in scadenza", untitled: "Attività" },
  },
  ru: {
    assigned: { title: "Вам назначена задача", untitled: "Новая задача" },
    dueSoon: { title: "Срок задачи скоро истекает", untitled: "Задача" },
  },
};

const SUPPORTED_LOCALES = new Set<SupportedLocale>(["en", "sk", "cs", "fr", "de", "es", "it", "ru"]);

function resolveLocale(locale: unknown): SupportedLocale {
  return typeof locale === "string" && SUPPORTED_LOCALES.has(locale as SupportedLocale)
    ? (locale as SupportedLocale)
    : "en";
}

/** Sends a push to every one of a user's registered devices, localized to
 *  their `users/{uid}`.locale (see UserRepository.upsertProfile) with a
 *  fallback to English for profiles that predate that field or use an
 *  unsupported language. `taskTitle` is user-authored content and is
 *  never translated - only the notification's own title, and the body's
 *  fallback for an untitled task, are. */
async function sendToUser(uid: string, kind: "assigned" | "dueSoon", taskTitle?: string) {
  const userSnap = await db().collection("users").doc(uid).get();
  const tokens: string[] = userSnap.get("fcmTokens") ?? [];
  if (tokens.length === 0) return;

  const strings = NOTIFICATION_STRINGS[resolveLocale(userSnap.get("locale"))][kind];
  const title = strings.title;
  const body = taskTitle ?? strings.untitled;

  const response = await getMessaging().sendEachForMulticast({
    tokens,
    notification: { title, body },
    android: { priority: "high" },
  });

  const staleTokens = response.responses
    .map((r: SendResponse, i: number) => (r.success ? null : tokens[i]))
    .filter((t): t is string => t !== null);
  if (staleTokens.length > 0) {
    await db()
      .collection("users")
      .doc(uid)
      .update({ fcmTokens: FieldValue.arrayRemove(...staleTokens) });
  }
}

/** Notifies a task's assignee whenever they're newly assigned (or
 *  reassigned) to a task, so they find out even if the app isn't open. */
export const onTaskWrite = onDocumentWritten(
  { document: "lists/{listId}/tasks/{taskId}", database: FIRESTORE_DATABASE_ID },
  async (event) => {
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
  },
);

/** Sweeps every list's tasks for ones due within the next 15 minutes that
 *  haven't been reminded about yet. A per-list on-device WorkManager
 *  reminder (see ReminderScheduler.kt) also fires locally as a fallback
 *  for the currently signed-in device; this is what reaches every other
 *  device / the assignee when they aren't the one who set the reminder. */
export const dueDateReminders = onSchedule("every 15 minutes", async () => {
  const windowEnd = Timestamp.fromMillis(Date.now() + 15 * 60 * 1000);

  const dueTasks = await db()
    .collectionGroup("tasks")
    .where("notify", "==", true)
    .where("reminderSent", "==", false)
    .where("dueAt", "<=", windowEnd)
    .get();

  if (dueTasks.empty) return;

  const batch = db().batch();
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
