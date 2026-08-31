package com.github.lukelloyd1985.mytasklist

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Deliberately dependency-free (no Compose, no app theme, no layout
 * XML) - built plainly in code so it stands the best chance of
 * rendering even if whatever crashed is something Compose-related or
 * otherwise deep in this app's own code. Exists because this project
 * has no crash-reporting service (no Crashlytics/Sentry), release
 * builds are minified (so a raw logcat trace would be unsymbolicated
 * without the R8 mapping file anyway), and there is no way to pull
 * logcat at all without adb - this puts the stack trace directly on
 * screen, selectable and shareable, so it can be read or sent off
 * without either. See MyTaskListApp's crash-handler installation.
 */
class CrashReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE).orEmpty()

        val textView = TextView(this).apply {
            text = "My Task List crashed. Share this with the developer:\n\n$stackTrace"
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(32, 32, 32, 32)
        }
        val shareButton = Button(this).apply {
            text = "Share crash report"
            setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, stackTrace)
                }
                startActivity(Intent.createChooser(shareIntent, null))
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(shareButton)
            addView(
                textView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
            )
        }
        setContentView(layout)
    }

    companion object {
        const val EXTRA_STACK_TRACE = "stack_trace"
    }
}
