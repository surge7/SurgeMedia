package com.surgemusic.app.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.surgemusic.app.AppContainer
import com.surgemusic.app.R
import com.surgemusic.core.downloads.DownloadItem
import com.surgemusic.core.downloads.DownloadState
import com.surgemusic.core.model.SizeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service keeping active downloads alive (plan Task 7).
 *
 * [onStartCommand] calls `startForeground` FIRST (the 5-second rule) — before anything that
 * could fail — then the service observes `AppContainer.downloadManager.items` and mirrors it
 * into one ongoing notification (title, "N active · speed/s · M queued", indeterminate
 * progress, single "Pause all" action routed back to this service as [ACTION_PAUSE_ALL]).
 * It stops itself (with `stopForeground`) once nothing is QUEUED/DOWNLOADING/FINALIZING/PAUSED.
 *
 * dataSync quota (Android 15: 6h per 24h window, see [DataSyncQuota]): the service feeds
 * its wall time to `AppContainer.dataSyncQuota` ([DataSyncQuota.onServiceStarted] in
 * [onStartCommand], [DataSyncQuota.onServiceStopped] in [onDestroy]) and re-checks the
 * budget every minute on the same scope — when only the 15-minute margin is left it
 * pauses the queue, posts a quota-paused notice and stops itself BEFORE the system
 * timeout escalates to a force-stop ([onTimeout] stays as the backstop for a lost
 * segment / process death).
 */
class DownloadService : Service() {

    // Default dispatcher: touching `AppContainer.downloadManager` lazily boots the native
    // torrent session (JLibTorrentEngine) — that must never run on the main thread
    // (onStartCommand is Main; a native session start there freezes the UI at every
    // app resume). NotificationManager.notify is thread-safe off-main.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observer: Job? = null
    private var quotaWatch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // START_STICKY may resurrect the service in a fresh process where no Activity has run;
        // AppContainer must be usable on its own.
        AppContainer.appContext = applicationContext
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 5-second rule: go foreground immediately, before anything else can fail.
        // The initial notification is built from an EMPTY item list on purpose — reading
        // the real list would synchronously boot the torrent engine on Main (see scope).
        // The observer below replaces it with live numbers within one emission.
        // 3-arg overload (API 29+) pins the manifest-declared `dataSync` type explicitly
        // (required shape on targetSdk 34+).
        startForeground(
            NOTIFICATION_ID,
            buildNotification(emptyList()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        AppContainer.dataSyncQuota.onServiceStarted()
        observeItems()
        observeQuota()
        if (intent?.action == ACTION_PAUSE_ALL) {
            scope.launch { AppContainer.downloadManager.pauseAll() }
        }
        return START_STICKY
    }

    /**
     * Android 15 (API 35) enforces a 6-hours-per-24 window quota on `dataSync` foreground
     * services and calls [onTimeout] when it is exhausted — without an override the system
     * force-stops the app (ANR-equivalent). Pause everything (same handling as
     * ACTION_PAUSE_ALL; queue items keep their PAUSED state), drop the notification and
     * stop before the system escalates. API 36 additionally dispatches the two-arg
     * overload — one shared body so exactly one of them runs per timeout on every level.
     */
    override fun onTimeout(startId: Int) {
        handleTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout()
    }

    private fun handleTimeout() {
        scope.launch { AppContainer.downloadManager.pauseAll() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Close the quota segment on every teardown path (quota pause, idle stop,
        // onTimeout, system kill with onDestroy delivered) so only real FGS wall time
        // counts against the 6h budget.
        AppContainer.dataSyncQuota.onServiceStopped()
        scope.cancel()
        super.onDestroy()
    }

    private fun observeItems() {
        if (observer?.isActive == true) return // onStartCommand can fire repeatedly; one collector
        observer = scope.launch {
            AppContainer.downloadManager.items.collect { items ->
                if (items.none { it.state in ACTIVE_STATES }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(items))
                }
            }
        }
    }

    /**
     * One quota watcher for the service's lifetime (same re-entry guard as
     * [observeItems]): every [QUOTA_CHECK_INTERVAL_MS] on the service scope, stop
     * gracefully once only the 15-minute margin of the dataSync budget is left —
     * the system must never be the one to kill us (mid-write finalization included).
     */
    private fun observeQuota() {
        if (quotaWatch?.isActive == true) return
        quotaWatch = scope.launch {
            while (isActive) {
                if (AppContainer.dataSyncQuota.shouldPause()) {
                    pauseForQuota()
                    return@launch
                }
                delay(QUOTA_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Budget spent down to the margin: pause everything (the ids land in
     * AppContainer's quota-paused set so MainActivity.onResume resumes them —
     * foreground app time consumes no dataSync quota), leave a dismissible notice
     * with the localized reason (its own notification id — [stopForeground] below
     * removes only the ongoing FGS one), then go away before the 6h timeout.
     */
    private fun pauseForQuota() {
        AppContainer.pauseForQuota()
        getSystemService(NotificationManager::class.java)
            ?.notify(QUOTA_NOTIFICATION_ID, buildQuotaNotification())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The quota notice: same channel/icon style as the ongoing one, quota text as content. */
    private fun buildQuotaNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_downloads)
            .setContentTitle(getString(R.string.notification_title_downloads))
            .setContentText(getString(R.string.download_paused_quota))
            .setOnlyAlertOnce(true)
            .build()

    private fun buildNotification(items: List<DownloadItem>): Notification {
        val downloading = items.filter { it.state == DownloadState.DOWNLOADING }
        val text = listOf(
            getString(R.string.notification_active_count, downloading.size),
            getString(
                R.string.notification_speed_per_second,
                SizeParser.formatBytes(downloading.sumOf { it.downloadSpeed }),
            ),
            getString(R.string.notification_queued_count, items.count { it.state == DownloadState.QUEUED }),
        ).joinToString(" · ")
        val pauseAllIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_downloads)
            .setContentTitle(getString(R.string.notification_title_downloads))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true) // indeterminate per plan
            .addAction(0, getString(R.string.notification_action_pause_all), pauseAllIntent)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1

        /** Quota-paused notice: separate id so stopForeground(REMOVE) keeps it visible. */
        const val QUOTA_NOTIFICATION_ID = 2
        const val QUOTA_CHECK_INTERVAL_MS = 60_000L
        const val ACTION_PAUSE_ALL = "com.surgemusic.app.downloads.PAUSE_ALL"
        val ACTIVE_STATES = setOf(
            DownloadState.QUEUED,
            DownloadState.DOWNLOADING,
            DownloadState.FINALIZING,
            DownloadState.PAUSED,
        )
    }
}
