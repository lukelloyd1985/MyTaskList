package com.github.lukelloyd1985.mytasklist

import android.app.Application
import android.content.Intent
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
        builder.setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
        debugToast("2c: setProjectId done")
        builder.setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
        debugToast("2d: setApplicationId done")
        builder.setApiKey(BuildConfig.FIREBASE_API_KEY)
        debugToast("2e: setApiKey done")
        builder.setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
        debugToast("2f: setGcmSenderId done")
        val options = builder.build()
        debugToast("2g: options built")
        FirebaseApp.initializeApp(this, options)
        debugToast("2h: FirebaseApp.initializeApp() returned")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
