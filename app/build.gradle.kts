plugins {
    alias(libs.plugins.android.application)
    // Kotlin sources compile via AGP's built-in Kotlin support (default
    // since AGP 9.0) - no separate org.jetbrains.kotlin.android plugin.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.play.publisher)
}

android {
    namespace = "com.mytasks.app"
    // 36, not 37: API 37 isn't published as an installable stable SDK
    // platform yet (see the `appwrite` version comment in
    // gradle/libs.versions.toml for how this was actually confirmed, not
    // assumed) - `sdkmanager "platforms;android-37"` in CI genuinely fails
    // with "Failed to find package", regardless of cmdline-tools version.
    // A previous fix here wrongly assumed that error was just a stale
    // package listing and bumped this to 37 anyway (matching what
    // io.appwrite:sdk-for-android 26.0.0+ requires via AAR metadata) -
    // pinning `appwrite` back to 25.2.0 removes that requirement instead,
    // so 36 compiles cleanly again. 36 already satisfies Play's minimum
    // targetSdk requirement (36+ from August 31, 2026 - see README
    // "Publishing to Google Play").
    compileSdk = 36

    defaultConfig {
        // Play-facing identity only - not the same as `namespace` above,
        // which stays com.mytasks.app for the Kotlin source package/R
        // class. com.mytasks.app was already taken on Play Store, so the
        // app's actual Play/OS identity lives under this repository's
        // GitHub namespace instead. See README "Publishing to Google
        // Play" for the Firebase re-registration this requires.
        applicationId = "com.github.lukelloyd1985.mytasks"
        minSdk = 31 // Android 12
        // Matches compileSdk above (also satisfies Google Play's minimum
        // requirement to target API 36+ from August 31, 2026 - see README
        // "Publishing to Google Play").
        targetSdk = 36

        // Play Store rejects any upload whose versionCode isn't strictly
        // greater than every previous upload's. GITHUB_RUN_NUMBER
        // increments on every run of this workflow, so it's a reliable
        // monotonic source in CI; local builds fall back to 1.
        // RELEASE_VERSION_NAME is set by the release workflow job to the
        // git tag (e.g. "v1.2.3"); local builds fall back to "1.0.0-dev".
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = System.getenv("RELEASE_VERSION_NAME") ?: "1.0.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Appwrite project ID isn't sensitive - like the Firebase
        // project identifiers below, it only identifies the project (no
        // access without a session/API key) and ships inside the compiled
        // APK regardless of how it's configured here.
        // So rather than duplicate it in a GitHub secret AND a separate
        // local/CI env var (two places the same value has to be pasted
        // and kept in sync), it's read directly from
        // appwrite/appwrite.json's own "projectId" field - the same file
        // deploy-appwrite.yml already pushes from, and the single place a
        // contributor standing up their own backend needs to set it (see
        // README "Backend setup").
        val appwriteProjectId = Regex("\"projectId\"\\s*:\\s*\"([^\"]*)\"")
            .find(rootProject.file("appwrite/appwrite.json").readText())
            ?.groupValues?.get(1) ?: ""

        // The rest of the Appwrite connection details are fixed IDs this
        // codebase itself chose (not assigned by Appwrite), matching
        // appwrite/appwrite.json's own $id fields - env-var overrides
        // exist only for a contributor customizing them, not because
        // they're expected to vary per-environment like the project ID.
        buildConfigField("String", "APPWRITE_ENDPOINT", "\"${System.getenv("MYTASKS_APPWRITE_ENDPOINT") ?: "https://cloud.appwrite.io/v1"}\"")
        buildConfigField("String", "APPWRITE_PROJECT_ID", "\"$appwriteProjectId\"")
        buildConfigField("String", "APPWRITE_DATABASE_ID", "\"${System.getenv("MYTASKS_APPWRITE_DATABASE_ID") ?: "mytasks"}\"")
        buildConfigField("String", "APPWRITE_COLLECTION_USERS_ID", "\"${System.getenv("MYTASKS_APPWRITE_COLLECTION_USERS_ID") ?: "users"}\"")
        buildConfigField("String", "APPWRITE_COLLECTION_LISTS_ID", "\"${System.getenv("MYTASKS_APPWRITE_COLLECTION_LISTS_ID") ?: "lists"}\"")
        buildConfigField("String", "APPWRITE_COLLECTION_TASKS_ID", "\"${System.getenv("MYTASKS_APPWRITE_COLLECTION_TASKS_ID") ?: "tasks"}\"")
        buildConfigField("String", "APPWRITE_FUNCTION_MAINTENANCE_ID", "\"${System.getenv("MYTASKS_APPWRITE_FUNCTION_MAINTENANCE_ID") ?: "maintenance"}\"")

        // Deep link scheme Appwrite's OAuth2 flow redirects back into the
        // app through - see AndroidManifest.xml and AuthRepository.
        manifestPlaceholders["appwriteCallbackScheme"] = "appwrite-callback-${appwriteProjectId.ifEmpty { "unset" }}"

        // Firebase Cloud Messaging is the only Firebase surface this app
        // still uses (see README "Architecture" - Appwrite Messaging now
        // owns everything except the on-device FCM token itself). Rather
        // than the google-services Gradle plugin + a committed
        // google-services.json, FirebaseApp is initialized manually in
        // MyTasksApp.onCreate() from these four values - none of them are
        // secrets (they're the same non-sensitive identifiers
        // google-services.json would have carried, just supplied directly
        // instead of through a generated file + plugin), so this follows
        // the same BuildConfig-from-env-var pattern as every other config
        // value here rather than introducing a second, inconsistent
        // mechanism for one library. Firebase Console → Project settings →
        // General → your Android app is where all four come from - see
        // README "Backend setup".
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${System.getenv("MYTASKS_FIREBASE_PROJECT_ID") ?: ""}\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${System.getenv("MYTASKS_FIREBASE_APPLICATION_ID") ?: ""}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${System.getenv("MYTASKS_FIREBASE_API_KEY") ?: ""}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${System.getenv("MYTASKS_FIREBASE_SENDER_ID") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("MYTASKS_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MYTASKS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MYTASKS_KEY_ALIAS")
                keyPassword = System.getenv("MYTASKS_KEY_PASSWORD")
            }
        }
        // GitHub Actions runners are a fresh VM every run, so with no
        // override here AGP's built-in debug signing would auto-generate a
        // brand-new, random debug.keystore on every CI build. Google Sign-In
        // verifies the calling app's signing certificate as part of its
        // account-reauth check, so a debug APK signed with a different,
        // unregistered certificate every run fails that check every time
        // (surfaces as GetCredentialException type TYPE_USER_CANCELED,
        // message "[16] Account reauth failed"). Overriding with a stable,
        // CI-provided keystore (see README) - whose SHA-1 gets registered in
        // Firebase once - fixes this. Falls back to AGP's default debug
        // signing (unaffected) for local builds where this isn't set.
        getByName("debug") {
            val keystorePath = System.getenv("MYTASKS_DEBUG_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MYTASKS_DEBUG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MYTASKS_DEBUG_KEY_ALIAS")
                keyPassword = System.getenv("MYTASKS_DEBUG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val keystorePath = System.getenv("MYTASKS_KEYSTORE_PATH")
            signingConfig = if (!keystorePath.isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                // Falls back to the debug keystore so `assembleRelease` still
                // produces an installable, unsigned-for-store APK for manual
                // testing builds when release-signing secrets aren't configured.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No explicit Kotlin jvmTarget: built-in Kotlin defaults it from
    // compileOptions.targetCompatibility above.

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

// Publishes the release App Bundle to Google Play via `publishReleaseBundle`
// (see .github/workflows/android-build.yml and README "Publishing to Google
// Play"). Configuring this block never requires credentials - only running
// a publish task does, via the ANDROID_PUBLISHER_CREDENTIALS environment
// variable (a Play Console service account's JSON key) - so this is a no-op
// for every other build until that's set.
play {
    track.set("internal")
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
    defaultToAppBundles.set(true)
}

dependencies {
    implementation(platform(libs.compose.bom))
    // Kept solely so firebase-messaging-ktx (the one Firebase surface this
    // migration keeps, as the FCM push transport) resolves its version -
    // every other Firebase dependency that used to come off this BOM
    // (auth/firestore/functions) is gone.
    implementation(platform(libs.firebase.bom))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.firebase.messaging)

    // io.appwrite:sdk-for-android is still the correct, actively-maintained
    // client SDK for Android/Kotlin (it was not merged into
    // io.appwrite:sdk-for-kotlin, which is a separate server-side SDK - see
    // its README's "If you're looking for the Android SDK..." note). Version
    // pin verified in gradle/libs.versions.toml against the SDK's own
    // README/CHANGELOG.
    implementation(libs.appwrite)

    implementation(libs.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
