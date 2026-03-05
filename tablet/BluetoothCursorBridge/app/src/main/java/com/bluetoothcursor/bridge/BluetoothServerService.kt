package com.bluetoothcursor.bridge

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.util.UUID

/**
 * BluetoothServerService
 *
 * Foreground service that:
 *  1. Opens a Bluetooth RFCOMM server socket (SPP UUID)
 *  2. Accepts exactly ONE connection from the laptop bridge script
 *  3. Reads 7-byte packets in a loop and dispatches actions:
 *       - movement → CursorOverlayManager.moveCursor(dx, dy)
 *       - click     → CursorAccessibilityService.performTap(x, y)
 *
 * Packet format (7 bytes):
 *   [0xAB | dx_hi | dx_lo | dy_hi | dy_lo | click_state | xor_checksum]
 */
class BluetoothServerService : Service() {

    companion object {
        private const val TAG = "BTServerService"
        private const val NOTIF_CHANNEL_ID  = "BtCursorChannel"
        private const val NOTIF_ID          = 1
        private const val PACKET_SIZE       = 7
        private const val HEADER            = 0xAB.toByte()
        private const val CLICK_NONE        = 0x00.toByte()
        private const val CLICK_DOWN        = 0x01.toByte()
        private const val CLICK_UP          = 0x02.toByte()

        // Standard Serial Port Profile UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val ACTION_STATUS = "com.bluetoothcursor.bridge.STATUS"
        const val EXTRA_STATUS  = "status"
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var readerThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var cursorOverlay: CursorOverlayManager

    // Track click state: only fire tap on UP following a DOWN
    private var isClickDown = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        cursorOverlay = CursorOverlayManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Waiting for laptop connection…"))
        acquireWakeLock()

        // Check if overlay permission granted
        if (!android.provider.Settings.canDrawOverlays(this)) {
            updateNotification("⚠ Overlay permission not granted!")
            stopSelf()
            return START_NOT_STICKY
        }

        // Check Bluetooth
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            updateNotification("⚠ Bluetooth is off!")
            stopSelf()
            return START_NOT_STICKY
        }

        startListening(adapter)
        return START_STICKY
    }

    // ── Bluetooth server ──────────────────────────────────────────────────────

    private fun startListening(adapter: BluetoothAdapter) {
        readerThread = Thread {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("BluetoothCursorBridge", SPP_UUID)
                Log.i(TAG, "Listening for RFCOMM connection…")
                updateNotification("Waiting for laptop…")

                val socket = serverSocket!!.accept()   // blocks until laptop connects
                clientSocket = socket
                serverSocket?.close()                  // only one connection needed
                serverSocket = null

                Log.i(TAG, "Laptop connected: ${socket.remoteDevice.address}")
                updateNotification("Connected ✓ — ${socket.remoteDevice.address}")

                cursorOverlay.create()
                readLoop(socket.inputStream)

            } catch (e: Exception) {
                Log.e(TAG, "Server error: $e")
                updateNotification("❌ Error: ${e.message}")
            } finally {
                cursorOverlay.destroy()
                clientSocket?.close()
                updateNotification("Disconnected.")
                stopSelf()
            }
        }.also { it.start() }
    }

    // ── Packet reading loop ───────────────────────────────────────────────────

    private fun readLoop(stream: InputStream) {
        val buf = ByteArray(PACKET_SIZE)
        Log.i(TAG, "Packet read loop started.")

        while (!Thread.currentThread().isInterrupted) {
            try {
                // Read exactly PACKET_SIZE bytes
                var bytesRead = 0
                while (bytesRead < PACKET_SIZE) {
                    val n = stream.read(buf, bytesRead, PACKET_SIZE - bytesRead)
                    if (n == -1) {
                        Log.w(TAG, "Stream closed by laptop.")
                        return
                    }
                    bytesRead += n
                }

                processPacket(buf)

            } catch (e: Exception) {
                Log.e(TAG, "Read error: $e")
                return
            }
        }
    }

    private fun processPacket(buf: ByteArray) {
        // Validate header
        if (buf[0] != HEADER) {
            Log.w(TAG, "Bad header: 0x${buf[0].toInt().and(0xFF).toString(16)}")
            return
        }

        // Validate XOR checksum
        var xor = 0
        for (i in 0 until 6) xor = xor xor buf[i].toInt().and(0xFF)
        if (xor != buf[6].toInt().and(0xFF)) {
            Log.w(TAG, "Checksum mismatch — packet dropped.")
            return
        }

        // Decode signed 16-bit dx, dy (big-endian)
        val dx = (buf[1].toInt().and(0xFF) shl 8 or buf[2].toInt().and(0xFF)).let {
            if (it >= 0x8000) it - 0x10000 else it
        }
        val dy = (buf[3].toInt().and(0xFF) shl 8 or buf[4].toInt().and(0xFF)).let {
            if (it >= 0x8000) it - 0x10000 else it
        }
        val clickState = buf[5]

        // Move cursor overlay
        if (dx != 0 || dy != 0) {
            cursorOverlay.moveCursor(dx, dy)
        }

        // Inject tap on click-UP (after a click-DOWN)
        when (clickState) {
            CLICK_DOWN -> isClickDown = true
            CLICK_UP   -> {
                if (isClickDown) {
                    isClickDown = false
                    val service = CursorAccessibilityService.instance
                    if (service != null) {
                        service.performTap(cursorOverlay.cursorX, cursorOverlay.cursorY)
                    } else {
                        Log.w(TAG, "Accessibility Service not connected — tap dropped.")
                    }
                }
            }
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Bluetooth Cursor Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows connection status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Bluetooth Cursor Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::WakeLock")
            .also { it.acquire(10 * 60 * 1000L) }   // max 10 min, refreshed by packets
    }

    override fun onDestroy() {
        readerThread?.interrupt()
        clientSocket?.close()
        serverSocket?.close()
        cursorOverlay.destroy()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
