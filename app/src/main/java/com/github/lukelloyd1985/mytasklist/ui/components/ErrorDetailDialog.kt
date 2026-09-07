package com.github.lukelloyd1985.mytasklist.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A Snackbar disappears in a few seconds, wraps or truncates long text,
// and its text can't be selected or copied - none of which works for
// an error that needs to be read carefully (an exception type/cause,
// or a bundle of device diagnostics) or sent elsewhere for diagnosis.
// This stays on screen until dismissed, is fully selectable, and has a
// one-tap Share action.
@Composable
fun ErrorDetailDialog(message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                context.startActivity(Intent.createChooser(shareIntent, null))
            }) { Text("Share") }
        },
        title = { Text("Error detail") },
        text = {
            SelectionContainer {
                Text(
                    text = message,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
    )
}
