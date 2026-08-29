# MyTasks

A modern Android app (Kotlin + Jetpack Compose + Material 3) for managing
shared task lists - Short term, Long term, Garden, House, or anything else
you organize your life into.

- **Multiple lists**, each with its own **visibility**: `Private` (only
  you) or `Shared` (invite people by email).
- **Tasks** can be **assigned** to a list member, given a **due date**, and
  optionally trigger a **reminder notification**. Open tasks can be
  **reordered** by long-pressing and dragging the handle on a task row.
- **Sign in** with Google.
- **Localized**: the UI is available in English, Slovak, Czech, French,
  German, Spanish, Italian, and Russian, following the device's language
  automatically - see [Localization](#localization) below for how to add
  more.
- **CI/CD**: a GitHub Actions workflow builds an APK on every manual run
  (for testing) and attaches a release APK to every published GitHub
  Release.

`app/google-services.json` is still committed and configures Firebase
Cloud Messaging only (push notification *delivery* - see
[Architecture](#architecture) below; sign-in and data no longer touch
Firebase at all). To build and run against real sign-in and data, you'll
also need your own Appwrite Cloud project - see
[Backend setup](#backend-setup) below for that.

## Architecture

- **UI**: Jetpack Compose, Material 3, single-Activity + Navigation Compose.
- **DI**: Hilt.
- **Data**: Appwrite Databases - one database (`mytasks`) with three
  collections, schema below. Appwrite has no subcollections, so
  `lists/{listId}/tasks/{taskId}` becomes a flat `tasks` collection scoped
  by a `listId` field instead of a Firestore-style nested path. There is
  no offline persistence: Appwrite's SDK has no equivalent to Firestore's
  local cache/sync, so the app now requires connectivity for every read
  and write - see [Notes & tradeoffs](#notes--tradeoffs), this is the
  single most user-visible change from the old backend.
- **Auth**: Appwrite Account, Google sign-in only - via Appwrite's OAuth2
  browser-redirect session flow (`account.createOAuth2Session` opens a
  Custom Tab against Appwrite's own hosted Google OAuth endpoint and
  redirects back through an `appwrite-callback-<PROJECT_ID>://` deep
  link), replacing the old Credential-Manager/Google-Identity native ID
  token flow - see `AuthRepository.kt` and `LoginScreen.kt`.
- **Notifications**: still delivered over Firebase Cloud Messaging as the
  transport, but sent via **Appwrite Messaging** rather than a direct call
  to FCM's API. The Android app registers each device as an Appwrite
  Messaging push **Target** (`AuthRepository.registerPushTarget`, backed
  by `account.createPushTarget`/`updatePushTarget`), and the
  `notifications` Appwrite Function just calls `messaging.createPush({
  users: [uid], ... })` on task assignment and on a 15-minute due-date
  sweep - Appwrite's own FCM **Provider** (configured once in Console,
  see [Backend setup](#backend-setup) step 6) handles dispatch, retries,
  and pruning dead tokens, none of which this app's code does anymore.
  `ReminderScheduler.kt` also schedules a local WorkManager reminder
  on-device as a fallback.
- **Backend**: Appwrite Functions (TypeScript, `node-appwrite`) in
  `/appwrite/functions` handle push notifications, list/task permission
  sync, and account deletion.

### Appwrite schema

One Appwrite database, `mytasks`, with three collections:

```
users/{uid}    displayName, email, photoUrl, locale
               (document ID = the Appwrite Auth user ID; push-device
               registration lives in Appwrite Messaging's own Targets,
               not a field here)
lists/{listId} name, icon, colorHex, visibility (PRIVATE|SHARED), ownerId,
               ownerName, memberIds[], members (JSON-encoded string -
               Appwrite has no array-of-objects attribute type)
tasks/{taskId} listId, title, description, assigneeId, assigneeName,
               priority (LOW|MEDIUM|HIGH), dueAt, notify, completed,
               order, createdBy, createdByName, reminderSent
               (flat collection - listId is the sole scoping field, there
               is no lists/{listId}/tasks subcollection)
```

None of the three carry a `createdAt`/`updatedAt`/`lastSignedInAt` field -
Appwrite's built-in `$createdAt`/`$updatedAt` system fields on every
document supersede them.

Unlike Firestore's declarative security rules, Appwrite authorizes access
with a **permission array stored on every document** - a static ACL, not
a rule engine that can evaluate against a *different* (e.g. parent)
document at read/write time. `users/{uid}` is readable by any signed-in
user and writable only by the user themself (delete is in practice only
ever done server-side by the `maintenance` Function's account-deletion
handler), mirroring the old rules. `lists/{listId}` carries read for the
owner plus every `memberIds` entry, and update/delete for the owner only,
recomputed on every membership-changing write. `tasks/{taskId}` is meant
to carry the *same* owner+members permissions as its parent list (so any
list member can fully CRUD its tasks) - but Appwrite has no way to express
"authorize like some other document." That gap is why the `maintenance`
Function's other handler, `syncListPermissions`, exists: it fires on
every list-membership change and rewrites permissions on every task under
that list to match. Without it, a list's membership and its tasks' actual
accessibility would silently drift apart over time.

## Localization

Every user-visible string in the app is a resource in
[`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml)
(English, the default) - there are no hardcoded strings left in the Kotlin
source. Translations live alongside it, one `values-<language code>/`
directory per language: [`sk`](app/src/main/res/values-sk/strings.xml)
(Slovak), [`cs`](app/src/main/res/values-cs/strings.xml) (Czech),
[`fr`](app/src/main/res/values-fr/strings.xml) (French),
[`de`](app/src/main/res/values-de/strings.xml) (German),
[`es`](app/src/main/res/values-es/strings.xml) (Spanish),
[`it`](app/src/main/res/values-it/strings.xml) (Italian), and
[`ru`](app/src/main/res/values-ru/strings.xml) (Russian). Android picks
whichever matches the device's language automatically, falling back to
English. The app name itself (`app_name` = "MyTasks") is intentionally
left untranslated in every locale, as a brand name.

`AndroidManifest.xml` also declares
[`res/xml/locales_config.xml`](app/src/main/res/xml/locales_config.xml)
(the languages above), so on Android 13+ people can also override the
app's language independently of the device's, from Settings → Apps →
MyTasks → Language.

To add another language:

1. Copy `values/strings.xml` to a new `values-<language code>/strings.xml`
   (e.g. `values-pl` for Polish) and translate every string, keeping the
   same `name` attributes and any `%1$s`/`%1$d` format placeholders in
   the same order. Leave `app_name` and `notification_channel_tasks_id`
   out - they're intentionally not overridden (see any existing
   `values-*` file for the pattern). `list_members_count` is a
   `<plurals>` resource (grammatical number - "1 member" vs "2 members" -
   varies by language, and Russian's `values-ru` needs four categories
   where most languages need two); provide whichever `quantity`
   categories the language's CLDR plural rules need
   ([cldr.unicode.org/index/cldr-spec/plural-rules](https://cldr.unicode.org/index/cldr-spec/plural-rules)),
   `other` at minimum.
2. Add a `<locale android:name="..."/>` entry for it to
   `res/xml/locales_config.xml`.
3. Add a matching Play Store listing under `app/src/main/play/listings/`
   (see [Publishing to Google Play](#publishing-to-google-play) below) so
   the app's store page is translated too, not just the app itself - use
   the locale code Play Console expects (check the language dropdown when
   adding a translation there; it's not always identical to the Android
   resource qualifier - e.g. Android's `values-cs` pairs with Play's
   `cs-CZ`, not `cs`).
4. Add an entry for the language to `NOTIFICATION_STRINGS` in
   `appwrite/functions/notifications/src/notificationStrings.ts`
   and redeploy the Appwrite Functions, so push notifications are
   localized too (see below).

Push notifications (task-assignment and due-date alerts sent by the
`notifications` Appwrite Function in `/appwrite/functions`) are localized
too: `UserRepository` writes `Locale.getDefault().language` to
`users/{uid}.locale` on every sign-in (this reflects any per-app
language override automatically), and
`appwrite/functions/notifications/src/notificationStrings.ts`
picks the matching translation from its own small `NOTIFICATION_STRINGS`
table when sending, falling back to English for an unset or unsupported
locale. A task's own title/description are user-authored content and are
never translated - only the notification's title and its fallback body
text for an untitled task are. Adding a language there means adding an
entry to that table to match the new `values-<language code>/strings.xml`.

## Backend setup

1. **Create an Appwrite Cloud project** in the
   [Appwrite Console](https://cloud.appwrite.io). Note its API endpoint
   (e.g. `https://fra.cloud.appwrite.io/v1`, needed in step 9) and its
   project ID (needed in step 2, right below).
2. **Set the project ID in `appwrite/appwrite.json`**, replacing its
   `"projectId"` placeholder with the real one from step 1.
   `appwrite/appwrite.json` is the Appwrite CLI's config for the database/
   tables/functions below - the project ID isn't sensitive (same category
   as Firebase's committed `google-services.json` project ID - it only
   identifies the project, and ships inside the built APK regardless), so
   it's the one place this repo needs it set, rather than a GitHub secret
   duplicated with a separate Android build-time env var. Both
   [`deploy-appwrite.yml`](.github/workflows/deploy-appwrite.yml) and
   `app/build.gradle.kts` read it from here directly.
3. **No manual Console work needed for the database or tables** -
   [`deploy-appwrite.yml`](.github/workflows/deploy-appwrite.yml) (see
   step 8 for its one-time CI setup) creates them for you when it runs.
   This wasn't always true: `appwrite push tables all --force` has, on
   **four** separate real runs, planned to delete the `mytasks` database
   outright before creating anything - the first three against an empty
   database (ruling out two real, separately-fixed schema bugs along the
   way: each table's `documentSecurity` should have been `rowSecurity`,
   and the `visibility`/`priority` columns should have been `"type":
   "enum"` rather than `"type": "string"` with `"format": "enum"`, both
   leftovers from the old Collections/Attributes schema), and the fourth
   against a database that already held all 3 correctly-created tables -
   which rules out "empty database" as the cause too. This looks like
   `appwrite push tables` fundamentally not recognizing this project's
   local config as matching its deployed state, not a fixable config
   mistake - see the full trail in `deploy-appwrite.yml`'s top-of-file
   comment. **`appwrite push tables` is not used anywhere in this repo
   as a result** - only `bootstrap-tables.mjs` ever touches the database
   or tables.

   [`appwrite/bootstrap-tables.mjs`](appwrite/bootstrap-tables.mjs)
   creates the database and tables directly via the `node-appwrite`
   server SDK instead, bypassing `appwrite push` entirely.
4. **Schema changes to `appwrite/appwrite.json` take effect on the next
   run of `bootstrap-tables.mjs`** - re-run it (via
   [`deploy-appwrite.yml`](#deploying-appwrite-functions) or locally, see
   step 3) any time you edit a table's columns/indexes. It never deletes
   anything: for a table that already exists, it lists the table's
   current columns/indexes and adds only what's missing from
   `appwrite.json` - existing rows, columns, and indexes are always left
   alone. If a column that already exists has drifted from its local
   declaration (e.g. `required` or `type` changed), the script logs a
   warning and leaves it as-is rather than trying to alter it in place -
   reconcile that by hand in Console, since some changes (like narrowing
   a string's size, or changing a column's type) aren't safely automatable
   without risking the existing data anyway.
5. **Enable the Google OAuth2 provider**: Console → Auth → Settings →
   **Google**, toggle it on. Appwrite auto-provisions a Web OAuth client
   for this and shows you the redirect URI to register in
   [Google Cloud Console](https://console.cloud.google.com) (APIs &
   Services → Credentials → your OAuth 2.0 Client ID → Authorized
   redirect URIs) - typically
   `https://<APPWRITE_ENDPOINT>/v1/account/sessions/oauth2/callback/google/<PROJECT_ID>`.
   This is simpler than the old Firebase setup: because sign-in now goes
   through Appwrite's own hosted OAuth endpoint rather than a native
   Credential-Manager flow on the device, Google never needs the app's
   own signing-certificate SHA-1/SHA-256 fingerprints registered at all
   (see [Notes & tradeoffs](#notes--tradeoffs)). The debug/release
   keystores themselves are still required - just for Play/APK signing,
   not for this.
6. **Configure the FCM Provider for Appwrite Messaging**: Console →
   Messaging → **Providers** → Add provider → **FCM** (under Push). Give
   it a name (e.g. `fcm`) and provide the same two values push
   notifications have always needed - a Firebase service account's JSON
   key (Firebase Console → Project settings → Service accounts →
   **Generate new private key** - needs the "Firebase Cloud Messaging
   API" role) and that service account's Firebase project ID (Firebase
   Console → Project settings → General → **Project ID** - not the
   Appwrite project ID from step 1). This is a one-time Console step, not
   part of `appwrite.json`: the credential lives with the Provider, not
   as a Function secret - see [Architecture](#architecture)'s
   Notifications bullet for why (Appwrite Messaging handles FCM dispatch
   directly; the `notifications` Function only decides what to send and
   to whom).
7. **Deploy the two Appwrite Functions** under
   [`appwrite/functions/`](appwrite/functions/): `notifications` (sends a
   push both when a task's assignee changes - database event trigger -
   and on the CRON due-date reminder sweep every 15 minutes) and
   `maintenance` (HTTP-invoked account deletion, cascading through the
   caller's lists/tasks; and a database event trigger that keeps task
   permissions in sync with their parent list's membership - see
   [Appwrite schema](#appwrite-schema)). Each is one Appwrite Function
   serving two triggers - dispatched internally by the `x-appwrite-trigger`
   request header (see either function's `src/main.ts`) - specifically to
   fit inside Appwrite Cloud's free-tier limit of **2 Functions per
   project**: this repo now declares exactly 2, so no plan upgrade or
   consolidation should be needed for a standard setup. If you ever add a
   third Function, you'll hit "The maximum number of functions allowed
   for the selected plan has reached" and need to either merge it into
   one of the existing two the same way, or upgrade the plan.

   Push them via [`deploy-appwrite.yml`](#deploying-appwrite-functions)
   (`appwrite push function all --force` - unlike tables, this hasn't
   shown any destructive behavior), or create/deploy each one by hand in
   the Console. Neither needs any environment variables set - the FCM
   credential lives with the Provider from step 6, not a Function
   secret.
8. **Create a server API key** for CI: Console → Overview →
   Integrations → **API Keys** → Create API key, scoped to
   **`databases.read`** and **`databases.write`**, **`tables.read`** and
   **`tables.write`**, **`columns.read`** and **`columns.write`** (the
   Console's own scope list has already dropped the legacy
   `collections`/`attributes` scopes in favor of `tables`/`columns` -
   don't grant the deprecated ones), **`functions.read`** and
   **`functions.write`**, **`rules.read`** (needed by `appwrite push
   function` - it's listed under the **Proxy** category in the scope
   picker, not Functions, which is easy to miss), and **`users.write`**
   (needed by `maintenance`'s cascading Auth-account deletion). This
   becomes the `APPWRITE_API_KEY` secret used by CI - see
   [Deploying Appwrite Functions](#deploying-appwrite-functions) below.
9. **Set the build-time env vars** the Android app reads (see
   `app/build.gradle.kts`). The project ID doesn't need one - it's read
   straight from `appwrite/appwrite.json` (step 2) - and the database/
   table/function IDs below already default to this repo's own fixed
   values, so only the endpoint needs setting explicitly. In CI,
   `android-build.yml` already reuses the same `APPWRITE_ENDPOINT` secret
   [Deploying Appwrite Functions](#deploying-appwrite-functions) has you
   create - no separate CI secret needed. For a local build, set
   it as a shell env var yourself before running Gradle:

   | Env var | Value |
   | --- | --- |
   | `MYTASKS_APPWRITE_ENDPOINT` | Appwrite endpoint from step 1 |

   The rest (`MYTASKS_APPWRITE_DATABASE_ID`,
   `MYTASKS_APPWRITE_COLLECTION_USERS_ID`/`_LISTS_ID`/`_TASKS_ID`,
   `MYTASKS_APPWRITE_FUNCTION_MAINTENANCE_ID`) are override knobs for
   a contributor customizing those IDs away from this repo's defaults
   (`mytasks`/`users`/`lists`/`tasks`/`maintenance`) - not something
   you need to set for a standard setup.

## Deploying Appwrite Functions

[`.github/workflows/deploy-appwrite.yml`](.github/workflows/deploy-appwrite.yml)
is a manually-triggered ("Run workflow" in the **Actions** tab - works
from the GitHub mobile site or app, no local Appwrite CLI or login
needed) job that pushes both Appwrite Functions from
[`appwrite/appwrite.json`](appwrite/appwrite.json) using a server API key
instead of an interactive login. It never runs on its own - a Functions
deploy going out on every push felt like too much blast radius for
something this easy to trigger on demand instead.

It does **not** push the database/tables - `appwrite push tables` has
repeatedly planned to delete the `mytasks` database outright (see
[Backend setup](#backend-setup) step 3 and `deploy-appwrite.yml`'s
top-of-file comment for the full trail), so it's dropped from this
workflow entirely. `bootstrap-tables.mjs` (also run by this workflow) is
the only thing that creates or updates the database and tables, and it
does so non-destructively - see step 4.

One-time setup - two repository secrets (Settings → Secrets and
variables → Actions), both from the Appwrite Console (the project ID
isn't among them - it's read from `appwrite/appwrite.json`, see
[Backend setup](#backend-setup) step 2):

| Secret | Value |
| --- | --- |
| `APPWRITE_ENDPOINT` | Your project's API endpoint, e.g. `https://fra.cloud.appwrite.io/v1` |
| `APPWRITE_API_KEY` | The server API key from [Backend setup](#backend-setup) step 8 |

That's the whole setup - a single scoped API key, considerably simpler
than the old Firebase deploy's Google Cloud service account juggling five
separate IAM roles (Firebase Admin, Cloud Build Editor, Service Account
User, Cloud Scheduler Admin, Artifact Registry Admin) plus its
Eventarc/Artifact-Registry first-deploy gotchas - none of that GCP-
specific machinery has an Appwrite equivalent to configure.

From then on: **Actions tab → Deploy Appwrite (Functions) → Run
workflow**.

## Building the APK

### Locally

```
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Release builds fall back to signing with the debug keystore when no
release-signing secrets are configured, so `assembleRelease` always
produces an installable APK even before you've set up a keystore.

### GitHub Actions

[`.github/workflows/android-build.yml`](.github/workflows/android-build.yml):

- **Run it manually** any time from the repo's **Actions** tab → *Android
  Build* → **Run workflow**. It builds a debug APK and attaches it to the
  workflow run as a downloadable artifact - handy for giving testers a
  build without cutting a release.
- **Publishing a GitHub Release** automatically builds a release APK *and*
  an Android App Bundle (`.aab`, what Google Play requires), attaches both
  to that release (and to the workflow run as artifacts), and - if the
  `PLAY_SERVICE_ACCOUNT_JSON` secret is set - uploads the AAB to Google
  Play's internal testing track. See
  [Publishing to Google Play](#publishing-to-google-play) below. None of
  the release-signing/AAB/Play-publish steps run for manual
  `workflow_dispatch` runs, only for this trigger.

To get a properly **signed** release build (instead of the debug-keystore
fallback), generate a keystore and add these repository secrets
(Settings → Secrets and variables → Actions):

```
keytool -genkey -v -keystore release.keystore -alias mytasks \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -i release.keystore | pbcopy   # or base64 -w0 on Linux
```

| Secret | Value |
| --- | --- |
| `MYTASKS_KEYSTORE_BASE64` | base64-encoded keystore file |
| `MYTASKS_KEYSTORE_PASSWORD` | keystore password |
| `MYTASKS_KEY_ALIAS` | key alias (e.g. `mytasks`) |
| `MYTASKS_KEY_PASSWORD` | key password |

**A stable debug keystore for CI builds is now optional.** It used to be
required for Google Sign-In, because Firebase verified the calling app's
signing certificate as part of its account-reauth check - since Auth
moved to Appwrite's OAuth2 browser-redirect flow (see
[Architecture](#architecture)), that check no longer exists, and sign-in
works from a CI-built debug APK regardless of its signing certificate.
It's still worth setting up if you want repeat CI debug builds to install
*over* each other on a test device rather than requiring an uninstall
first (Android refuses to install an update signed with a different
certificate than what's already on the device) - without it,
`assembleDebug` falls back to AGP's built-in debug signing, which
auto-generates a brand-new random keystore on every run, since CI runners
are a fresh VM each time. Generate one the same way and add it as its own
set of secrets:

```
keytool -genkeypair -v -keystore debug.keystore -alias mytasksdebug \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -i debug.keystore | pbcopy   # or base64 -w0 on Linux
```

| Secret | Value |
| --- | --- |
| `MYTASKS_DEBUG_KEYSTORE_BASE64` | base64-encoded debug keystore file |
| `MYTASKS_DEBUG_KEYSTORE_PASSWORD` | debug keystore password |
| `MYTASKS_DEBUG_KEY_ALIAS` | debug key alias (e.g. `mytasksdebug`) |
| `MYTASKS_DEBUG_KEY_PASSWORD` | debug key password |

No further registration step is needed - unlike the old Firebase setup,
there's no per-certificate step to complete afterwards.

## Publishing to Google Play

Getting onto Google Play has two kinds of steps: a handful that **only you
can do**, by hand, in Play Console (there's no API for them - creating an
app and its first release requires a human in the Play Console UI), and
everything after that, which CI automates.

### 1. One-time setup you do by hand

1. **Host the privacy policy and account deletion page.** Play requires a
   live URL for each. [`docs/privacy.html`](docs/privacy.html) and
   [`docs/delete-account.html`](docs/delete-account.html) are starting
   drafts - replace every `[bracketed placeholder]` in both (especially
   the support email), then enable Pages at Settings → Pages → *Build and
   deployment* → *Source* → **GitHub Actions** (not "Deploy from a
   branch" - that built-in source rebuilds on every push to `main`
   regardless of what changed, with no way to scope it to `/docs`;
   [`.github/workflows/pages.yml`](.github/workflows/pages.yml) is a
   custom deployment that only runs when `docs/` actually changes). Both
   pages are served from the same deployment, at
   `https://<your-github-username>.github.io/MyTasks/privacy.html` and
   `.../delete-account.html`.
2. **Create the app** in [Play Console](https://play.google.com/console):
   *Create app* → name it, set default language, "App" (not game), and
   Free. The package name is fixed at creation to whatever you tell it -
   use `com.github.lukelloyd1985.mytasks` to match `applicationId` in
   `app/build.gradle.kts`. Whatever default language you pick here must
   match the locale directory under `app/src/main/play/listings/` (and
   `default-language.txt`) - the Play Developer API rejects listing/image
   uploads for a language the app doesn't have a store listing for yet,
   with a 404 "Listing for language '...' not found", so a mismatch (e.g.
   picking English (UK) here but leaving the repo on `en-US`) breaks the
   `publishListing` CI step. This repo currently uses `en-GB`.
3. **Complete "App content"** (Play Console won't allow any release
   without these): Privacy policy URL (from step 1), Ads (No ads, unless
   you've added some), Content ratings questionnaire, Target audience,
   **App access** - the whole app sits behind Google Sign-In (there's no
   guest/anonymous mode: `MainActivity` shows `LoginScreen` until
   `AuthViewModel.currentUser` is non-null), so this must be set to "All
   or some functionality is restricted," not "available without special
   access." Add one login instruction: leave *Username* and *Password*
   blank (there aren't any - it's Sign in with Google, not a password
   form) and put something like the following in *Instructions*: "This
   app only supports Sign in with Google - there's no username/password.
   Tap 'Continue with Google' and sign in with any Google Account to
   access all functionality." Reviewers use their own Google account for
   this, the same way a real user would.
   **Data safety** section - this should mirror `docs/privacy.html`:
   personal info collected (name, email, photo - via Google Sign-In),
   task/list content, shared with third parties = **No**, encrypted in
   transit = **Yes**, users can request data deletion = **Yes**, with the
   account deletion URL set to `docs/delete-account.html`'s published
   address from step 1 (see [Notes & tradeoffs](#notes--tradeoffs) for
   what that page and the in-app "Delete my account" action actually do).
4. **Fill in the Store listing**: short/full description, app icon
   (512×512 PNG), feature graphic (1024×500 PNG), and at least 2 phone
   screenshots. Ready-to-upload versions of all three live in
   [`docs/store-assets/`](docs/store-assets/) - upload them by hand this
   first time, or, once you've done the one-time API access setup in
   step 2 below, run `./gradlew publishListing` locally instead: it
   publishes the same content straight from
   `app/src/main/play/listings/en-GB/` (title, descriptions, and the
   `docs/store-assets/` graphics, already copied in there). From then on,
   CI keeps this in sync automatically on every release - see step 4 in
   part 2 below. The repo also ships Slovak (`sk`), Czech (`cs-CZ`),
   French (`fr-FR`), German (`de-DE`), Spanish (`es-ES`), Italian
   (`it-IT`), and Russian (`ru-RU`) store listings under the same
   `listings/` directory - same rule as above applies to all of them:
   Play Console rejects `publishListing` uploads for any language the
   app doesn't already have a listing for (see the note on step 2
   above), so add every one of these under Store presence → Main store
   listing → Manage translations → Add language *before* the first CI
   release, or `publishListing` will 404 on them the same way it did on
   a mismatched default language.
5. **Do the first release by hand.** Build an AAB
   (`./gradlew bundleRelease`, or download one from a GitHub Release once
   you've tagged one - see below), go to Testing → Internal testing →
   *Create new release*, upload it, add release notes, and roll it out.
   Add yourself (and any other testers) as an internal tester so you can
   install it.

### 2. Let CI handle every release after that

1. Play Console's old **Setup → API access** page is gone - Google
   removed it, and the replacement flow is no longer inside Play Console
   at the start. In [Google Cloud Console](https://console.cloud.google.com)
   (any project - it doesn't need to be one already linked to this app),
   go to **APIs & Services → Library**, search for "Google Play Android
   Developer API", and enable it. Then **IAM & Admin → Service Accounts →
   Create Service Account** (any name, e.g. `mytasks-ci-publisher`) - it
   doesn't need any Google Cloud IAM roles for this. Open it → **Keys →
   Add Key → Create new key → JSON** to download the key file whose
   contents become the `PLAY_SERVICE_ACCOUNT_JSON` secret below.
2. Back in Play Console, go to **Users and permissions → Invite new
   user**, and invite the service account by pasting its email address
   (the `client_email` field in the JSON key you just downloaded, looks
   like `mytasks-ci-publisher@<project>.iam.gserviceaccount.com`) exactly
   as if inviting a person. Under **App permissions**, add this app and
   grant it **both** **Release management** permissions (needed for
   `publishReleaseBundle`) **and** **Store listing / "Manage store
   presence"** permissions (needed for `publishListing`, which pushes the
   title, description, and graphics from `app/src/main/play/` - see step
   4 above). These are separate permission groups in Play Console;
   granting only "Release manager" covers releases but not the store
   listing, and `publishListing` will fail with a permissions error
   without the second one.
3. Add the JSON key's full contents as the `PLAY_SERVICE_ACCOUNT_JSON`
   repository secret (Settings → Secrets and variables → Actions) -
   paste the raw JSON, not base64.
4. From then on, **publishing a GitHub Release** builds the AAB and runs
   `publishReleaseBundle`, which uploads it straight to the **internal
   testing** track with `releaseStatus = COMPLETED` (see the `play { }`
   block in `app/build.gradle.kts`). CI never touches the production
   track - promote a release from internal testing to production
   yourself in Play Console once you're happy with it. It also runs
   `publishListing`, which pushes the store listing text and graphics
   committed under `app/src/main/play/` - edit those files (and
   `docs/store-assets/` for the graphics themselves) and the next release
   picks up the changes automatically, no manual re-upload needed.
5. **Version each release with a git tag** (e.g. `v1.0.1`) when you
   create the GitHub Release - it becomes the app's `versionName`.
   `versionCode` auto-increments from `GITHUB_RUN_NUMBER`, so Play's
   "each upload needs a higher version code than the last" requirement is
   handled for you automatically.

## Notes & tradeoffs

- **`appwrite push tables all --force` can delete the `mytasks` database
  outright** - confirmed on four separate real runs, including one
  against a database that already held all 3 correctly-created tables,
  which rules out "only happens when empty". See
  [Backend setup](#backend-setup) step 3 and the `WARNING` in
  `deploy-appwrite.yml`'s top-of-file comment. This is why
  `appwrite/bootstrap-tables.mjs` creates the database and tables
  directly via the API instead, and why `appwrite push tables` isn't run
  anywhere in this repo, including in CI - it's not just deferred until
  "after bootstrap," it's removed.
- **`bootstrap-tables.mjs` only ever adds - it never deletes or alters
  anything.** A table that already exists has its columns/indexes
  diffed against `appwrite/appwrite.json` and only what's missing is
  added; a column that already exists but has drifted from its local
  declaration is left as-is with a warning logged, not altered in place
  (see [Backend setup](#backend-setup) step 4). Reconciling a genuinely
  changed column (e.g. a type change) still needs a manual step in
  Console, since that can't always be done without risking the existing
  data - this script deliberately won't attempt it automatically.
- **`appwrite push function` needs the `rules.read` scope**, which
  Console's API key scope picker lists under the **Proxy** category, not
  Functions - easy to miss (see [Backend setup](#backend-setup) step 8).
- **Only 2 Appwrite Functions exist (`notifications`, `maintenance`),
  each serving two triggers**, specifically to fit inside Appwrite
  Cloud's free-tier limit of 2 Functions per project - see
  [Backend setup](#backend-setup) step 7. This also happened to remove
  duplicated code: `listAll.ts` was byte-identical between the two
  functions now merged into `maintenance`.
- **Notifications go through Appwrite Messaging, not a direct FCM API
  call** - the Android app registers each device as a Messaging push
  Target (`AuthRepository.registerPushTarget`), and the `notifications`
  Function just calls `messaging.createPush`; Appwrite's own FCM
  Provider (see [Backend setup](#backend-setup) step 6) handles dispatch
  and dead-token pruning. This replaced an earlier version that called
  FCM's HTTP v1 API directly with a service-account credential minted
  via `google-auth-library` - dropped that dependency (it was part of
  what caused the TS18028 build failures worked through earlier) along
  with the hand-rolled token-pruning logic. **Devices that registered
  under the old `fcmTokens` array won't automatically get a Messaging
  Target** - `MainActivity`'s `LaunchedEffect(user)` re-registers on
  every app start for anyone already signed in, so this self-heals the
  next time each existing install is opened; nothing needs a forced
  re-login. The now-unused `fcmTokens` column on an already-deployed
  `users` table is harmless dead data - `bootstrap-tables.mjs` never
  drops columns, so it's left in place; safe to remove by hand in
  Console if you want it gone.
- `res/drawable/ic_provider_google.xml` is Google's official "G" identity
  mark (sourced from Google's own FirebaseUI-Android library), matching
  their [Sign in with Google branding guidelines](https://developers.google.com/identity/branding-guidelines).
  Don't recolor or restyle it.
- **Offline persistence is gone**, and is the single most user-visible
  regression from the Firebase migration: Firestore's local cache/sync
  had no Appwrite SDK equivalent to carry over, so the app now requires
  connectivity for every read and write - there is no more "keep working
  on a flaky connection and sync later."
- Any signed-in user can look up any other user's basic profile (name,
  email, photo), which is what powers "invite by email" on a shared
  list. See the `users` collection's permissions in
  [Appwrite schema](#appwrite-schema) if you want to tighten this
  further.
- A related, new gap from the move to Appwrite's static per-document
  permissions: any signed-in user can technically create a `tasks`
  document against an arbitrary (but unguessable) `listId`, since the
  `tasks` collection's create permission can't validate list membership
  the way Firestore's rule (`isListMember(parentList())`) could against
  the parent list document. Mirrors the existing "any signed-in user can
  read any profile" tradeoff above.
- Due-date reminders are best-effort: an on-device WorkManager job covers
  the device that set the reminder, and the `notifications` Appwrite
  Function's CRON-triggered sweep runs every 15 minutes as the
  cross-device fallback.
- `deleteList` and `reorderTasks` no longer run as an atomic batch -
  Appwrite has no transactional multi-document write like Firestore's
  `WriteBatch`. A crash mid-operation can leave a partial state (e.g. some
  tasks reordered, some not), but it self-heals on retry rather than
  silently losing data.
- **Google Sign-In no longer requires registering the app's signing
  certificate** (SHA-1/SHA-256) anywhere - see
  [Backend setup](#backend-setup) step 5. The debug/release keystores
  themselves are still required, just for Play Store/APK signing.
- **Account deletion** satisfies Play's dual in-app + web requirement:
  the Profile screen's "Delete my account" action HTTP-invokes the
  `maintenance` Appwrite Function
  (`appwrite/functions/maintenance/src/deleteAccount.ts`), which transfers or removes
  the user's membership on every list they're part of (a shared list
  they own is handed to another member rather than deleted out from
  under them), unassigns their tasks elsewhere, deletes their
  `users/{uid}` doc, then deletes their Appwrite Auth account.
  `docs/delete-account.html` covers the same thing for someone who no
  longer has the app installed, and gets registered as the "Delete
  account" URL in Play Console's Data safety section (see
  [Publishing to Google Play](#publishing-to-google-play)). This runs
  server-side rather than from the client both because one user must
  never be able to delete another's account, and because the `users`
  collection's permissions (see [Appwrite schema](#appwrite-schema))
  block client deletes of `users/{uid}` outright.
- Kotlin sources compile via AGP 9's built-in Kotlin support (no
  `org.jetbrains.kotlin.android` plugin applied), and Hilt's annotation
  processing runs via KSP rather than the now-incompatible `kapt`. Both
  changes were required together - `kapt` doesn't work under built-in
  Kotlin - see <https://developer.android.com/build/migrate-to-built-in-kotlin>.
