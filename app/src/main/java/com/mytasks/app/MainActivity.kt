package com.mytasks.app

import android.Manifest
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import com.mytasks.app.ui.auth.AuthViewModel
import com.mytasks.app.ui.auth.LoginScreen
import com.mytasks.app.ui.navigation.MyTasksNavHost
import com.mytasks.app.ui.theme.MyTasksTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyTasksTheme {
                MyTasksRoot()
            }
        }
    }
}

@Composable
private fun MyTasksRoot(authViewModel: AuthViewModel = hiltViewModel()) {
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val user by authViewModel.currentUser.collectAsStateWithLifecycle()
    if (user == null) {
        LoginScreen(authViewModel)
    } else {
        MyTasksNavHost()
    }
}
