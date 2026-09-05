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
        // TEMPORARY diagnostic checkpoints - the classic-SHA-1 fix (see
        // README "Publishing to Google Play" step 5) did NOT resolve the
        // Play-install-only startup crash; initFirebase() still dies the
        // same way. These bisect *inside* the function to find the exact
        // failing line, since "somewhere in initFirebase()" wasn't enough
        // last time. Remove once the cause is found.
        debugToast("1: App.onCreate start")
        installCrashHandler()
        debugToast("2: crash handler installed")
        initFirebase()
        debugToast("3: initFirebase() returned")
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
            // handler runs, before anything else below, on the main
            // thread regardless of which thread actually crashed - so we
            // find out in the same test round whether this is even a
            // catchable Kotlin/Java exception this time, rather than
            // needing a separate upload+test cycle just to check.
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "DEBUG X: crash handler invoked, thread=${thread.name}, throwable=${throwable::class.java.name}: ${throwable.message}", Toast.LENGTH_LONG).show()
            }
            try {
                val stackTrace = Log.getStackTraceString(throwable)
                startActivity(
                    Intent(this, CrashReportActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashReportActivity.EXTRA_STACK_TRACE, stackTrace)
                    },
                )
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
        debugToast("2a: initFirebase start")
        val builder = FirebaseOptions.Builder()
        debugToast("2b: Builder() created")

        val projectId = BuildConfig.FIREBASE_PROJECT_ID
        debugToast("2c: FIREBASE_PROJECT_ID = [$projectId]")
        builder.setProjectId(projectId)
        debugToast("2d: setProjectId done")

        // Last confirmed checkpoint two rounds running was 2c (the old,
        // coarser numbering - equivalent to 2d here) - died before ever
        // reaching what's now 2f. Splitting "read the BuildConfig
        // constant" from "call the Builder setter" narrows it further:
        // if 2e shows but 2f doesn't, the value itself is fine and the
        // SDK call is what's dying; if 2e never shows, reading the
        // constant itself is somehow the trigger (very unlikely, but
        // worth ruling out explicitly rather than assuming).
        val applicationId = BuildConfig.FIREBASE_APPLICATION_ID
        debugToast("2e: FIREBASE_APPLICATION_ID = [$applicationId]")
        builder.setApplicationId(applicationId)
        debugToast("2f: setApplicationId done")

        val apiKey = BuildConfig.FIREBASE_API_KEY
        debugToast("2g: FIREBASE_API_KEY = [$apiKey]")
        builder.setApiKey(apiKey)
        debugToast("2h: setApiKey done")

        val senderId = BuildConfig.FIREBASE_SENDER_ID
        debugToast("2i: FIREBASE_SENDER_ID = [$senderId]")
        builder.setGcmSenderId(senderId)
        debugToast("2j: setGcmSenderId done")

        val options = builder.build()
        debugToast("2k: options built")
        FirebaseApp.initializeApp(this, options)
        debugToast("2l: FirebaseApp.initializeApp() returned")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
