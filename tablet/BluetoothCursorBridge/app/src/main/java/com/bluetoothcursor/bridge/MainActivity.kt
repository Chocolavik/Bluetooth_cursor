package com.bluetoothcursor.bridge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity
 *
 * Entry point UI. Guides the user through the two manual setup steps:
 *  1. Grant SYSTEM_ALERT_WINDOW (overlay) permission
 *  2. Enable the Accessibility Service
 *
 * Then starts [BluetoothServerService] as a foreground service.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText: TextView = findViewById(R.id.tv_status)
        val btnOverlay: Button   = findViewById(R.id.btn_overlay_permission)
        val btnA11y: Button      = findViewById(R.id.btn_accessibility)
        val btnStart: Button     = findViewById(R.id.btn_start_server)

        // ── Button: grant overlay permission ────────────────────────────────
        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                statusText.text = "✓ Overlay permission already granted."
            }
        }

        // ── Button: open Accessibility Settings ─────────────────────────────
        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ── Button: start Bluetooth server ───────────────────────────────────
        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                statusText.text = "⚠ Please grant overlay permission first."
                return@setOnClickListener
            }
            if (CursorAccessibilityService.instance == null) {
                statusText.text = "⚠ Please enable the Accessibility Service first."
                return@setOnClickListener
            }
            val svcIntent = Intent(this, BluetoothServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
            statusText.text = "Server started — waiting for laptop…\nCheck the notification for status."
            btnStart.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        // Update button states when user returns from Settings
        val statusText: TextView = findViewById(R.id.tv_status)
        val btnOverlay: Button   = findViewById(R.id.btn_overlay_permission)
        val btnStart: Button     = findViewById(R.id.btn_start_server)

        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk    = CursorAccessibilityService.instance != null

        btnOverlay.text = if (overlayOk) "✓ Overlay Permission Granted" else "1. Grant Overlay Permission"
        btnOverlay.isEnabled = !overlayOk

        val steps = buildString {
            if (!overlayOk) appendLine("⬜ Step 1: Grant overlay permission (tap button above)")
            else            appendLine("✅ Step 1: Overlay permission granted")
            if (!a11yOk)   appendLine("⬜ Step 2: Enable 'Bluetooth Cursor Bridge' in Accessibility Settings")
            else            appendLine("✅ Step 2: Accessibility Service active")
            appendLine()
            if (overlayOk && a11yOk) appendLine("All good — tap START SERVER ↓")
        }
        statusText.text = steps

        btnStart.isEnabled = overlayOk && a11yOk
    }
}
