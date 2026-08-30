package com.mytasks.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import com.mytasks.app.ui.auth.AuthViewModel
import com.mytasks.app.ui.auth.LoginScreen
import com.mytasks.app.ui.navigation.MyTasksNavHost
import com.mytasks.app.ui.theme.MyTasksTheme

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
        deepLink = extractDeepLink(intent)
        enableEdgeToEdge()
        setContent {
            MyTasksTheme {
                MyTasksRoot(deepLink = deepLink, onDeepLinkConsumed = { deepLink = null })
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
private fun MyTasksRoot(
    deepLink: DeepLinkTarget?,
    onDeepLinkConsumed: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
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
        MyTasksNavHost(deepLink = deepLink, onDeepLinkConsumed = onDeepLinkConsumed)
    }
}
