package com.example

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.ui.CircleLensScreen
import com.example.ui.CircleLensViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.CircleLensScreenshotHolder
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: CircleLensViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        disableWindowTransitions()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (handleIncomingIntent(intent)) {
            // Intent fully handled and the activity is closing (e.g. PROCESS_TEXT share bridge)
            return
        }

        lifecycleScope.launch {
            viewModel.closeAppEvent.collect {
                closeApp()
            }
        }

        setContent {
            MyApplicationTheme(darkTheme = true) {
                CircleLensScreen(
                    viewModel = viewModel,
                    onCloseApp = { closeApp() }
                )
            }
        }
    }

    private fun disableWindowTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun closeApp() {
        finish()
        disableWindowTransitions()
    }

    override fun onResume() {
        super.onResume()
        disableWindowTransitions()
        // Single consumer path: ask the ViewModel to pick up any new pending
        // screenshot (it deduplicates by screenshot id).
        viewModel.loadPendingScreenshotIfNew()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            // Let the ~550MB translation model be evicted under pressure;
            // it reloads transparently on the next translate.
            viewModel.onLowMemory()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        disableWindowTransitions()
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Returns true when the intent was fully handled and the activity is closing. */
    private fun handleIncomingIntent(intent: Intent?): Boolean {
        if (intent == null) return false

        val isAssist = intent.getBooleanExtra("from_voice_assist", false) ||
                intent.action == Intent.ACTION_ASSIST ||
                intent.action == Intent.ACTION_VOICE_COMMAND

        // 1. Check Voice Assistant / ScreenshotHolder (single consumer path)
        if (CircleLensScreenshotHolder.hasPendingScreenshot()) {
            viewModel.loadPendingScreenshotIfNew()
            return false
        }

        // 2. Check direct screenshot parcelable in Intent extras
        val screenshotBitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("android.intent.extra.ASSIST_SCREENSHOT", Bitmap::class.java)
                    ?: intent.getParcelableExtra("screenshot", Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Parcelable>("android.intent.extra.ASSIST_SCREENSHOT") as? Bitmap)
                    ?: (intent.getParcelableExtra<Parcelable>("screenshot") as? Bitmap)
            }
        } catch (e: Throwable) {
            null
        }

        if (screenshotBitmap != null) {
            viewModel.loadBitmapDirect(screenshotBitmap, null)
            return false
        }

        // 3. Text selected in another app ("Circle OCR" entry in the text
        // selection toolbar, declared via ACTION_PROCESS_TEXT in the manifest):
        // open the share sheet for that text right away and close this overlay,
        // so the app acts as a share bridge for selected text.
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            val selectedText = try {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            } catch (e: Throwable) {
                null
            }
            if (!selectedText.isNullOrBlank()) {
                viewModel.shareManager.shareTextGeneral(selectedText)
            }
            closeApp()
            return true
        }

        // 4. Check shared image URI
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
                    }
                    if (imageUri != null) {
                        viewModel.loadBitmapFromUri(imageUri)
                    }
                }
            }
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> {
                viewModel.onAssistLaunched()
            }
            else -> {
                if (isAssist) {
                    viewModel.onAssistLaunched()
                } else if (intent.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true) {
                    // Opened directly by tapping the app launcher icon -> show setup wizard
                    viewModel.showAssistHelp(true)
                }
            }
        }
        return false
    }
}
