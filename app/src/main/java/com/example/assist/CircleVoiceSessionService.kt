package com.example.assist

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.example.MainActivity
import com.example.util.CircleLensScreenshotHolder

class CircleVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return CircleVoiceInteractionSession(this)
    }
}

/**
 * Session without UI of its own: it waits for the screenshot, opens
 * MainActivity with startAssistantActivity and finishes.
 *
 * startAssistantActivity is refused once the session is hidden ("Cannot start
 * assistant activity on a hidden session"), and the plain startActivity
 * fallback is blocked as a background activity start on Android 10+. So the
 * overlay sometimes did not open at all although the system had already taken
 * the screenshot (the white "screen captured" edge flash):
 *  - the default onCloseSystemDialogs() hides the session, and the power
 *    long-press itself broadcasts "close system dialogs" around the time the
 *    session is shown;
 *  - a fixed 350ms timer (or onHandleAssist arriving first) opened the overlay
 *    before the screenshot and finished the session, dropping the screenshot.
 * Now the session ignores close-system-dialogs, opens the overlay when the
 * screenshot arrives (or after [SCREENSHOT_WAIT_MS], then still hands a late
 * screenshot to the open overlay), and if the system hides it before the
 * overlay opened it shows itself again once.
 */
class CircleVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())

    /** MainActivity was started for the current show. */
    private var overlayLaunched = false

    /** The screenshot of the current show arrived (null on screens that forbid capture). */
    private var screenshotReceived = false

    /** finish() was requested; later callbacks of this session are ignored. */
    private var finishing = false

    /** The session asked to be shown again after being hidden before the overlay opened. */
    private var reshowRequested = false

    /** Showing again is attempted once per session, so it can never loop. */
    private var reshowAttempted = false

    override fun onCreate() {
        super.onCreate()
        try {
            // CircleLens delegates overlay UI to MainActivity
            setUiEnabled(false)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not setUiEnabled(false)", e)
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "onShow, showFlags: $showFlags")
        if (finishing) return
        handler.removeCallbacksAndMessages(null)
        overlayLaunched = false
        screenshotReceived = false
        reshowRequested = false

        if (showFlags and SHOW_WITH_SCREENSHOT == 0) {
            // No screenshot was requested, so none will arrive.
            launchOverlayAndFinish()
            return
        }
        // The screenshot normally arrives within a few hundred ms. If it does
        // not, open the overlay anyway and keep the session a little longer so
        // a late screenshot still reaches it.
        handler.postDelayed({ onScreenshotTimeout() }, SCREENSHOT_WAIT_MS)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        Log.d(TAG, "onHandleScreenshot: ${screenshot?.width}x${screenshot?.height}")
        if (finishing) return
        screenshotReceived = true
        handler.removeCallbacksAndMessages(null)
        if (screenshot != null) {
            // An overlay that is already open (late screenshot) picks it up from the holder.
            CircleLensScreenshotHolder.setScreenshot(screenshot)
        }
        if (overlayLaunched) {
            finishSession()
        } else {
            launchOverlayAndFinish()
        }
    }

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        // The overlay needs the screenshot, which is delivered separately.
        // Opening it here (often before the screenshot) finished the session
        // and dropped the screenshot.
        Log.d(TAG, "onHandleAssist")
    }

    override fun onCloseSystemDialogs() {
        // The default hides the session, after which startAssistantActivity is
        // refused. This session has no UI to close and finishes itself as soon
        // as the overlay is open, so it is ignored.
        Log.d(TAG, "Ignoring onCloseSystemDialogs (overlayLaunched=$overlayLaunched)")
    }

    override fun onHide() {
        super.onHide()
        Log.d(TAG, "onHide (overlayLaunched=$overlayLaunched, finishing=$finishing)")
        if (finishing || overlayLaunched || reshowRequested) return
        // Hidden by the system before the overlay opened: show again once
        // (this also requests a fresh screenshot), otherwise give up quietly.
        if (!requestReshow()) finishSession()
    }

    private fun onScreenshotTimeout() {
        if (finishing || screenshotReceived) return
        Log.w(TAG, "No screenshot after ${SCREENSHOT_WAIT_MS}ms; opening the overlay without it")
        if (!overlayLaunched && !launchOverlay()) {
            if (!reshowRequested) finishSession()
            return
        }
        handler.postDelayed({ finishSession() }, LATE_SCREENSHOT_GRACE_MS)
    }

    private fun launchOverlayAndFinish() {
        // When the launch failed because the session was hidden and a new show
        // was requested, that show opens the overlay instead.
        if (launchOverlay() || !reshowRequested) finishSession()
    }

    /** Starts MainActivity. Returns false when it could not be started. */
    private fun launchOverlay(): Boolean {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            putExtra("from_voice_assist", true)
            putExtra("assist_launch_time", System.currentTimeMillis())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startAssistantActivity(intent)
                overlayLaunched = true
                return true
            } catch (e: IllegalStateException) {
                // "Cannot start assistant activity on a hidden session"
                Log.w(TAG, "startAssistantActivity refused", e)
                if (requestReshow()) return false
            } catch (e: Throwable) {
                Log.w(TAG, "startAssistantActivity failed, falling back to context.startActivity", e)
            }
        }

        // Last resort. On Android 10+ the system may block this as a background
        // activity start, which is why the session is kept shown above.
        return try {
            val options = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
            context.startActivity(intent, options)
            overlayLaunched = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to launch MainActivity", e)
            false
        }
    }

    /**
     * Shows this session again after the system hid it before the overlay
     * opened (startAssistantActivity only works on a shown session). The new
     * show requests a fresh screenshot. At most once per session, and not over
     * the lock screen. Returns true when the new show was requested.
     */
    private fun requestReshow(): Boolean {
        if (finishing || reshowAttempted) return false
        reshowAttempted = true
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return false
        return try {
            handler.removeCallbacksAndMessages(null)
            show(null, SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
            reshowRequested = true
            // onShow clears this; it only fires if the new show never arrives.
            handler.postDelayed({ if (reshowRequested) finishSession() }, RESHOW_TIMEOUT_MS)
            Log.d(TAG, "Session was hidden before the overlay opened; showing it again")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not show the session again", e)
            false
        }
    }

    private fun finishSession() {
        if (finishing) return
        finishing = true
        handler.removeCallbacksAndMessages(null)
        try {
            finish()
        } catch (e: Throwable) {
            try {
                hide()
            } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "VoiceAssistSession"

        /** How long the overlay waits for the screenshot before opening without it. */
        const val SCREENSHOT_WAIT_MS = 800L

        /** How long the session stays alive after that, to hand a late screenshot over. */
        const val LATE_SCREENSHOT_GRACE_MS = 3_000L

        /** Gives up when a requested re-show does not arrive. */
        const val RESHOW_TIMEOUT_MS = 2_000L
    }
}
