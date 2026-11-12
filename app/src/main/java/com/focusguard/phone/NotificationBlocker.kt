package com.focusguard.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** During a session, removes notifications of social media / messenger apps (Apps.BLOCKED). */
class NotificationBlocker : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        sweep()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldBlock(sbn)) cancelNotification(sbn.key)
    }

    private fun shouldBlock(sbn: StatusBarNotification): Boolean =
        Session.isActive(this) && sbn.packageName in Apps.BLOCKED

    /** Clears blocked notifications that arrived before the session started. */
    fun sweep() {
        runCatching {
            activeNotifications?.filter { shouldBlock(it) }?.forEach { cancelNotification(it.key) }
        }
    }

    companion object {
        @Volatile
        var instance: NotificationBlocker? = null
    }
}
