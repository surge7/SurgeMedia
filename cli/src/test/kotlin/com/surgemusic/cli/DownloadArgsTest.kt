package com.surgemusic.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `download` argument rules — [parseDownloadArgs] is the pure extraction from cmdDownload.
 * The usage texts are user-facing behavior, so they are asserted verbatim.
 */
class DownloadArgsTest {
    private val topicIdUsage = "download requires a numeric topicId"
    private val timeoutUsage = "--timeout-minutes must be a positive number of minutes"

    @Test fun minimalArgs_useDefaults() {
        assertEquals(DownloadParse.Ok(123L, null, 30L), parseDownloadArgs(listOf("123")))
    }

    @Test fun outAndTimeoutArePickedUp() {
        assertEquals(
            DownloadParse.Ok(7L, "/tmp/torrents", 45L),
            parseDownloadArgs(listOf("7", "--out", "/tmp/torrents", "--timeout-minutes", "45")),
        )
    }

    @Test fun missingTopicId_isUsageError() {
        assertEquals(DownloadParse.Err(topicIdUsage), parseDownloadArgs(emptyList()))
    }

    @Test fun nonNumericTopicId_isUsageError() {
        assertEquals(DownloadParse.Err(topicIdUsage), parseDownloadArgs(listOf("abc")))
    }

    @Test fun nonNumericTimeout_isUsageError() {
        assertEquals(DownloadParse.Err(timeoutUsage), parseDownloadArgs(listOf("9", "--timeout-minutes", "abc")))
    }

    @Test fun zeroTimeout_isUsageError() {
        assertEquals(DownloadParse.Err(timeoutUsage), parseDownloadArgs(listOf("9", "--timeout-minutes", "0")))
    }

    @Test fun negativeTimeout_isUsageError() {
        assertEquals(DownloadParse.Err(timeoutUsage), parseDownloadArgs(listOf("9", "--timeout-minutes", "-5")))
    }

    @Test fun timeoutOfOneMinute_isAccepted() {
        assertEquals(DownloadParse.Ok(9L, null, 1L), parseDownloadArgs(listOf("9", "--timeout-minutes", "1")))
    }

    @Test fun timeoutFlagWithoutValue_fallsBackToDefault() {
        // option() returns null when the flag is last, so the default applies — same as --out.
        assertEquals(DownloadParse.Ok(9L, null, 30L), parseDownloadArgs(listOf("9", "--timeout-minutes")))
    }
}
