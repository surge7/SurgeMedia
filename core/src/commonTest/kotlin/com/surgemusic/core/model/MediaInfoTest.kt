package com.surgemusic.core.model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaInfoTest {
    // All titles below are real topic titles from the live fixture core/liveFixtures/search.html.

    @Test fun flacHiResTracksPlusCue() {
        val info = MediaInfo.fromTitle(
            "(Progressive Rock) [LP] [24/192] Pink Floyd - The Dark Side of the Moon - 1973 (2003, 30th Anniversary Edition), FLAC (tracks+.cue)",
        )
        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals("FLAC", info.codec)
        assertEquals("24-bit/192 kHz", info.quality)
        assertTrue(info.tags.contains("tracks+.cue"), "tags=${info.tags}")
    }

    @Test fun mp3Bitrate() {
        val info = MediaInfo.fromTitle(
            "(Electronic, Trance, Ambient, Downtempo) Out of Phase - Animals 2001 - A Tribute to Pink Floyd - 2001, MP3, 320 kbps",
        )
        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals("MP3", info.codec)
        assertEquals("320 kbps", info.quality)
    }

    @Test fun apeLosslessImagePlusCue() {
        val info = MediaInfo.fromTitle(
            "[RM] [TR24] (Rock) Pink Floyd - The Album Collection (14 Releases, Tatman Music 03.10.2026), APE (image+.cue), lossless",
        )
        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals("APE", info.codec)
        assertEquals("lossless", info.quality)
        assertTrue(info.tags.contains("image+.cue"), "tags=${info.tags}")
    }

    @Test fun wavPackTracks() {
        val info = MediaInfo.fromTitle(
            "(Prog Rock) [LP] [32/384] Pink Floyd – Animals - 2016 (1977), WavPack (tracks)",
        )
        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals("WavPack", info.codec)
        assertEquals("32-bit/384 kHz", info.quality)
        assertTrue(info.tags.contains("tracks"), "tags=${info.tags}")
    }

    @Test fun dsdWithAndWithoutRateTag() {
        val withTag = MediaInfo.fromTitle(
            "(Rock) [LP] [1bit/11.2MHz] Pink Floyd - The Dark Side of the Moon (Mobile Fidelity Japan) - 1979, DSD 256 (tracks)",
        )
        assertEquals(MediaKind.AUDIO, withTag.kind)
        assertEquals("DSD", withTag.codec)
        assertEquals("1bit/11.2MHz", withTag.quality)
        assertTrue(withTag.tags.contains("tracks"), "tags=${withTag.tags}")

        val bare = MediaInfo.fromTitle("(Rock) [LP] Pink Floyd - 8-Tracks - 2026, DSD 256 (tracks)")
        assertEquals(MediaKind.AUDIO, bare.kind)
        assertEquals("DSD", bare.codec)

        val bracketed = MediaInfo.fromTitle(
            "(Rock, Psychedelic Rock) [MB] [DSD256] Pink Floyd - Meddle (1971), tracks",
        )
        assertEquals(MediaKind.AUDIO, bracketed.kind)
        assertEquals("DSD", bracketed.codec)
        assertTrue(bracketed.tags.contains("tracks"), "tags=${bracketed.tags}")
    }

    @Test fun aacVbr() {
        val info = MediaInfo.fromTitle(
            "(Progressive Rock, Art Rock, Classic Rock) Roger Waters (ex - Pink Floyd) - Collection (49 CD + 3 WEB) - 1970-2025, AAC (tracks), VBR 320 kbps",
        )
        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals("AAC", info.codec)
        assertEquals("VBR 320 kbps", info.quality)
        assertTrue(info.tags.contains("tracks"), "tags=${info.tags}")
    }

    @Test fun alacHiRes() {
        val info = MediaInfo.fromTitle(
            "(English Psychedelic Rock, Prog) [MC] [24/48] Pink Floyd - Meddle - 1974 (1971), ALAC (image+.cue)",
        )
        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals("ALAC", info.codec)
        assertEquals("24-bit/48 kHz", info.quality)
        assertTrue(info.tags.contains("image+.cue"), "tags=${info.tags}")
    }

    @Test fun atmosMultichannelTransfer() {
        val info = MediaInfo.fromTitle(
            "[TRM24][BR][7.1][Dolby Atmos] Pink Floyd - Wish You Were Here (Remastered) - 1975 / 2025 (Art Rock, Prog Rock)",
        )
        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals("24-bit", info.quality)
        assertTrue(info.tags.contains("Dolby Atmos"), "tags=${info.tags}")
        assertTrue(info.tags.contains("7.1"), "tags=${info.tags}")
    }

    @Test fun bdrip1080p() {
        val info = MediaInfo.fromTitle(
            "Pink Floyd - The Later Years 1987-2019 [2019, Концерт, прогрессивный рок, BDRip 1080p]",
        )
        assertEquals(MediaKind.VIDEO, info.kind)
        assertEquals("1080p", info.quality)
        assertEquals("BDRip 1080p", info.chips().first())
    }

    @Test fun uhdBdRedux2160pSdr() {
        val info = MediaInfo.fromTitle(
            "Пинк Флойд в Помпеях - 1972, 1974 (Пинк Флойд: Концерт в Помпеи) / Pink Floyd At Pompeii - MCMLXXII (Pink Floyd: Live at Pompeii) [1972, музыкальный, документальный, UHD BDRedux 2160p, SDR] + Sub Rus,Eng",
        )
        assertEquals(MediaKind.VIDEO, info.kind)
        assertEquals("2160p", info.quality)
        assertEquals("BDRedux", info.codec)
        assertTrue(info.tags.contains("SDR"), "tags=${info.tags}")
    }

    @Test fun bluRayVideo() {
        val info = MediaInfo.fromTitle(
            "Pink Floyd - Pink Floyd At Pompeii-MCMLXXII [1972 / 2026, Rock, Progressive Rock, Blu-ray, 2160p]",
        )
        assertEquals(MediaKind.VIDEO, info.kind)
        assertEquals("Blu-ray", info.codec)
        assertEquals("2160p", info.quality)
    }

    @Test fun dvdRipAvc() {
        val info = MediaInfo.fromTitle(
            "The Pink Floyd Story: Which One's Pink? / История Pink Floyd: Кто из них Пинк? [2007, документальный, биография, музыка, DVDRip-AVC] DVO (Pazl Voice) + Original (Eng) + Sub (Rus)",
        )
        assertEquals(MediaKind.VIDEO, info.kind)
        assertEquals("DVDRip", info.codec)
    }

    @Test fun pdfBook() {
        val info = MediaInfo.fromTitle(
            "(Справочник) Маббетт Энди - Pink Floyd. Полный путеводитель по песням и альбомам (Рок-Путеводитель) [2023, PDF, RUS]",
        )
        assertEquals(MediaKind.BOOK, info.kind)
        assertEquals("PDF", info.codec)
    }

    @Test fun forumNameMarksVideo() {
        val info = MediaInfo.fromTitle("Pink Floyd - Live at Pompeii", "UHD музыкальное видео")
        assertEquals(MediaKind.VIDEO, info.kind)
        val docForum = MediaInfo.fromTitle(
            "The Pink Floyd Story: Which One's Pink?", "Документальные фильмы о музыке и музыкантах (Видео)",
        )
        assertEquals(MediaKind.VIDEO, docForum.kind)
    }

    @Test fun audioCodecBeatsVideoWords() {
        val info = MediaInfo.fromTitle("(Rock) Pink Floyd - Live at Pompeii 1988, FLAC (tracks), BDRip 1080p")
        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals("FLAC", info.codec)
    }

    @Test fun unknownFallback() {
        val info = MediaInfo.fromTitle("Pink Floyd - Collection 1967-83", "Зарубежная рок-музыка (оцифровки)")
        assertEquals(MediaKind.UNKNOWN, info.kind)
        assertEquals("Unknown", info.summary())
        assertTrue(info.chips().isEmpty())
    }

    @Test fun summaryJoinsKindCodecTagAndQuality() {
        val info = MediaInfo.fromTitle(
            "(Prog Rock) [CD] Pink Floyd - One Of These Days. Live In London 1971 - (2022, Unofficial Release), FLAC (image+.cue), lossless",
        )
        assertEquals("Audio · FLAC (image+.cue) · lossless · CD", info.summary())
    }

    @Test fun chipsCappedAtThree() {
        val info = MediaInfo.fromTitle(
            "(Progressive Rock) [LP] [24/96] Pink Floyd - Wish You Were Here [PBTHAL 2025] (1980 US Mastersound) - 1975/1980, FLAC (tracks)",
        )
        assertTrue(info.chips().size <= 3, "chips=${info.chips()}")
        assertTrue(info.tags.size <= 3, "tags=${info.tags}")
        assertEquals("FLAC", info.chips().first())
    }

    @Test fun sacdRTransferWithoutCodec() {
        val info = MediaInfo.fromTitle(
            "[SACD-R][OF] Pink Floyd - The Dark Side of the Moon - 1973/2003 (Classic Rock, Prog Rock)",
        )
        assertEquals(MediaKind.AUDIO, info.kind)
        assertTrue(info.tags.contains("SACD-R"), "tags=${info.tags}")
    }

    @Test fun hiResForumStyleTag() {
        val info = MediaInfo.fromTitle(
            "(Progressive, Art, Psychodelic Rock) [Hi-Res RM] Pink Floyd - Collection (RM 24-48) - 1973-2014, FLAC (image+.cue), lossless",
        )
        assertEquals(MediaKind.AUDIO, info.kind)
        assertEquals("FLAC", info.codec)
        assertEquals("lossless", info.quality)
        assertTrue(info.tags.contains("Hi-Res"), "tags=${info.tags}")
    }
}
