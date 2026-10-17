package com.surgemusic.core.engine.jvm

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.URLBuilder
import java.net.InetAddress
import java.util.concurrent.ThreadLocalRandom

/**
 * HTTP tracker announce shim (VPN survival).
 *
 * libtorrent's native resolver/route enumeration breaks under Android per-app VPNs
 * (tun0): `enum_route` fails, native getaddrinfo returns "Host not found", and every
 * tracker announce is skipped as unreachable — observed on a real device where the
 * Java/OkHttp path through the same VPN works fine (rutracker.org search + dl.php).
 * TCP connect through the tunnel works; only libtorrent's own DNS does not.
 *
 * So the announce itself is done over the working HTTP stack, and the returned peers
 * are injected with connect_peer — libtorrent needs no DNS for bare IP endpoints.
 * Rutracker's bt*.t-ru.org trackers sit behind Cloudflare and require the correct
 * Host header, so rewriting the tracker URL to an IP is NOT an option; the announce
 * must be a real HTTP GET to the original URL.
 */
internal class TrackerAnnouncer {

    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    /** One announce for [announceUrl]; returns discovered peers, or null on any failure. */
    suspend fun announceOnce(
        announceUrl: String,
        infoHashHex: String,
        listenPort: Int,
        leftBytes: Long,
        started: Boolean,
    ): List<Pair<String, Int>>? = runCatching {
        val url = URLBuilder(announceUrl).apply {
            parameters.append("info_hash", percentEncodeRawBytes(hexToBytes(infoHashHex)))
            parameters.append("peer_id", peerId())
            parameters.append("port", listenPort.coerceAtLeast(1).toString())
            parameters.append("uploaded", "0")
            parameters.append("downloaded", "0")
            parameters.append("left", leftBytes.coerceAtLeast(0).toString())
            parameters.append("compact", "1")
            parameters.append("numwant", "50")
            if (started) parameters.append("event", "started")
        }.build()
        parsePeers(http.get(url).bodyAsBytes())
    }.getOrNull()

    companion object {
        private const val PEER_ID_PREFIX = "-SM2010-"

        /** Random Azureus-style peer id (20 chars). */
        internal fun peerId(): String {
            val tail = StringBuilder()
            repeat(12) { tail.append(('a' + ThreadLocalRandom.current().nextInt(26))) }
            return PEER_ID_PREFIX + tail
        }

        internal fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        /** Trackers expect the RAW 20 info-hash bytes percent-encoded (%XX), not form encoding. */
        internal fun percentEncodeRawBytes(bytes: ByteArray): String =
            buildString { bytes.forEach { b -> append('%'); append("%02X".format(b.toInt() and 0xFF)) } }

        /**
         * Minimal bencode scrape of the peers fields: compact IPv4 (`5:peers<len>:` +
         * 6-byte groups), compact IPv6 (`6:peers6<len>:` + 18-byte groups) and the
         * non-compact dict list (`2:ip<len>:<ip> … 4:porti<n>e`). Failure responses
         * simply parse to an empty list.
         */
        internal fun parsePeers(body: ByteArray): List<Pair<String, Int>> {
            val peers = mutableListOf<Pair<String, Int>>()
            findStringAfter(body, "6:peers6")?.let { v6 ->
                var i = 0
                while (i + 18 <= v6.size) {
                    val addr = v6.copyOfRange(i, i + 16)
                    val port = ((v6[i + 16].toInt() and 0xFF) shl 8) or (v6[i + 17].toInt() and 0xFF)
                    if (port > 0) runCatching { peers += InetAddress.getByAddress(addr).hostAddress to port }
                    i += 18
                }
                return peers
            }
            findStringAfter(body, "5:peers")?.let { v4 ->
                var i = 0
                while (i + 6 <= v4.size) {
                    val ip = "${v4[i].toInt() and 0xFF}.${v4[i + 1].toInt() and 0xFF}." +
                        "${v4[i + 2].toInt() and 0xFF}.${v4[i + 3].toInt() and 0xFF}"
                    val port = ((v4[i + 4].toInt() and 0xFF) shl 8) or (v4[i + 5].toInt() and 0xFF)
                    if (port > 0) peers += ip to port
                    i += 6
                }
                return peers
            }
            // Non-compact dict list: `5:peers` followed by 'l' with per-peer dictionaries.
            val text = String(body, Charsets.ISO_8859_1)
            val peersIdx = text.indexOf("5:peers")
            if (peersIdx < 0) return peers
            var cursor = peersIdx
            while (peers.size < 200) {
                val ipMatch = Regex("2:ip(\\d+):").find(text, cursor) ?: break
                val ipStart = ipMatch.range.last + 1
                val ipLen = ipMatch.groupValues[1].toInt()
                if (ipStart + ipLen > text.length) break
                val ip = text.substring(ipStart, ipStart + ipLen)
                val portMatch = Regex("4:porti(\\d+)e").find(text, ipStart + ipLen) ?: break
                val port = portMatch.groupValues[1].toIntOrNull() ?: break
                if (port in 1..65535) peers += ip to port
                cursor = portMatch.range.last + 1
            }
            return peers
        }

        /** Bytes of the string declared right after [key] (`key<len>:`), or null if absent. */
        private fun findStringAfter(body: ByteArray, key: String): ByteArray? {
            val text = String(body, Charsets.ISO_8859_1)
            val idx = text.indexOf(key)
            if (idx < 0) return null
            val at = idx + key.length
            // The `<len>:` declaration must start EXACTLY at [at] — a plain find(text, at)
            // with an anchored `^` never matches past index 0, and an unanchored one would
            // happily grab a later `N:` from inside the payload, so verify the position.
            val lenMatch = Regex("\\d+:").find(text, at) ?: return null
            if (lenMatch.range.first != at) return null
            val len = lenMatch.value.dropLast(1).toIntOrNull() ?: return null
            val start = lenMatch.range.last + 1
            if (len < 0 || start + len > body.size) return ByteArray(0)
            return body.copyOfRange(start, start + len)
        }
    }
}
