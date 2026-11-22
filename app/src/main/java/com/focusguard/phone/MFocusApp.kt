package com.focusguard.phone

import android.app.Application
import android.os.Handler
import android.os.Looper

class MFocusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Diag.logCrash(this, e) }
            old?.uncaughtException(t, e)
        }
        // While a focus session is running, an unexpected error in a service, receiver or timer
        // must not take the whole app (and the protection) down. It is logged and the app carries on.
        // Outside a session nothing is swallowed, so real bugs still show up.
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    return@post
                } catch (t: Throwable) {
                    runCatching { Diag.logCrash(this, t) }
                    val keepRunning = runCatching { Session.isActive(this) }.getOrDefault(false)
                    if (!keepRunning) {
                        old?.uncaughtException(Thread.currentThread(), t)
                        return@post
                    }
                }
            }
        }
    }
}
