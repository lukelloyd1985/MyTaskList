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
    // Matches Google Play's actual target-API requirement (see targetSdk
    // below). The Appwrite SDK's own AAR declares compileSdk = 37, but
    // that's only what the library itself was compiled against - it
    // doesn't force consumers to match it, and "platforms;android-37"
    // isn't published to the public SDK repository yet (sdkmanager fails
    // to find it - confirmed via a real CI run), so 37 isn't installable
    // here regardless. Revisit once that platform is actually available,
    // if AAPT/manifest-merging against the Appwrite AAR ever needs it.
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
        // Google Play requires new apps/updates to target API 36 (Android
        // 16) or higher from August 31, 2026 - see README "Publishing to
        // Google Play". Matches compileSdk above.
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

        // Appwrite Cloud connection details - never hardcoded in Kotlin
        // source, always read from BuildConfig (same env-var-driven
        // pattern as the signing config below). Empty-string defaults for
        // the project/function IDs mean a build with no env vars set still
        // compiles; it just fails at runtime when it tries to talk to
        // Appwrite, same tradeoff as the old default_web_client_id gap.
        buildConfigField("String", "APPWRITE_ENDPOINT", "\"${System.getenv("MYTASKS_APPWRITE_ENDPOINT") ?: "https://cloud.appwrite.io/v1"}\"")
        buildConfigField("String", "APPWRITE_PROJECT_ID", "\"${System.getenv("MYTASKS_APPWRITE_PROJECT_ID") ?: ""}\"")
        buildConfigField("String", "APPWRITE_DATABASE_ID", "\"${System.getenv("MYTASKS_APPWRITE_DATABASE_ID") ?: "mytasks"}\"")
        buildConfigField("String", "APPWRITE_COLLECTION_USERS_ID", "\"${System.getenv("MYTASKS_APPWRITE_COLLECTION_USERS_ID") ?: "users"}\"")
        buildConfigField("String", "APPWRITE_COLLECTION_LISTS_ID", "\"${System.getenv("MYTASKS_APPWRITE_COLLECTION_LISTS_ID") ?: "lists"}\"")
        buildConfigField("String", "APPWRITE_COLLECTION_TASKS_ID", "\"${System.getenv("MYTASKS_APPWRITE_COLLECTION_TASKS_ID") ?: "tasks"}\"")
        buildConfigField("String", "APPWRITE_FUNCTION_DELETE_ACCOUNT_ID", "\"${System.getenv("MYTASKS_APPWRITE_FUNCTION_DELETE_ACCOUNT_ID") ?: ""}\"")

        // Deep link scheme Appwrite's OAuth2 flow redirects back into the
        // app through - see AndroidManifest.xml and AuthRepository.
        manifestPlaceholders["appwriteCallbackScheme"] = "appwrite-callback-${System.getenv("MYTASKS_APPWRITE_PROJECT_ID") ?: "unset"}"
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
