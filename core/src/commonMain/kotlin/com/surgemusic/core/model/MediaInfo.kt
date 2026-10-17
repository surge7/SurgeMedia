package com.surgemusic.core.model

/**
 * Release format kind parsed from a rutracker topic title / forum name.
 *
 * @see MediaInfo
 */
enum class MediaKind { AUDIO, VIDEO, BOOK, UNKNOWN }

/**
 * Audio / video / e-book format info parsed from a rutracker topic title (plus forum name).
 *
 * Rutracker encodes the release format in the title with well-known conventions, e.g.
 * `(Progressive Rock) [LP] [24/192] Pink Floyd - … , FLAC (tracks+.cue)`,
 * `… [2019, Концерт, BDRip 1080p]` or `… [2023, PDF, RUS]`. [fromTitle] extracts:
 * - [kind] — AUDIO / VIDEO / BOOK / UNKNOWN. An audio codec token beats video words;
 *   a forum name containing `видео` (`Видео`, `UHD музыкальное видео`, `… (Видео)`)
 *   marks VIDEO even when the title lacks rip keywords.
 * - [codec] — display-ready codec/container: `FLAC`, `MP3`, `APE`, `WavPack`, `DSD`,
 *   `AAC`, `ALAC`… for audio; `BDRip`, `Blu-ray`, `DVDRip`, `WEB-DL`, `x265`… for video;
 *   `PDF`/`DJVU`/`ePub` for books.
 * - [quality] — `lossless`, `320 kbps`, `VBR 320 kbps`, `V0`, `24-bit/96 kHz`
 *   (from `[24/96]`-style tags), `1bit/11.2MHz`, `DSD 256`, `1080p` / `2160p`…
 * - [tags] — at most 3 short extras: the cue variant (`image+.cue`, `tracks+.cue`,
 *   `tracks`), `Hi-Res`, `Dolby Atmos`, channel counts (`7.1`), `quad`, and the source
 *   medium (`SACD-R`, `DVD-A`, `Blu-ray`, `2xLP`, `LP`, `12"`, `CD`, `MC`, `reel`,
 *   `remaster`, `official`).
 *
 * Dependency-free (Regex only), so it compiles in commonMain.
 */
