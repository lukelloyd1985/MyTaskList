import { onTaskWrite } from "./onTaskWrite";
import { dueDateReminders } from "./dueDateReminders";
import type { FunctionContext } from "./context";

/** Single Appwrite Function serving two triggers, to fit within the
 *  Appwrite Cloud free-tier limit of 2 functions per project (see README
 *  "Backend setup" step 6) - previously two separate functions
 *  (on-task-write, due-date-reminders), which also happened to share
 *  identical sendToUser.ts/notificationStrings.ts, now merged into one
 *  with no duplicated code.
 *
 *  Appwrite tags every invocation's request with `x-appwrite-trigger`,
 *  one of "http" | "schedule" | "event" - [VERIFY]: this is documented,
 *  long-standing Appwrite Functions runtime behavior, but hasn't been
 *  exercised against a live invocation of this merged function yet. */
export default async (context: FunctionContext) => {
  const trigger = context.req.headers["x-appwrite-trigger"];
  switch (trigger) {
    case "event":
      return onTaskWrite(context);
    case "schedule":
      return dueDateReminders(context);
    default:
      context.error(`notifications: unexpected trigger "${trigger}"`);
      return context.res.json({ success: false, message: `Unexpected trigger "${trigger}"` }, 400);
  }
};
