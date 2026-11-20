package app.dpadmouse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.dpadmouse.Strings

/**
 * Foreground service that runs only while mouse mode is on. Without it Android demotes the process to the
 * background a few seconds after the app is left (limited CPU), which makes the cursor stutter.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, Strings.mouseOnNotification, NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setContentText(Strings.mouseOnNotification)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "mouse_on"
        const val ID = 1
    }
}
