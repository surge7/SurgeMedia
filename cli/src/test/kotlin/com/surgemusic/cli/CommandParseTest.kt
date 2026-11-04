package com.surgemusic.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** The command dispatch table — [parseCommand] is the pure extraction from `main`. */
class CommandParseTest {
    @Test fun noArgs_yieldsPlainUsage() {
        assertEquals(Command.Usage, parseCommand(emptyList()))
    }

    @Test fun unknownCommand_yieldsPlainUsage() {
        assertEquals(Command.Usage, parseCommand(listOf("bogus")))
    }

    @Test fun commandWordIsCaseInsensitive() {
        assertEquals(Command.Login, parseCommand(listOf("LOGIN")))
        assertEquals(Command.Session, parseCommand(listOf("Session")))
        assertEquals(Command.Version, parseCommand(listOf("VERSION")))
    }

    @Test fun simpleCommandsDispatch() {
        assertEquals(Command.Login, parseCommand(listOf("login")))
        assertEquals(Command.Session, parseCommand(listOf("session")))
        assertEquals(Command.Version, parseCommand(listOf("version")))
    }

    @Test fun searchTakesTheNextArg() {
        assertEquals(Command.Search("foo bar"), parseCommand(listOf("search", "foo bar")))
    }

    @Test fun searchWithoutQuery_isAMissingArgUsageError() {
        assertEquals(Command.MissingArg("search requires a query"), parseCommand(listOf("search")))
    }

    @Test fun downloadForwardsItsArgumentsUnparsed() {
        assertEquals(
            Command.Download(listOf("42", "--out", "/x")),
            parseCommand(listOf("download", "42", "--out", "/x")),
        )
    }
}
