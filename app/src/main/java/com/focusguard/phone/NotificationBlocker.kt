package com.focusguard.phone

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** During a session, removes notifications of social media / messenger apps (Apps.BLOCKED). */
class NotificationBlocker : NotificationListenerService() {

    private var callCheckAt = 0L
    private var callCheckResult = false

    override fun onListenerConnected() {
        instance = this
        sweep()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldBlock(sbn)) cancelNotification(sbn.key)
        if (isCallNotification(sbn)) GuardService.instance?.onCallNotification()
    }

    private fun isCallNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (sbn.packageName in Apps.BLOCKED || sbn.packageName in Apps.AI_ALL) return false
        if (n.category == Notification.CATEGORY_CALL) return true
        return (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 &&
            Apps.isCallUi(this, sbn.packageName, "")
    }

    private fun shouldBlock(sbn: StatusBarNotification): Boolean =
        Session.isActive(this) && !Session.phase(this).onBreak && sbn.packageName in Apps.BLOCKED

    /** Clears blocked notifications that arrived before the session started. */
    fun sweep() {
        runCatching {
            activeNotifications?.filter { shouldBlock(it) }?.forEach { cancelNotification(it.key) }
        }
    }

    /**
     * Tap intent of the ongoing-call notification. The focus screen uses it to bring a running
     * call back to the front, which is the one way that works without extra permissions.
     */
    fun callIntent(): PendingIntent? = callSbn()?.notification?.contentIntent

    /** True while a call notification (ringing / ongoing) is showing. */
    fun hasCallNotification(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - callCheckAt < 250) return callCheckResult
        callCheckResult = callSbn() != null
        callCheckAt = now
        return callCheckResult
    }

    /** Packages that post call notifications: the app that shows the in-call screen. */
    fun callPackages(): Set<String> = runCatching {
        activeNotifications.orEmpty()
            .filter { sbn ->
                val n = sbn.notification ?: return@filter false
                n.category == Notification.CATEGORY_CALL ||
                    ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 && Apps.isCallUi(this, sbn.packageName, ""))
            }
            .map { it.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    private fun callSbn(): StatusBarNotification? = runCatching {
        activeNotifications?.firstOrNull { sbn ->
            val n = sbn.notification ?: return@firstOrNull false
            if (n.category == Notification.CATEGORY_CALL) return@firstOrNull true
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 && Apps.isCallUi(this, sbn.packageName, "")
        }
    }.getOrNull()

    companion object {
        @Volatile
        var instance: NotificationBlocker? = null
    }
}
