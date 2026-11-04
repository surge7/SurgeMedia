package com.surgemusic.app.downloads

import com.surgemusic.app.settings.KeyValueStore

/**
 * Tracker of elapsed `dataSync` foreground-service time against Android 15's quota: a
 * `dataSync` FGS may run at most [QUOTA_MS] (6 h) per [WINDOW_MS] (24 h) rolling window;
 * overspending ends in [DownloadService.onTimeout] and a system force-stop — the queue
 * survives, but the user sees an unexplained death mid-download.
 *
 * Why not WorkManager (design rationale): WorkManager's long-running workers reserve the
 * very same FGS types and therefore the same 6h/24h quotas on Android 15, and expedited
 * work cannot legally run for hours — moving the download loop there changes nothing.
 * The real fix is to know the budget, stop BEFORE the system does
 * ([DownloadService] checks every minute and pauses itself at [PAUSE_THRESHOLD_MS]),
 * and let foreground app time (downloads need no FGS while the app is visible) plus
 * queue persistence carry downloads through the dead window until the 24h window rolls
 * over and the FGS can start again (AppContainer.startDownloadService gates on
 * [shouldPause]; MainActivity.onResume resumes the quota-paused items).
 *
 * Accounting: [onServiceStarted]/[onServiceStopped] bracket each FGS spell and
 * accumulate its wall time into `accumulatedForegroundMs`; both it and
 * `windowStartEpochMs` persist via the process-wide [KeyValueStore] (string-encoded —
 * the store has no long API) so the budget survives process death. A segment whose
 * process died without [onServiceStopped] is lost (under-counted);
 * [DownloadService.onTimeout] remains the system-side backstop for that case.
 */
class DataSyncQuota(private val store: KeyValueStore) {

    /** Settable clock so unit tests can freeze/advance time; production uses wall time. */
    var nowMs: () -> Long = System::currentTimeMillis

    private val lock = Any()

    /** True between [onServiceStarted] and [onServiceStopped] (one open segment at most). */
    private var running = false

    /** Wall time the open segment began (meaningless while `running == false`). */
    private var segmentStartMs = 0L

    /**
     * FGS came up (DownloadService.onStartCommand): opens an accumulation segment and
     * resets the rolling window first when `now − windowStart` has passed [WINDOW_MS].
     * Idempotent for repeated onStartCommand calls while the service stays up.
     */
    fun onServiceStarted() {
        synchronized(lock) {
            val now = nowMs()
            rollWindowIfNeeded(now)
            if (!running) {
                running = true
                segmentStartMs = now
            }
        }
    }

    /** FGS went down (DownloadService.onDestroy): closes the open segment, persists the sum. */
    fun onServiceStopped() {
        synchronized(lock) {
            if (!running) return
            running = false
            val accumulated = readAccumulated() + (nowMs() - segmentStartMs).coerceAtLeast(0L)
            store.putString(KEY_ACCUMULATED_MS, accumulated.toString())
        }
    }

    /**
     * Unspent budget: [QUOTA_MS] − accumulated (including the open segment), never
     * negative; a window older than [WINDOW_MS] counts as fully reset — full quota —
     * without writing (the reset persists on the next [onServiceStarted]).
     */
    fun remainingMs(): Long = synchronized(lock) {
        val now = nowMs()
        val windowStart = windowStartLocked()
        if (windowStart == null || now - windowStart > WINDOW_MS) {
            QUOTA_MS
        } else {
            (QUOTA_MS - elapsedLocked(now)).coerceAtLeast(0L)
        }
    }

    /**
     * Pause/gate decision: elapsed time has reached [PAUSE_THRESHOLD_MS] (6h − 15min),
     * i.e. at most a 15-minute margin is left before the system's own 6h timeout —
     * exactly where the graceful self-pause must happen. Used both by the service's
     * periodic check (stop the FGS in time) and the start gate
     * (AppContainer.startDownloadService withholds the FGS at the same point).
     */
    fun shouldPause(): Boolean = synchronized(lock) { elapsedLocked(nowMs()) >= PAUSE_THRESHOLD_MS }

    /** Accumulated dataSync time in the current window (open segment included). */
    private fun elapsedLocked(now: Long): Long {
        val windowStart = windowStartLocked()
        if (windowStart == null || now - windowStart > WINDOW_MS) return 0L
        val open = if (running) (now - segmentStartMs).coerceAtLeast(0L) else 0L
        return readAccumulated() + open
    }

    /**
     * Resets the rolling window (persisted) when it has aged out; called on service
     * start. A reset while a segment is open re-bases the segment at `now` — its
     * pre-reset time belonged to the expired window.
     */
    private fun rollWindowIfNeeded(now: Long) {
        val windowStart = windowStartLocked()
        if (windowStart == null || now - windowStart > WINDOW_MS) {
            store.putString(KEY_WINDOW_START_MS, now.toString())
            store.putString(KEY_ACCUMULATED_MS, "0")
            segmentStartMs = now
        }
    }

    private fun windowStartLocked(): Long? = store.getString(KEY_WINDOW_START_MS)?.toLongOrNull()

    private fun readAccumulated(): Long = store.getString(KEY_ACCUMULATED_MS)?.toLongOrNull() ?: 0L

    companion object {
        /** Android 15's dataSync FGS budget: 6 hours per 24-hour rolling window. */
        const val QUOTA_MS = 6L * 60 * 60 * 1000

        /** Rolling window length after which the budget is considered fully restored. */
        const val WINDOW_MS = 24L * 60 * 60 * 1000

        /**
         * Elapsed-time trigger for the graceful self-pause: 6h − 15min, so stopping
         * here always beats the system timeout by the 15-minute margin (long enough to
         * finish any in-flight write/finalize, short enough to never race the system).
         */
        const val PAUSE_THRESHOLD_MS = QUOTA_MS - 15L * 60 * 1000

        private const val KEY_WINDOW_START_MS = "data_sync_window_start_ms"
        private const val KEY_ACCUMULATED_MS = "data_sync_accumulated_ms"
    }
}
