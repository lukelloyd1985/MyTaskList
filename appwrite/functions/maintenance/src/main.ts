import { deleteAccount } from "./deleteAccount";
import { syncListPermissions } from "./syncListPermissions";
import type { FunctionContext } from "./context";

/** Single Appwrite Function serving two triggers, to fit within the
 *  Appwrite Cloud free-tier limit of 2 functions per project (see README
 *  "Backend setup" step 6) - previously two separate functions
 *  (delete-account, sync-list-permissions), which also happened to share
 *  identical listAll.ts, now merged into one with no duplicated code.
 *
 *  Appwrite tags every invocation's request with `x-appwrite-trigger`,
 *  one of "http" | "schedule" | "event" - [VERIFY]: this is documented,
 *  long-standing Appwrite Functions runtime behavior, but hasn't been
 *  exercised against a live invocation of this merged function yet. */
export default async (context: FunctionContext) => {
  const trigger = context.req.headers["x-appwrite-trigger"];
  switch (trigger) {
    case "http":
      return deleteAccount(context);
    case "event":
      return syncListPermissions(context);
    default:
      context.error(`maintenance: unexpected trigger "${trigger}"`);
      return context.res.json({ success: false, message: `Unexpected trigger "${trigger}"` }, 400);
  }
};
