package com.focusguard.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * While a session runs: an ongoing notification ("M Focus, focus session running" with a countdown).
 * Phones kill background apps when the screen is off; a foreground service makes that much less likely.
 */
class KeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = Session.isActive(this)
        // startForeground() must come first, even when there is nothing to do: the caller used
        // startForegroundService(), so stopping before promoting to foreground crashes the app.
        runCatching {
            val n = notification(active)
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        }
        if (!active) {
            Session.complete(this)       // session ended while nobody was watching: save the minutes
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, Session.remainingMs(this) + 2000)
        return START_STICKY
    }

    private fun notification(active: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("session", "Focus session", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "session") else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("M Focus")
            .setContentText(if (active) "Focus session running" else "Finishing session")
            .setOngoing(true)
        if (active) b.setShowWhen(true).setWhen(Session.endAt(this)).setUsesChronometer(true).setChronometerCountDown(true)
        return b.build()
    }

    override fun onDestroy() {
        if (Session.endAt(this) > 0) Session.complete(this)   // never lose a finished session
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
