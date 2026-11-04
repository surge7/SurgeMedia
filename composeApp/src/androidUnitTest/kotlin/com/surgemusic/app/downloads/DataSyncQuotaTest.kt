package com.surgemusic.app.downloads

import com.surgemusic.app.settings.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DataSyncQuota] against an in-memory [KeyValueStore] fake (Robolectric-free, like
 * [com.surgemusic.app.settings.SettingsStoreTest]): accumulation across FGS spells, the
 * 15-minute-margin pause threshold, the 24h rolling-window reset and persistence across
 * instances — all on a settable clock ([DataSyncQuota.nowMs]).
 *
 * The OPEN segment (`running`/segment start) lives on the instance — exactly what a
 * process death loses, per the class KDoc — so each spell steps one instance's clock;
 * closed state persists through the store and is re-checked via fresh instances.
 */
class DataSyncQuotaTest {

    private class InMemoryKeyValueStore : KeyValueStore {
        val strings = mutableMapOf<String, String>()

        override fun getString(key: String): String? = strings[key]
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun getBoolean(key: String, default: Boolean): Boolean = default
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun getInt(key: String, default: Int): Int = default
        override fun putInt(key: String, value: Int) = Unit
    }

    private val store = InMemoryKeyValueStore()

    /** Minute in ms, for readable offsets below. */
    private val min = 60_000L

    /** Quota pinned to the fixed instant [now]; later steps reassign [DataSyncQuota.nowMs]. */
    private fun quotaAt(now: Long): DataSyncQuota = DataSyncQuota(store).apply { nowMs = { now } }

    /** Fresh instance over the same store (process death / service recreation). */
    private fun reloadedAt(now: Long): DataSyncQuota = quotaAt(now)

    @Test
    fun emptyStoreStartsWithFullQuota() {
        assertEquals(DataSyncQuota.QUOTA_MS, quotaAt(0L).remainingMs())
        assertFalse(quotaAt(0L).shouldPause())
    }

    @Test
    fun serviceSpellAccumulatesWallTime() {
        val quota = quotaAt(0L)
        quota.onServiceStarted()

        // 30 minutes up: remaining reflects the open segment
        quota.nowMs = { 30 * min }
        assertEquals(DataSyncQuota.QUOTA_MS - 30 * min, quota.remainingMs())

        // stopped after 45 minutes: the closed segment persists
        quota.nowMs = { 45 * min }
        quota.onServiceStopped()
        assertEquals(
            DataSyncQuota.QUOTA_MS - 45 * min,
            reloadedAt(46 * min).remainingMs(),
        )
    }

    @Test
    fun repeatedStartDoesNotRestartTheSegment() {
        // onStartCommand can fire repeatedly while the service stays up; the segment
        // must continue from the original start, not re-base.
        val quota = quotaAt(0L)
        quota.onServiceStarted()
        quota.nowMs = { min }
        quota.onServiceStarted()
        quota.nowMs = { 2 * min }
        assertEquals(DataSyncQuota.QUOTA_MS - 2 * min, quota.remainingMs())
    }

    @Test
    fun secondSpellAddsToFirstAccumulation() {
        val quota = quotaAt(0L)
        quota.onServiceStarted()
        quota.nowMs = { 60 * min }
        quota.onServiceStopped() // 1h

        quota.nowMs = { 120 * min }
        quota.onServiceStarted() // still the same window: 2h < 24h
        quota.nowMs = { 150 * min }
        quota.onServiceStopped() // +30m → 1h30m total

        assertEquals(DataSyncQuota.QUOTA_MS - 90 * min, reloadedAt(151 * min).remainingMs())
    }

    @Test
    fun stopWithoutStartIsIgnored() {
        quotaAt(0L).onServiceStopped()
        assertEquals(DataSyncQuota.QUOTA_MS, quotaAt(0L).remainingMs())
    }

    @Test
    fun pauseThresholdHitsAtFiveHoursFortyFiveMinutesWithFifteenMinuteMargin() {
        val quota = quotaAt(0L)
        quota.onServiceStarted()

        // one minute before the threshold: still allowed
        quota.nowMs = { DataSyncQuota.PAUSE_THRESHOLD_MS - min }
        assertFalse(quota.shouldPause())

        // at the threshold: pause — and exactly the 15-minute margin is left
        quota.nowMs = { DataSyncQuota.PAUSE_THRESHOLD_MS }
        assertTrue(quota.shouldPause())
        assertEquals(15 * min, quota.remainingMs())
    }

    @Test
    fun remainingNeverGoesNegative() {
        val quota = quotaAt(0L)
        quota.onServiceStarted()
        quota.nowMs = { DataSyncQuota.QUOTA_MS + min } // overspent within the window

        assertEquals(0L, quota.remainingMs())
        assertTrue(quota.shouldPause())
    }

    @Test
    fun windowRollsOverAfterTwentyFourHours() {
        val quota = quotaAt(0L)
        quota.onServiceStarted()
        quota.nowMs = { 2 * 60 * min }
        quota.onServiceStopped() // 2h spent

        // 23h in: still inside the window — the 2h count
        quota.nowMs = { 23 * 60 * min }
        assertEquals(DataSyncQuota.QUOTA_MS - 2 * 60 * min, quota.remainingMs())

        // past 24h: budget fully restored (lazy read — no write needed)
        quota.nowMs = { 25 * 60 * min }
        assertEquals(DataSyncQuota.QUOTA_MS, quota.remainingMs())

        // the next service spell then accumulates from zero and persists the reset
        quota.onServiceStarted()
        quota.nowMs = { 25 * 60 * min + min }
        assertEquals(DataSyncQuota.QUOTA_MS - min, quota.remainingMs())
    }

    @Test
    fun accumulationSurvivesReloadAcrossInstances() {
        val quota = quotaAt(0L)
        quota.onServiceStarted()
        quota.nowMs = { 30 * min }
        quota.onServiceStopped()

        // same backing store, fresh instance sees the persisted closed segment
        assertEquals(DataSyncQuota.QUOTA_MS - 30 * min, reloadedAt(31 * min).remainingMs())
    }

    @Test
    fun openSegmentIsLostOnReloadByDesign() {
        // Documented trade-off: a process death without onDestroy loses only the open
        // segment (under-count); DownloadService.onTimeout stays the system backstop.
        val quota = quotaAt(0L)
        quota.onServiceStarted()
        quota.nowMs = { 30 * min }

        assertEquals(DataSyncQuota.QUOTA_MS, reloadedAt(30 * min).remainingMs())
    }

    @Test
    fun corruptPersistedValuesFallBackToCleanStart() {
        store.strings["data_sync_window_start_ms"] = "not-a-number"
        store.strings["data_sync_accumulated_ms"] = "also-not"

        assertEquals(DataSyncQuota.QUOTA_MS, quotaAt(0L).remainingMs())
    }
}
