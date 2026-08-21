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
  Build* → **Run workflow**. It builds debug + release APKs and attaches
  them to the workflow run as downloadable artifacts - handy for giving
  testers a build without cutting a release.
- **Publishing a GitHub Release** automatically builds a release APK and
  attaches it to that release.

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
