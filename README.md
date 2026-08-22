# MyTasks

A modern Android app (Kotlin + Jetpack Compose + Material 3) for managing
shared task lists - Short term, Long term, Garden, House, or anything else
you organize your life into.

- **Multiple lists**, each with its own **visibility**: `Private` (only
  you) or `Shared` (invite people by email).
- **Tasks** can be **assigned** to a list member, given a **due date**, and
  optionally trigger a **reminder notification**.
- **Sign in** with Google.
- **CI/CD**: a GitHub Actions workflow builds an APK on every manual run
  (for testing) and attaches a release APK to every published GitHub
  Release.

The app builds and runs out of the box against a placeholder Firebase
project so you can try the UI immediately, but sign-in and data sync need a
real backend - see [Backend setup](#backend-setup) below.

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

## Backend setup

1. **Create a Firebase project** at <https://console.firebase.google.com>,
   add an Android app with package name `com.mytasks.app` (and, for local
   debug builds, `com.mytasks.app.debug`), and download the real
   `google-services.json` over the placeholder committed at
   `app/google-services.json`.
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
   `app/google-services.json` (or update the `GOOGLE_SERVICES_JSON` CI
   secret).
6. **Register your signing certificates' SHA-1/SHA-256 fingerprints** in
   Firebase Console → Project settings → Your apps, on both the
   `com.mytasks.app` and `com.mytasks.app.debug` entries. Google Sign-In
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

Optionally also add `GOOGLE_SERVICES_JSON` (the real `google-services.json`,
base64-encoded) as a secret so CI builds authenticate against your real
Firebase project instead of the committed placeholder.

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
`com.mytasks.app.debug` app in Firebase Console per step 6 above - that's
the step that actually fixes Google Sign-In on CI-built debug APKs.

## Publishing to Google Play

Getting onto Google Play has two kinds of steps: a handful that **only you
can do**, by hand, in Play Console (there's no API for them - creating an
app and its first release requires a human in the Play Console UI), and
everything after that, which CI automates.

### 1. One-time setup you do by hand

1. **Host the privacy policy.** Play requires a live URL for one.
   [`docs/privacy.html`](docs/privacy.html) is a starting draft - replace
   every `[bracketed placeholder]` in it (especially the support email),
   then enable it at Settings → Pages → *Build and deployment* → *Deploy
   from a branch* → branch `feature/mvp`, folder `/docs`. It'll be served
   at `https://<your-github-username>.github.io/MyTasks/privacy.html`.
2. **Create the app** in [Play Console](https://play.google.com/console):
   *Create app* → name it, set default language, "App" (not game), and
   Free. The package name is fixed at creation to whatever you tell it -
   use `com.mytasks.app` to match `applicationId` in
   `app/build.gradle.kts`.
3. **Complete "App content"** (Play Console won't allow any release
   without these): Privacy policy URL (from step 1), Ads (No ads, unless
   you've added some), App access (all functionality is available without
   special access), Content ratings questionnaire, Target audience,
   **Data safety** section - this should mirror `docs/privacy.html`:
   personal info collected (name, email, photo - via Google Sign-In),
   task/list content, shared with third parties = **No**, encrypted in
   transit = **Yes**, users can request data deletion = **Yes**, pointing
   at the same privacy policy page.
4. **Fill in the Store listing**: short/full description, app icon
   (512×512 PNG), feature graphic (1024×500 PNG), and at least 2 phone
   screenshots. There's nothing in this repo for these yet - the launcher
   icon vectors in `app/src/main/res/drawable` aren't a substitute for a
   proper Play Store icon/graphics set.
5. **Do the first release by hand.** Build an AAB
   (`./gradlew bundleRelease`, or download one from a GitHub Release once
   you've tagged one - see below), go to Testing → Internal testing →
   *Create new release*, upload it, add release notes, and roll it out.
   Add yourself (and any other testers) as an internal tester so you can
   install it.

### 2. Let CI handle every release after that

1. In Play Console, go to **Setup → API access** and follow its prompt to
   create a Google Cloud service account (or link an existing project).
   In the Cloud Console, create a JSON key for that service account.
2. Back in Play Console's API access page, grant that service account
   **Release manager**-level access to this app (or a custom role limited
   to managing releases on the internal track, if you'd rather keep it
   narrower).
3. Add the JSON key's full contents as the `PLAY_SERVICE_ACCOUNT_JSON`
   repository secret (Settings → Secrets and variables → Actions) -
   paste the raw JSON, not base64.
4. From then on, **publishing a GitHub Release** builds the AAB and runs
   `publishReleaseBundle`, which uploads it straight to the **internal
   testing** track with `releaseStatus = COMPLETED` (see the `play { }`
   block in `app/build.gradle.kts`). CI never touches the production
   track - promote a release from internal testing to production
   yourself in Play Console once you're happy with it.
5. **Version each release with a git tag** (e.g. `v1.0.1`) when you
   create the GitHub Release - it becomes the app's `versionName`.
   `versionCode` auto-increments from `GITHUB_RUN_NUMBER`, so Play's
   "each upload needs a higher version code than the last" requirement is
   handled for you automatically.

## Notes & tradeoffs

- `res/drawable/ic_provider_google.xml` is a simple stand-in, not the
  official brand mark - swap it for Google's real logo asset per their
  brand guidelines before shipping.
- Any signed-in user can look up any other user's basic profile (name,
  email, photo) by email, which is what powers "invite by email" on a
  shared list. See `firestore.rules` if you want to tighten this further.
- Due-date reminders are best-effort: an on-device WorkManager job covers
  the device that set the reminder, and the `dueDateReminders` Cloud
  Function sweeps every 15 minutes as the cross-device fallback.
- **Account deletion isn't implemented yet.** `docs/privacy.html` points
  people at a support email for now, which satisfies Play's account
  deletion policy on its own, but Google increasingly expects (and
  reviewers may ask for) an in-app "Delete my account" action for apps
  that support Google Sign-In. Worth adding before a real launch: it'd
  need to remove the user's `users/{uid}` doc, their Firebase Auth
  account, and either delete or reassign ownership of any lists they own
  (a shared list they own can't just vanish for its other members).
- Kotlin sources compile via AGP 9's built-in Kotlin support (no
  `org.jetbrains.kotlin.android` plugin applied), and Hilt's annotation
  processing runs via KSP rather than the now-incompatible `kapt`. Both
  changes were required together - `kapt` doesn't work under built-in
  Kotlin - see <https://developer.android.com/build/migrate-to-built-in-kotlin>.
