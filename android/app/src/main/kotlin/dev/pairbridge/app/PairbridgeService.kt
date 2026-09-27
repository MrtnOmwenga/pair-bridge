package dev.pairbridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/** Foreground service that keeps [TabletFileServer] running while tablet sharing is on. */
class PairbridgeService : Service() {

    private var server: TabletFileServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startFileServer()
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startFileServer() {
        if (server != null) return
        if (!hasAllFilesAccess()) {
            Log.w(TAG, "not starting tablet file server: All files access not granted")
            return
        }
        val token = LaptopConnection.get(this).credentials.token
        if (token == null) {
            Log.w(TAG, "not starting tablet file server: not paired yet")
            return
        }
        try {
            val newServer = TabletFileServer(TabletFileServer.PORT, token)
            newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = newServer
            Log.i(TAG, "tablet file server listening on port ${TabletFileServer.PORT}")
        } catch (e: IOException) {
            Log.w(TAG, "failed to start tablet file server: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val credentialStore = LaptopConnection.get(this).credentials
        val text = when {
            !credentialStore.isPaired -> "Not paired"
            server != null -> "Connected to ${credentialStore.host} • sharing tablet files"
            else -> "Connected to ${credentialStore.host}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pairbridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pairbridge connection",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "Pairbridge"
        private const val CHANNEL_ID = "pairbridge_service"
        private const val NOTIFICATION_ID = 1
    }
}

/** "All files access" (Android 11+) is what lets [TabletFileServer] read the tablet's public folders. */
fun hasAllFilesAccess() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
