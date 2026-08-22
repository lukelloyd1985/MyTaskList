plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.play.publisher)
}

android {
    namespace = "com.mytasks.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mytasks.app"
        minSdk = 26
        targetSdk = 35

        // Play Store rejects any upload whose versionCode isn't strictly
        // greater than every previous upload's. GITHUB_RUN_NUMBER
        // increments on every run of this workflow, so it's a reliable
        // monotonic source in CI; local builds fall back to 1.
        // RELEASE_VERSION_NAME is set by the release workflow job to the
        // git tag (e.g. "v1.2.3"); local builds fall back to "1.0.0-dev".
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = System.getenv("RELEASE_VERSION_NAME") ?: "1.0.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
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
    kapt(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    kapt(libs.androidx.hilt.compiler)

    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)

    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
