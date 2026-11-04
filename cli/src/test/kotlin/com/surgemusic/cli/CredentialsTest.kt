package com.surgemusic.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

/**
 * File-backed loading only: the RUTRACKER_* env branches come first in production, so the
 * test task blanks those vars and every case below exercises the injected base dir.
 */
class CredentialsTest {
    @TempDir
    lateinit var baseDir: Path

    private fun write(name: String, content: String) {
        Files.writeString(baseDir.resolve(name), content)
    }

    @Test fun credentialsFile_parsesUserAndPass_ignoringBlankLinesAndWhitespace() {
        write("credentials", "  user@example.com  \n\n\t hunter2 \n")
        assertEquals(Credentials("user@example.com", "hunter2"), loadCredentials(baseDir))
    }

    @Test fun credentialsFile_usesOnlyFirstTwoLines() {
        write("credentials", "user\npass\nextra\n")
        assertEquals(Credentials("user", "pass"), loadCredentials(baseDir))
    }

    @Test fun credentialsFile_handlesCrlfAndEofWithoutNewline() {
        write("credentials", "user\r\npass\r\n")
        assertEquals(Credentials("user", "pass"), loadCredentials(baseDir))
        write("credentials", "user\npass")
        assertEquals(Credentials("user", "pass"), loadCredentials(baseDir))
    }

    @Test fun credentialsFile_missingReturnsNull() {
        assertNull(loadCredentials(baseDir))
    }

    @Test fun credentialsFile_onlyBlankLinesReturnsNull() {
        write("credentials", "\n \n")
        assertNull(loadCredentials(baseDir))
    }

    @Test fun cookiesFile_parsesHeaderAndUserAgent_trimmed() {
        write("cookies", "  bb_session=abc; cf_clearance=xyz \n\n  Mozilla/5.0 (Macintosh) \n")
        assertEquals(
            SessionConfig("bb_session=abc; cf_clearance=xyz", "Mozilla/5.0 (Macintosh)"),
            loadSessionConfig(baseDir),
        )
    }

    @Test fun cookiesFile_withoutUserAgentLine_yieldsNullUserAgent() {
        write("cookies", "bb_session=abc\n")
        assertEquals(SessionConfig("bb_session=abc", null), loadSessionConfig(baseDir))
    }

    @Test fun cookiesFile_blankUserAgentLine_yieldsNullUserAgent() {
        write("cookies", "bb_session=abc\n   \n")
        assertEquals(SessionConfig("bb_session=abc", null), loadSessionConfig(baseDir))
    }

    @Test fun cookiesFile_missingReturnsNull() {
        assertNull(loadSessionConfig(baseDir))
    }

    @Test fun injectedBaseDir_isWhereFilesAreReadFrom() {
        // Distinctive content proves the injected dir (not ~/.config/surgemusic) is the source.
        write("credentials", "injected-user\ninjected-pass\n")
        write("cookies", "injected=cookie\n")
        assertEquals(Credentials("injected-user", "injected-pass"), loadCredentials(baseDir))
        assertEquals(SessionConfig("injected=cookie", null), loadSessionConfig(baseDir))
    }
}
