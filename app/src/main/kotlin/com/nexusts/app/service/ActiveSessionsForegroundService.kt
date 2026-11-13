package com.nexusts.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nexusts.app.R

/**
 * Keeps active SSH tunnels, RDP sessions, and background SFTP transfers alive when NexusTS windows
 * are minimized across multiple Googlebook OS virtual desktops (Android 14/15 `specialUse` compliant).
 */
class ActiveSessionsForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        promoteToForeground(activeCount = 1)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 1) ?: 1
        if (activeCount <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        promoteToForeground(activeCount)
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 (API 35) foreground service timeout compliance
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground(activeCount: Int) {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("${getString(R.string.ssh_tunnels_active)} ($activeCount)")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val fgsType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            mgr?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "nexusts_active_sessions"
        private const val NOTIFICATION_ID = 4201
        private const val EXTRA_ACTIVE_COUNT = "extra_active_session_count"

        fun syncSessionKeepAlive(context: Context, activeSessionCount: Int) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, ActiveSessionsForegroundService::class.java).apply {
                putExtra(EXTRA_ACTIVE_COUNT, activeSessionCount)
            }
            runCatching {
                if (activeSessionCount > 0) {
                    ContextCompat.startForegroundService(appCtx, intent)
                } else {
                    appCtx.stopService(intent)
                }
            }
        }
    }
}
