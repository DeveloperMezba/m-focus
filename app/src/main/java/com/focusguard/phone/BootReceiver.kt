package com.focusguard.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** After a restart in the middle of a session: bring the keep-alive notification back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Session.isActive(c)) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, KeepAliveService::class.java)) }
        }
    }
}