data class MediaInfo(
    val kind: MediaKind,
    val codec: String?,
    val quality: String?,
    val tags: List<String>,
) {
    /** 1–3 short chips for list rows, e.g. ["FLAC", "24-bit/96 kHz"] or ["BDRip 1080p"]; empty for UNKNOWN. */
    fun chips(): List<String> {
        if (kind == MediaKind.UNKNOWN) return emptyList()
        val out = ArrayList<String>(4)
        if (kind == MediaKind.VIDEO && codec != null && quality != null) {
            out += "$codec $quality" // e.g. "BDRip 1080p"
        } else {
            if (codec != null) out += codec
            // Skip a quality that just restates the codec (DSD + "DSD 256").
            if (quality != null && (codec == null || !quality.startsWith(codec))) out += quality
        }
        out += tags
        return out.take(3)
    }

    /** Single-line summary for the details screen, e.g. "Audio · FLAC (tracks+.cue) · lossless". */
    fun summary(): String {
        if (kind == MediaKind.UNKNOWN) return "Unknown"
        val parts = ArrayList<String>()
        if (codec != null) parts += if (tags.isNotEmpty()) "$codec (${tags.first()})" else codec
        if (quality != null) parts += quality
        parts += if (codec != null) tags.drop(1) else tags
        return (listOf(kindLabel) + parts).joinToString(" · ")
    }

    private val kindLabel: String
        get() = when (kind) {
            MediaKind.AUDIO -> "Audio"
            MediaKind.VIDEO -> "Video"
            MediaKind.BOOK -> "Book"
            MediaKind.UNKNOWN -> "Unknown"
        }

    companion object {
        /** Token guard: ASCII alnum boundaries, so Cyrillic text behaves as a delimiter. */
        private fun tok(word: String) = "(?<![a-z0-9])$word(?![a-z0-9])"

        private val audioCodecs: List<Pair<Regex, String>> = listOf(
            "wavpack" to "WavPack",
            "ogg\\s+vorbis" to "Ogg Vorbis",
            "ogg" to "OGG",
            "opus" to "Opus",
            "alac" to "ALAC",
            "flac" to "FLAC",
            "ape" to "APE",
            "aac" to "AAC",
            "wma" to "WMA",
            "wav" to "WAV",
            "pcm" to "PCM",
            "mp3" to "MP3",
        ).map { Regex(tok(it.first)) to it.second }

        private val videoCodecs: List<Pair<Regex, String>> = listOf(
            "bdredux" to "BDRedux",
            "bdrip" to "BDRip",
            "blu-ray" to "Blu-ray",
            "uhd" to "UHD",
            "dvdrip" to "DVDRip",
            "web-dl" to "WEB-DL",
            "webrip" to "WEBRip",
            "hdtvrip" to "HDTVRip",
            "hevc" to "HEVC",
            "x265" to "x265",
            "x264" to "x264",
            "avc" to "AVC",
            "mkv" to "MKV",
        ).map { Regex(tok(it.first)) to it.second }

        private val bookCodecs: List<Pair<Regex, String>> = listOf(
            "pdf" to "PDF",
            "djvu" to "DJVU",
            "epub" to "ePub",
        ).map { Regex(tok(it.first)) to it.second }

        /** `DSD 256 (tracks)`, `[DSD256]`, `DSD64/DSD128/DSD512` — group 1 is the rate, if any. */
        private val RE_DSD = Regex("(?<![a-z0-9])dsd\\s?(\\d{2,4})?(?![a-z])")

        // Cue variants: "FLAC (image+.cue)", "FLAC (tracks+.cue)", "WavPack (tracks)", "…, tracks".
        private val RE_CUE_IMAGE = Regex("image\\+\\.cue")
        private val RE_CUE_TRACKS = Regex("tracks\\+\\.cue")
        private val RE_TRACKS_PAREN = Regex("\\(\\s*tracks\\s*\\)")
        private val RE_TRACKS_TAIL = Regex(",\\s*tracks\\s*$")

        /** Hi-res depth/rate tag: `[24/96]`, `[24/192]`, `[32/384]` → "24-bit/96 kHz". */
        private val RE_HIRES = Regex("\\[(\\d{1,2})/(\\d{2,4})\\]")

        /** DSD tag `[1bit/11.2MHz]` — kept verbatim as a quality. */
        private val RE_1BIT = Regex("\\[\\s*1bit/(\\d+(?:\\.\\d+)?)\\s*mhz\\s*\\]")

        private val RE_LOSSLESS = Regex(tok("lossless"))
        private val RE_BITRATE = Regex("(?<![a-z0-9])(vbr\\s+)?(\\d{2,3})\\s*kbps(?![a-z0-9])")
        private val RE_LAME = Regex("(?<![a-z0-9])v(\\d)(?![a-z0-9])") // V0 / V2 presets

        private val RE_TR24 = Regex("\\[trm?24\\]") // [TR24] / [TRM24] — 24-bit transfer
        private val RE_TR16 = Regex("\\[tr16\\]")
        private val RE_HIRES_TAG = Regex("\\[hi-res[^\\]]*\\]") // [Hi-Res RM]
        private val RE_SACD_R = Regex(tok("sacd-r"))
        private val RE_DVDA = Regex(tok("dvda"))
        private val RE_ATMOS = Regex("dolby\\s+atmos")
        private val RE_CHANNELS = Regex("\\[(7\\.1|5\\.1|4\\.0)\\]")
        private val RE_QU = Regex("\\[qu\\]")

        // Source-medium tags (bracket-bounded so bare words like "of" never match).
        private val RE_BR_TAG = Regex("\\[br\\]")
        private val RE_2XLP = Regex("\\[2xlp\\]")
        private val RE_LP = Regex("\\[lp\\]")
        private val RE_12IN = Regex("\\[12\"\\]")
        private val RE_CD_TAG = Regex("\\[cd\\]")
        private val RE_MC = Regex("\\[mc\\]")
        private val RE_MB = Regex("\\[mb\\]") // магнитофонная бобина → reel
        private val RE_RM = Regex("\\[rm\\]")
        private val RE_OF = Regex("\\[of\\]")

        private val RE_RESOLUTION = Regex("(?<!\\d)(2160|1080|720|480)p(?!\\d)")
        private val RE_4K = Regex(tok("4k"))
        private val RE_SDR = Regex(tok("sdr"))
        private val RE_HDR = Regex("(?<![a-z0-9])hdr(10)?(?![a-z0-9])")
        private val RE_DTS = Regex("(?<![a-z0-9])dts(-hd)?(?![a-z0-9])")

        fun fromTitle(title: String, forumName: String = ""): MediaInfo {
            val t = title.replace('\u00A0', ' ').lowercase()
            val forumVideo = forumName.lowercase().contains("видео")

            // --- codec ---
            var codec: String? = null
            var dsdRate: String? = null
            RE_DSD.find(t)?.let { m ->
                codec = "DSD"
                dsdRate = m.groupValues[1].takeIf { it.isNotEmpty() }
            }
            if (codec == null) {
                codec = audioCodecs.firstNotNullOfOrNull { (re, name) ->
                    if (re.containsMatchIn(t)) name else null
                }
            }
            val has1Bit = RE_1BIT.containsMatchIn(t)
            if (codec == null && has1Bit) codec = "DSD" // 1bit/xMHz is the DSD family
            val bookCodec = bookCodecs.firstNotNullOfOrNull { (re, name) ->
                if (re.containsMatchIn(t)) name else null
            }

            // --- cue variant ---
            val cue = when {
                RE_CUE_IMAGE.containsMatchIn(t) -> "image+.cue"
                RE_CUE_TRACKS.containsMatchIn(t) -> "tracks+.cue"
                RE_TRACKS_PAREN.containsMatchIn(t) -> "tracks"
                RE_TRACKS_TAIL.containsMatchIn(t) -> "tracks"
                else -> null
            }

            // --- quality (most specific first) ---
            var quality: String? = null
            RE_HIRES.find(t)?.let { m -> quality = "${m.groupValues[1]}-bit/${m.groupValues[2]} kHz" }
            if (quality == null) RE_1BIT.find(t)?.let { m -> quality = "1bit/${m.groupValues[1]}MHz" }
            val lossless = RE_LOSSLESS.containsMatchIn(t)
            if (quality == null && lossless) quality = "lossless"
            if (quality == null) {
                RE_BITRATE.find(t)?.let { m ->
                    quality = (if (m.groupValues[1].isNotBlank()) "VBR " else "") + "${m.groupValues[2]} kbps"
                }
            }
            if (quality == null) RE_LAME.find(t)?.let { m -> quality = "V${m.groupValues[1]}" }

            val tr24 = RE_TR24.containsMatchIn(t)
            val sacdR = RE_SACD_R.containsMatchIn(t)
            val dvda = RE_DVDA.containsMatchIn(t)
            if (quality == null && dsdRate != null) quality = "DSD $dsdRate"
            if (quality == null && tr24) quality = "24-bit"

            // --- video ---
            val videoCodec = videoCodecs.firstNotNullOfOrNull { (re, name) ->
                if (re.containsMatchIn(t)) name else null
            }
            val resolution = RE_RESOLUTION.find(t)?.value
                ?: if (RE_4K.containsMatchIn(t)) "2160p" else null

            // --- kind: an audio codec token beats video words; forum `видео` beats everything else ---
            val audioMarker = codec != null || lossless || quality != null || sacdR || dvda ||
                tr24 || RE_TR16.containsMatchIn(t) || cue != null || has1Bit
            val kind = when {
                codec != null -> MediaKind.AUDIO
                videoCodec != null || resolution != null || forumVideo -> MediaKind.VIDEO
                bookCodec != null -> MediaKind.BOOK
                audioMarker -> MediaKind.AUDIO
                else -> MediaKind.UNKNOWN
            }
            val finalQuality = if (kind == MediaKind.VIDEO) (resolution ?: quality) else quality
            val displayCodec = when (kind) {
                MediaKind.AUDIO -> codec
                MediaKind.VIDEO -> videoCodec
                MediaKind.BOOK -> bookCodec
                MediaKind.UNKNOWN -> null
            }

            // --- tags (≤3, roughly in order of interest to a music downloader) ---
            val tagList = ArrayList<String>(6)
            when (kind) {
                MediaKind.AUDIO -> {
                    if (cue != null) tagList += cue
                    if (RE_ATMOS.containsMatchIn(t)) tagList += "Dolby Atmos"
                    RE_CHANNELS.find(t)?.let { tagList += it.groupValues[1] }
                    if (RE_QU.containsMatchIn(t)) tagList += "quad"
                    if (tr24 || RE_HIRES_TAG.containsMatchIn(t)) tagList += "Hi-Res"
                    if (sacdR) tagList += "SACD-R"
                    if (dvda) tagList += "DVD-A"
                    if (RE_BR_TAG.containsMatchIn(t)) tagList += "Blu-ray"
                    if (RE_2XLP.containsMatchIn(t)) tagList += "2xLP"
                    if (RE_LP.containsMatchIn(t)) tagList += "LP"
                    if (RE_12IN.containsMatchIn(t)) tagList += "12\""
                    if (RE_CD_TAG.containsMatchIn(t)) tagList += "CD"
                    if (RE_MC.containsMatchIn(t)) tagList += "MC"
                    if (RE_MB.containsMatchIn(t)) tagList += "reel"
                    if (RE_RM.containsMatchIn(t)) tagList += "remaster"
                    if (RE_OF.containsMatchIn(t)) tagList += "official"
                }
                MediaKind.VIDEO -> {
                    if (RE_SDR.containsMatchIn(t)) tagList += "SDR"
                    if (RE_HDR.containsMatchIn(t)) tagList += "HDR"
                    if (RE_DTS.containsMatchIn(t)) tagList += "DTS"
                    if (RE_ATMOS.containsMatchIn(t)) tagList += "Dolby Atmos"
                    RE_CHANNELS.find(t)?.let { tagList += it.groupValues[1] }
                }
                else -> Unit
            }

            return MediaInfo(kind, displayCodec, finalQuality, tagList.distinct().take(3))
        }
    }
}
