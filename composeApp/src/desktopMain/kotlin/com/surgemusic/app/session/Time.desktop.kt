package com.surgemusic.app.session

import java.text.DateFormat
import java.util.Date

// Desktop twin of Time.android.kt (same JDK APIs); keep the two in sync.
internal actual fun nowEpochMs(): Long = System.currentTimeMillis()

internal actual fun formatTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
