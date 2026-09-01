# migrate-to-appwrite

One-time script that migrates My Task List production data from Firestore
(named database `mytasks`) to Appwrite Cloud, as part of the
Firebase -> Appwrite backend migration. Read side uses `firebase-admin`;
write side uses `node-appwrite`.

## What it does

1. Pre-creates an Appwrite Auth user for every `users/{uid}` Firestore doc,
   with the Appwrite user's `$id` set to the original Firebase `uid`. This
   is what lets every later `ownerId` / `memberIds` / `assigneeId` /
   `createdBy` reference keep working unchanged.
2. Migrates `users/{uid}` -> the `users` collection (same doc ID).
3. Migrates `lists/{listId}` -> the `lists` collection (same doc ID),
   computing per-document permissions and JSON-encoding `members`.
4. Migrates every `lists/{listId}/tasks/{taskId}` -> the flat `tasks`
   collection (same doc ID), with permissions derived from that task's
   parent list's owner/members.

See the top-of-file comments in `src/migrate.ts`, `src/appwriteTarget.ts`,
and `src/types.ts` for the exact field-by-field mapping and permission
rules.

## IMPORTANT - unverified assumption, confirm before production cutover

Step 1 marks each pre-created Appwrite user's email as verified
(`users.updateEmailVerification(uid, true)`), on the assumption that this
is what makes Appwrite's Google OAuth2 flow **link** to the same
pre-created `$id` by matching email, rather than creating a brand-new,
separate Appwrite user the first time a real user actually signs back
in through the app's new Appwrite OAuth flow.

**This is standard, documented Appwrite behavior, but it has not been
exercised against a live Appwrite project as part of writing this
script** - no Appwrite test project was available in this environment.
Before relying on it for the real cutover:

- Create a throwaway Appwrite test project.
- Run this script's user pre-creation step (or just `users.create` +
  `users.updateEmailVerification`) for one real Google account's uid/email.
- Sign in through the app's actual Appwrite Google OAuth2 flow with that
  same Google account and confirm the resulting session's user `$id`
  matches the pre-created one, rather than a new random `$id` appearing.

If it turns out Appwrite creates a fresh, separate account instead of
linking, this script's "preserve `$id` = Firebase uid" strategy does not
hold for real users post-cutover, and a follow-up reconciliation pass
(merging the duplicate accounts, or re-pointing document ownership) would
be needed. That reconciliation pass is out of scope for this script - it
only flags the risk, see `src/migrate.ts` for the in-code warning at the
exact call site.

## Setup

```bash
cd scripts/migrate-to-appwrite
npm install
```

Copy `.env.example` to `.env` for reference (it documents variable names
only, no real values) and set the real values in your actual shell/CI
environment - this script reads directly from `process.env` and does not
load `.env` itself:

| Variable | Required | Notes |
|---|---|---|
| `GOOGLE_APPLICATION_CREDENTIALS` | yes | Path to a Firebase service-account JSON key file. Same convention as `.github/workflows/deploy-firebase.yml`'s `FIREBASE_SERVICE_ACCOUNT_JSON` secret (decode it to a file first). |
| `FIRESTORE_DATABASE_ID` | no | Defaults to `mytasks`. |
| `APPWRITE_ENDPOINT` | no | Defaults to `https://cloud.appwrite.io/v1`. |
| `APPWRITE_PROJECT_ID` | yes (unless `--dry-run`) | Target Appwrite project. |
| `APPWRITE_API_KEY` | yes (unless `--dry-run`) | Needs Databases (read/write) and Users (read/write) scopes. |
| `APPWRITE_DATABASE_ID` | no | Defaults to `mytasklist`. |
| `APPWRITE_COLLECTION_USERS_ID` | no | Defaults to `users`. |
| `APPWRITE_COLLECTION_LISTS_ID` | no | Defaults to `lists`. |
| `APPWRITE_COLLECTION_TASKS_ID` | no | Defaults to `tasks`. |

## Running

Always do a dry run against real production Firestore data first - it is
read-only and never touches Appwrite:

```bash
npm run build
node dist/migrate.js --dry-run
# or, equivalently:
npm run migrate:dry-run
```

The dry run prints how many users/lists/tasks would be migrated, a
sample of computed permission arrays, and flags any anomalies (e.g. a
task whose `listId` doesn't match any list, a user doc missing `email`).
Review that output before doing a real run.

Once satisfied:

```bash
node dist/migrate.js
# or:
npm run migrate
```

### Idempotency

Every write uses the original Firestore/Firebase ID as the Appwrite
document/user ID (`ID.custom(...)`), so the script is safe to re-run.
A 409 ("already exists") from Appwrite is treated as "already migrated,
skip" rather than a fatal error - if a run dies partway through (rate
limit, network blip), just re-run it; already-migrated records are
skipped and the run picks up wherever it left off. No separate
checkpoint file is needed or created.

### Logging

Progress and a final per-collection summary are printed, e.g.:

```
users: 42/50 migrated, 3 skipped (already exist), 0 failed
```

Any individual record that fails for a reason other than "already
exists" is logged with its collection, ID, and the underlying error, and
processing continues with the rest. All failed record IDs are collected
and printed again at the end of the run so they can be investigated and
retried (e.g. by re-running the whole script, since it's idempotent).

## Verifying it compiles

```bash
npm install
npx tsc --noEmit
```

No live Firestore or Appwrite project is required to typecheck the
script - only to actually run it.
