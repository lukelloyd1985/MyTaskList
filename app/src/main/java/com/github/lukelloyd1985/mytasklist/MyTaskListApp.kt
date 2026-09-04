package com.github.lukelloyd1985.mytasklist

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.github.lukelloyd1985.mytasklist.notifications.NotificationHelper
import kotlin.system.exitProcess

@HiltAndroidApp
class MyTaskListApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // TEMPORARY diagnostic checkpoints - see MainActivity.kt for the
        // rest of the sequence. Remove once the Play-Store-install-only
        // startup hang is found.
        debugToast("1: App.onCreate start")
        installCrashHandler()
        debugToast("2: crash handler installed")
        // TEMPORARY diagnostic: initFirebase() skipped entirely to test
        // whether it's the cause of the Play-install-only startup loop
        // (DEBUG 3 never appeared, and neither did the crash handler's own
        // DEBUG X, so whatever's failing here isn't a catchable Kotlin
        // exception - it's the strongest remaining suspect, since it's the
        // one thing in this path that talks to Google Play Services,
        // which behaves differently for Play App Signing's certificate
        // than the sideloaded release.keystore one). FCM push just won't
        // work with this skipped - everything else should be unaffected.
        // initFirebase()
        debugToast("3: Firebase initialization SKIPPED")
        NotificationHelper.createChannel(this)
        debugToast("4: App.onCreate complete")
    }

    private fun debugToast(message: String) {
        Toast.makeText(this, "DEBUG $message", Toast.LENGTH_SHORT).show()
    }

    // See CrashReportActivity's own comment for why this exists. Falls
    // through to the platform's own default handler (which shows the
    // usual "App keeps stopping" dialog) if launching the crash screen
    // itself fails for any reason, rather than risking a silent hang.
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // TEMPORARY diagnostic: fires unconditionally the instant this
            // handler runs, before the try block below, on the main thread
            // regardless of which thread actually crashed - confirms
            // whether an uncaught exception is being caught here at all,
            // since even a 2s delay before killing (see below) still
            // never showed CrashReportActivity.
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "DEBUG X: crash handler invoked, thread=${thread.name}", Toast.LENGTH_LONG).show()
            }
            try {
                val stackTrace = Log.getStackTraceString(throwable)
                startActivity(
                    Intent(this, CrashReportActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashReportActivity.EXTRA_STACK_TRACE, stackTrace)
                    },
                )
                // TEMPORARY diagnostic change: CrashReportActivity runs in
                // this same process, so killing immediately after
                // startActivity() (an async IPC to ActivityManagerService)
                // can race ahead of the new Activity actually being
                // created and drawn - suspected cause of the Play-install
                // startup loop (see MainActivity.kt/this file's DEBUG
                // toasts). Give it a moment to actually appear first.
                Thread.sleep(2000)
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } catch (t: Throwable) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    // No google-services.json/plugin - see the BuildConfig fields' own
    // comment in app/build.gradle.kts for why. FirebaseOptions requires
    // applicationId and apiKey (verified against
    // firebase-common/.../FirebaseOptions.java in
    // github.com/firebase/firebase-android-sdk: the constructor calls
    // Preconditions.checkState on applicationId and the Builder's
    // setApiKey() calls checkNotEmpty() - both throw if blank);
    // projectId/gcmSenderId are technically optional there, but FCM's
    // HTTP v1 API is project-scoped, so both are supplied anyway rather
    // than relying on undocumented fallback behavior. Must run before
    // anything else in the app calls FirebaseMessaging.getInstance() -
    // Application.onCreate() is the earliest hook available.
    private fun initFirebase() {
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(this, options)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
