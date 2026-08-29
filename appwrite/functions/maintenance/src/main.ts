import { deleteAccount } from "./deleteAccount";
import { syncListPermissions } from "./syncListPermissions";
import { googleSignIn } from "./googleSignIn";
import type { FunctionContext } from "./context";

/** Single Appwrite Function serving two trigger types (three logical
 *  operations), to fit within the Appwrite Cloud free-tier limit of 2
 *  functions per project (see README "Backend setup") - previously two
 *  separate functions (delete-account, sync-list-permissions), which also
 *  happened to share identical listAll.ts, now merged into one with no
 *  duplicated code.
 *
 *  Appwrite tags every invocation's request with `x-appwrite-trigger`,
 *  one of "http" | "schedule" | "event" - [VERIFY]: this is documented,
 *  long-standing Appwrite Functions runtime behavior, but hasn't been
 *  exercised against a live invocation of this merged function yet.
 *
 *  The "http" trigger itself now serves two distinct operations,
 *  distinguished by the request path the client sets via
 *  functions.createExecution({ path }) - see AuthRepository.kt
 *  (googleSignIn) and the original deleteAccount call site (no path,
 *  defaults to "/").
 *
 *  appwrite.json sets this function's execute permission to "any" rather
 *  than "users": googleSignIn is the one path that must be reachable by a
 *  caller with no Appwrite session yet (that's the whole point of the
 *  bridge - see googleSignIn.ts). deleteAccount stays safe under "any"
 *  because it independently checks the x-appwrite-user-id header Appwrite
 *  only injects for an authenticated caller, rejecting anyone without one. */
export default async (context: FunctionContext) => {
  const trigger = context.req.headers["x-appwrite-trigger"];
  switch (trigger) {
    case "http":
      return context.req.path === "/google-sign-in" ? googleSignIn(context) : deleteAccount(context);
    case "event":
      return syncListPermissions(context);
    default:
      context.error(`maintenance: unexpected trigger "${trigger}"`);
      return context.res.json({ success: false, message: `Unexpected trigger "${trigger}"` }, 400);
  }
};
