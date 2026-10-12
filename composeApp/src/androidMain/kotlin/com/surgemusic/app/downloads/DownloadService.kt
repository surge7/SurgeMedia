package com.surgemusic.app.downloads

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Stub so the manifest's `<service>` entry compiles in the scaffold task.
 * Completed in Task 7: startForeground + notification, service stops itself when idle.
 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
