package com.bluetoothcursor.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * CursorAccessibilityService
 *
 * Runs as an Android Accessibility Service (must be enabled by the user in system
 * Accessibility Settings). Receives LocalBroadcast intents from BluetoothServerService
 * and injects GestureDescription taps whenever a click event arrives.
 *
 * This service is NOT responsible for moving the cursor overlay — that is handled
 * by CursorOverlayManager inside BluetoothServerService.
 */
class CursorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CursorA11yService"
        const val ACTION_INJECT_TAP = "com.bluetoothcursor.bridge.INJECT_TAP"
        const val EXTRA_TAP_X = "tap_x"
        const val EXTRA_TAP_Y = "tap_y"

        /** Singleton reference so BluetoothServerService can call it directly. */
        @Volatile
        var instance: CursorAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val tapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_INJECT_TAP) {
                val x = intent.getFloatExtra(EXTRA_TAP_X, 0f)
                val y = intent.getFloatExtra(EXTRA_TAP_Y, 0f)
                performTap(x, y)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected — gesture injection ready.")

        val filter = IntentFilter(ACTION_INJECT_TAP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // RECEIVER_NOT_EXPORTED requires API 33+
            registerReceiver(tapReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(tapReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }

    override fun onInterrupt() {
        Log.w(TAG, "Interrupted")
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(tapReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Gesture injection ─────────────────────────────────────────────────────

    /**
     * Inject a tap (down + up) at the given screen coordinates.
     * Must be called on main thread (or via Handler).
     */
    fun performTap(x: Float, y: Float) {
        val canGesture = serviceInfo?.capabilities?.and(
            android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES
        ) != 0
        if (!canGesture) {
            Log.e(TAG, "canPerformGestures is false — cannot inject gestures.")
            return
        }

        mainHandler.post {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(
                path,
                /* startTime= */ 0L,
                /* duration= */ 50L   // 50 ms tap duration
            )
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.d(TAG, "Tap at ($x, $y) completed.")
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "Tap at ($x, $y) cancelled.")
                }
            }, null)
        }
    }
}
