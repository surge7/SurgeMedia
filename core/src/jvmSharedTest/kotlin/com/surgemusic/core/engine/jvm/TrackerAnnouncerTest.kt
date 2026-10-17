package com.surgemusic.core.engine.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure parsing/formatting halves of the tracker shim (no HTTP in unit tests; the
 * announce path itself is exercised live on-device through the VPN scenario it
 * exists for).
 */
class TrackerAnnouncerTest {

    @Test
    fun compactV4PeersAreParsed() {
        val raw = "d8:completei5e10:incompletei2e5:peers12:".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(1, 2, 3, 4, 0x1A, 0xE1.toByte(), 5, 6, 7, 8, 0x04, 0xD2.toByte()) +
            "e".toByteArray(Charsets.ISO_8859_1)
        assertEquals(listOf("1.2.3.4" to 6881, "5.6.7.8" to 1234), TrackerAnnouncer.parsePeers(raw))
    }

    @Test
    fun failureReasonYieldsNoPeers() {
        val raw = "d14:failure reason15:tracker is downe".toByteArray(Charsets.ISO_8859_1)
        assertEquals(emptyList(), TrackerAnnouncer.parsePeers(raw))
    }

    @Test
    fun nonCompactDictListIsParsed() {
        val raw = ("d5:peersl" +
            "d2:ip9:127.0.0.17:peer id20:aaaaaaaaaaaaaaaaaaaa4:porti6881ee" +
            "d2:ip13:192.168.1.1007:peer id20:bbbbbbbbbbbbbbbbbbbb4:porti12345ee" +
            "ee").toByteArray(Charsets.ISO_8859_1)
        assertEquals(
            listOf("127.0.0.1" to 6881, "192.168.1.100" to 12345),
            TrackerAnnouncer.parsePeers(raw),
        )
    }

    @Test
    fun truncatedCompactPeersAreIgnoredNotCrashing() {
        val raw = "d5:peers4:".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(1, 2, 3, 4) + "e".toByteArray(Charsets.ISO_8859_1)
        assertEquals(emptyList(), TrackerAnnouncer.parsePeers(raw))
    }

    @Test
    fun infoHashBytesArePercentEncodedAsRawHex() {
        val bytes = byteArrayOf(0x00, 0x0F, 0xA5.toByte(), 0xFF.toByte(), 0x10)
        assertEquals("%00%0F%A5%FF%10", TrackerAnnouncer.percentEncodeRawBytes(bytes))
        assertEquals(bytes.toList(), TrackerAnnouncer.hexToBytes("000FA5FF10").toList())
    }

    @Test
    fun peerIdIsTwentyChars() {
        assertEquals(20, TrackerAnnouncer.peerId().length)
        assertTrue(TrackerAnnouncer.peerId().startsWith("-SM2010-"))
    }
}
