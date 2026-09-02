package com.github.lukelloyd1985.mytasklist

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import com.github.lukelloyd1985.mytasklist.ui.auth.AuthViewModel
import com.github.lukelloyd1985.mytasklist.ui.auth.LoginScreen
import com.github.lukelloyd1985.mytasklist.ui.navigation.MyTaskListNavHost
import com.github.lukelloyd1985.mytasklist.ui.theme.MyTaskListTheme

/** Which list (and, optionally, task) a notification tap should open -
 *  see NotificationHelper.show's PendingIntent, which is the only source
 *  of MainActivity.EXTRA_LIST_ID/EXTRA_TASK_ID extras. */
data class DeepLinkTarget(val listId: String, val taskId: String?)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LIST_ID = "listId"
        const val EXTRA_TASK_ID = "taskId"
    }

    // Held here (not just read once in onCreate) because a notification
    // tap while the app is already running arrives via onNewIntent, not a
    // fresh onCreate - android:launchMode="singleTask" (see
    // AndroidManifest.xml) routes it there instead of spawning a second
    // MainActivity instance.
    private var deepLink by mutableStateOf<DeepLinkTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TEMPORARY diagnostic checkpoints, continued from MyTaskListApp.kt.
        Toast.makeText(this, "DEBUG 5: MainActivity.onCreate start", Toast.LENGTH_SHORT).show()
        deepLink = extractDeepLink(intent)
        enableEdgeToEdge()
        Toast.makeText(this, "DEBUG 6: about to setContent", Toast.LENGTH_SHORT).show()
        setContent {
            MyTaskListTheme {
                MyTaskListRoot(deepLink = deepLink, onDeepLinkConsumed = { deepLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = extractDeepLink(intent)
    }

    private fun extractDeepLink(intent: Intent): DeepLinkTarget? {
        val listId = intent.getStringExtra(EXTRA_LIST_ID) ?: return null
        return DeepLinkTarget(listId, intent.getStringExtra(EXTRA_TASK_ID))
    }
}

@Composable
private fun MyTaskListRoot(
    deepLink: DeepLinkTarget?,
    onDeepLinkConsumed: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    // TEMPORARY diagnostic checkpoint, continued from onCreate() above.
    // authViewModel's default value (hiltViewModel(), which constructs the
    // whole Hilt/Appwrite dependency chain behind AuthViewModel) is
    // resolved before this composable body starts running - so this toast
    // firing means that construction completed successfully.
    val debugContext = LocalContext.current
    LaunchedEffect(Unit) {
        Toast.makeText(debugContext, "DEBUG 7: MyTaskListRoot composing", Toast.LENGTH_SHORT).show()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val user by authViewModel.currentUser.collectAsStateWithLifecycle()

    LaunchedEffect(user) {
        if (user != null) {
            authViewModel.registerPushTarget()
        }
    }

    if (user == null) {
        LoginScreen(authViewModel)
    } else {
        MyTaskListNavHost(deepLink = deepLink, onDeepLinkConsumed = onDeepLinkConsumed)
    }
}
