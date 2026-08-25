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

`app/google-services.json` is already configured against a real Firebase
project, so the app builds and runs against real sign-in and data sync out
of the box. If you're standing up your own backend instead - see
[Backend setup](#backend-setup) below for that.

## Architecture

- **UI**: Jetpack Compose, Material 3, single-Activity + Navigation Compose.
- **DI**: Hilt.
- **Data**: Cloud Firestore (offline-persistence enabled), with the schema
  below. `lists/{listId}/tasks/{taskId}` is a subcollection so Firestore
  security rules can authorize per-list.
- **Auth**: Firebase Authentication, Google sign-in only (via Credential
  Manager / Google Identity - see `AuthRepository.kt` and `LoginScreen.kt`).
- **Notifications**: Cloud Functions send FCM pushes when a task is
  assigned and on a 15-minute due-date sweep; `ReminderScheduler.kt` also
  schedules a local WorkManager reminder on-device as a fallback.
- **Backend**: Cloud Functions (TypeScript) in `/functions` handle push
  notifications.

### Firestore schema

```
users/{uid}                  displayName, email, photoUrl, fcmTokens[]
lists/{listId}                name, visibility (PRIVATE|SHARED), ownerId,
                               ownerName, memberIds[], members[]
lists/{listId}/tasks/{taskId} title, description, assigneeId, assigneeName,
                               priority, dueAt, notify, completed, ...
```

Security rules are in [`firestore.rules`](firestore.rules): a private
list is only readable by its owner; a shared list is readable/writable by
its owner and everyone in `memberIds`.

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
   `functions/src/notifications.ts` and redeploy functions, so push
   notifications are localized too (see below).

Push notifications (task-assignment and due-date alerts sent by the
Cloud Functions in `/functions`) are localized too: `UserRepository`
writes `Locale.getDefault().language` to `users/{uid}.locale` on every
sign-in (this reflects any per-app language override automatically),
and `functions/src/notifications.ts` picks the matching translation
from its own small `NOTIFICATION_STRINGS` table when sending, falling
back to English for an unset or unsupported locale. A task's own
title/description are user-authored content and are never translated -
only the notification's title and its fallback body text for an
untitled task are. Adding a language there means adding an entry to
that table to match the new `values-<language code>/strings.xml`.

## Backend setup

1. **Create a Firebase project** at <https://console.firebase.google.com>,
   add an Android app with package name `com.github.lukelloyd1985.mytasks`
   (and, for local debug builds,
   `com.github.lukelloyd1985.mytasks.debug`), and download the real
   `google-services.json` over `app/google-services.json`. It's committed
   directly to the repo - Google designed this file to be safe for public
   repos (the API key in it only identifies the project; it isn't a
   credential on its own) - and CI builds from it as-is, so this is the
   only place it needs to live.

   The package name (`applicationId` in `app/build.gradle.kts`) isn't
   `com.mytasks.app` - that was already taken on Play Store, so the app's
   Play/Firebase identity moved under this repo's GitHub namespace
   instead. `namespace` in the same file (the Kotlin source package, R
   class, etc.) is unaffected and stays `com.mytasks.app` - the two are
   independent in Android, and only `applicationId` needed to change.
   The `google-services.json` committed right now has its `package_name`
   fields relabeled to match the new `applicationId` purely so the
   google-services Gradle plugin doesn't hard-fail the build (it throws
   if none of the file's client entries match `applicationId`) - those
   entries still point at the *old* Firebase Android app registrations,
   so Google Sign-In won't actually work until you complete this step for
   real: add the two Android apps above to the project, then redo step 6
   below (the SHA-1/SHA-256 fingerprints are registered per Android-app
   entry, so the old registrations don't carry over either).
2. **Enable Firestore** (production mode) and deploy the rules/indexes:
   ```
   npm install -g firebase-tools
   firebase login
   firebase use --add          # pick your project
   firebase deploy --only firestore:rules,firestore:indexes
   ```
3. **Enable Cloud Messaging** (enabled by default with the project).
4. **Deploy Cloud Functions**:
   ```
   cd functions
   npm install
   npm run build
   cd ..
   firebase deploy --only functions
   ```
5. **Enable Google sign-in** in Firebase Console → Authentication →
   Sign-in method → Google. Note the auto-created Web client - the
   `com.google.gms.google-services` Gradle plugin generates a
   `default_web_client_id` string resource from it automatically, which is
   what Credential Manager uses (see `LoginScreen.kt`). No further app
   registration is needed beyond the Android app already added in step 1.

   Until this is done, `google-services.json`'s `oauth_client` array stays
   empty and the plugin has nothing to generate that resource from -
   `LoginScreen.kt` looks it up by name at runtime rather than a
   compile-time `R.string` reference specifically so the app still builds
   in that state, but tapping "Continue with Google" will show "Google
   sign-in isn't configured yet" until you complete this step. After
   enabling it, re-download `google-services.json` and replace
   `app/google-services.json`.
6. **Register your signing certificates' SHA-1/SHA-256 fingerprints** in
   Firebase Console → Project settings → Your apps, on both the
   `com.github.lukelloyd1985.mytasks` and
   `com.github.lukelloyd1985.mytasks.debug` entries. Google Sign-In
   verifies the calling app's certificate as part of its silent
   account-reauth check, so a build signed with an unregistered
   certificate fails sign-in with a `GetCredentialException` of type
   `TYPE_USER_CANCELED` and message `[16] Account reauth failed` - it
   looks like a cancel, but it's really an unrecognized signer. Get each
   fingerprint with:
   ```
   keytool -list -v -keystore <path-to-keystore> -alias <alias> -storepass <password>
   ```
   For a **release** build, that's your `MYTASKS_KEYSTORE_BASE64` keystore.
   For a **local debug** build, it's Android Studio's per-machine
   `~/.android/debug.keystore` (alias `androiddebugkey`, password
   `android`). For a **CI-built debug APK**, see the debug keystore secrets
   below - CI runners are a fresh VM every run, so without a keystore
   secret configured there's no stable certificate to register at all.

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

**Debug builds from CI need their own stable keystore too**, or Google
Sign-In won't work on them (see [Backend setup](#backend-setup) step 6).
Without it, `assembleDebug` falls back to AGP's built-in debug signing,
which auto-generates a brand-new random keystore on every run - since CI
runners are a fresh VM each time - so there's never a consistent
certificate for Firebase to recognize. Generate one the same way and add
it as its own set of secrets:

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

Then register this keystore's SHA-1 (and SHA-256) fingerprint against the
`com.github.lukelloyd1985.mytasks.debug` app in Firebase Console per step
6 above - that's the step that actually fixes Google Sign-In on
CI-built debug APKs.

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

- `res/drawable/ic_provider_google.xml` is Google's official "G" identity
  mark (sourced from Google's own FirebaseUI-Android library), matching
  their [Sign in with Google branding guidelines](https://developers.google.com/identity/branding-guidelines).
  Don't recolor or restyle it.
- Any signed-in user can look up any other user's basic profile (name,
  email, photo) by email, which is what powers "invite by email" on a
  shared list. See `firestore.rules` if you want to tighten this further.
- Due-date reminders are best-effort: an on-device WorkManager job covers
  the device that set the reminder, and the `dueDateReminders` Cloud
  Function sweeps every 15 minutes as the cross-device fallback.
- **Account deletion** satisfies Play's dual in-app + web requirement:
  the Profile screen's "Delete my account" action calls the `deleteAccount`
  callable Cloud Function (`functions/src/accountDeletion.ts`), which
  transfers or removes the user's membership on every list they're part
  of (a shared list they own is handed to another member rather than
  deleted out from under them), unassigns their tasks elsewhere, deletes
  their `users/{uid}` doc, then deletes their Firebase Auth account.
  `docs/delete-account.html` covers the same thing for someone who no
  longer has the app installed, and gets registered as the "Delete
  account" URL in Play Console's Data safety section (see
  [Publishing to Google Play](#publishing-to-google-play)). This runs
  server-side rather than from the client both because one user must
  never be able to delete another's account, and because
  `firestore.rules` blocks client deletes of `users/{uid}` outright.
- Kotlin sources compile via AGP 9's built-in Kotlin support (no
  `org.jetbrains.kotlin.android` plugin applied), and Hilt's annotation
  processing runs via KSP rather than the now-incompatible `kapt`. Both
  changes were required together - `kapt` doesn't work under built-in
  Kotlin - see <https://developer.android.com/build/migrate-to-built-in-kotlin>.
