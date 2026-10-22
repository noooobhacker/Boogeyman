package com.boogeyman.app

import android.app.Application
import android.util.Log
import java.io.File

// Saves any crash to a file so the next launch can show it (helps finding the cause)
class BoogeyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText(Log.getStackTraceString(e).take(4000)) } catch (_: Exception) {}
            old?.uncaughtException(t, e)
        }
    }
}
