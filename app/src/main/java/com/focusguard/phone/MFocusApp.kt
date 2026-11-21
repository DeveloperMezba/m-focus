package com.focusguard.phone

import android.app.Application

class MFocusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Diag.logCrash(this, e) }
            old?.uncaughtException(t, e)
        }
    }
}
