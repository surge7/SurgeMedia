package com.surgemusic.app.locale

import com.surgemusic.app.IsMacOs
import com.surgemusic.app.IsWindows
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.win32.W32APIOptions
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/** Poll cadence for OS-language changes (desktop live re-language task). */
private const val POLL_INTERVAL_MS = 10_000L

/** `defaults` subprocess timeout (macOS) — a hung read must not stall the poll loop. */
private const val DEFAULTS_TIMEOUT_MS = 5_000L

/** Windows `LOCALE_NAME_MAX_LENGTH` — the buffer `LCIDToLocaleName` writes into. */
private const val LOCALE_NAME_MAX_LENGTH = 85

/**
 * Outcome of one poll sample compared against the monitor's baseline language. The
 * OS-sourced sample wins over the JVM's own movement — it is the authoritative signal
 * and carries the [Locale.setDefault] follow-up — while a JVM default whose language
 * moved on its own (some macOS JVMs self-update it when the OS language changes) still
 * bumps the tick without touching [Locale].
 */
internal sealed interface LocaleChange {
    /** Baseline still matches both samples — nothing to do. */
    data object None : LocaleChange

    /** The OS reports a language different from the baseline: apply via [Locale.setDefault]. */
    data class OsChange(val language: String) : LocaleChange

    /** The JVM default's language moved on its own: re-resolve strings, no setDefault. */
    data class JvmSelfUpdate(val language: String) : LocaleChange
}

/**
 * Pure change-detection core of [DesktopLocaleMonitor]: compare the baseline
 * [priorLanguage] with a fresh sample of the OS-sourced language and the JVM default's
 * language (either may be null — macOS/Windows can fail to produce the former, Linux
 * never produces it). Pure so the monitor's logic is unit-testable with injected
 * prior/new locale pairs.
 */
internal fun detectLocaleChange(
    priorLanguage: String?,
    osLanguage: String?,
    jvmLanguage: String?,
): LocaleChange = when {
    osLanguage != null && osLanguage != priorLanguage -> LocaleChange.OsChange(osLanguage)
    jvmLanguage != null && jvmLanguage != priorLanguage -> LocaleChange.JvmSelfUpdate(jvmLanguage)
    else -> LocaleChange.None
}

/**
 * Language part of an OS locale tag: `ru_RU@rg=uszzzz` (macOS AppleLocale) and `en-US` /
 * `en_US` (Windows `LCIDToLocaleName` / region-less AppleLocale) all resolve to `ru` /
 * `en`. Only the first line is read (the `defaults` output carries a trailing newline);
 * anything without a lowercase-letter language head returns null — "no usable OS sample
 * this poll".
 */
internal fun languageOfOsTag(raw: String?): String? {
    val tag = raw?.lineSequence()?.firstOrNull().orEmpty().trim().substringBefore('@')
    val language = tag.substringBefore('_').substringBefore('-').lowercase(Locale.ROOT)
    return language.takeIf { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } }
}

/**
 * Desktop OS-language watcher: keeps `AppLanguage.SYSTEM` honest without an app restart.
 * A daemon poll loop (10s cadence) samples the platform UI language and the JVM default's
 * language; on a change it re-points [Locale.setDefault] (OS-sourced samples only) and
 * bumps the injected [tick] flow so `App` re-resolves its strings live.
 *
 * Per-platform sampling:
 * - macOS: `defaults read -g AppleLocale` via ProcessBuilder with a
 *   [DEFAULTS_TIMEOUT_MS] wait, parsed by [languageOfOsTag].
 * - Windows: kernel32 `GetUserDefaultUILanguage` → `LCIDToLocaleName` via the minimal
 *   JNA mapping at the bottom of this file — jna-platform 5.17.0 maps neither call
 *   (verified against the jar), so they are mapped here on the jna core library that is
 *   already on the desktop classpath, same pattern as
 *   [com.surgemusic.app.WindowsWindowChrome].
 * - Linux: no OS sample — the environment is fixed for the process lifetime, so only the
 *   JVM-default watch runs; a full OS-language change still requires an app restart there
 *   (documented here rather than worked around).
 *
 * The JVM-default watch runs on every platform: some macOS JVMs self-update
 * `Locale.getDefault()` when the OS language changes, and that signal alone is enough to
 * act (bump the tick) without the OS call — no [Locale.setDefault] then, since the JVM
 * already did the update and the sample is not OS-sourced.
 *
 * On an OS-sourced change the new default keeps the current region
 * (`Locale.of(lang, currentCountry)`) so the number/date formats dictated by the user's
 * OS region stay stable.
 *
 * Lifecycle: [start] at desktop app startup, [stop] from the app's shutdown hook
 * (Main.kt). Both are idempotent, and the loop runs on Dispatchers.IO (daemon threads),
 * so no exit path is blocked or leaked either way.
 */
class DesktopLocaleMonitor(private val tick: MutableStateFlow<Int>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Baseline language both samples compare against; set at [start], updated per change. */
    @Volatile private var knownLanguage: String? = null

    /** Starts the poll loop (restarts it if already running). */
    fun start() {
        stop()
        knownLanguage = Locale.getDefault().language
        job = scope.launch {
            while (isActive) {
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Cancels the poll loop; safe to call repeatedly. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** One poll: sample → [detectLocaleChange] → apply. All state is [knownLanguage] / [tick]. */
    private fun pollOnce() {
        val prior = knownLanguage ?: return
        val osLanguage = osUiLanguage()
        val jvmLanguage = Locale.getDefault().language
        when (val change = detectLocaleChange(prior, osLanguage, jvmLanguage)) {
            is LocaleChange.OsChange -> {
                // OS-sourced: the JVM did not move, so re-point the default itself.
                Locale.setDefault(Locale.of(change.language, Locale.getDefault().country))
                knownLanguage = change.language
                tick.value += 1
            }
            is LocaleChange.JvmSelfUpdate -> {
                // The JVM already updated its own default — no setDefault (not OS-sourced).
                knownLanguage = change.language
                tick.value += 1
            }
            LocaleChange.None -> Unit
        }
    }

    /** OS-sourced UI language for this platform, or null when unavailable (see class KDoc). */
    private fun osUiLanguage(): String? = when {
        IsMacOs -> macOsUiLanguage()
        IsWindows -> windowsUiLanguage()
        else -> null
    }

    /**
     * macOS UI language: `defaults read -g AppleLocale` with a [DEFAULTS_TIMEOUT_MS] wait
     * (a hung `defaults` is killed and the poll gives up with null). A non-zero exit
     * (key missing) is null too — the JVM-default watch still covers the poll.
     */
    private fun macOsUiLanguage(): String? {
        if (!IsMacOs) return null
        val raw = runCatching {
            val process = ProcessBuilder("defaults", "read", "-g", "AppleLocale").start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            when {
                !process.waitFor(DEFAULTS_TIMEOUT_MS, TimeUnit.MILLISECONDS) -> {
                    process.destroyForcibly()
                    null
                }
                process.exitValue() != 0 -> null
                else -> output
            }
        }.getOrNull()
        return raw?.let(::languageOfOsTag)
    }

    /**
     * Windows UI language: `GetUserDefaultUILanguage` → LANGID, converted to a locale
     * name (`ru-RU`) by `LCIDToLocaleName`, then parsed by [languageOfOsTag]. Both calls
     * are best-effort: any failure (or a non-Windows host) returns null.
     */
    private fun windowsUiLanguage(): String? {
        if (!IsWindows) return null
        return runCatching {
            val kernel32 = Kernel32Locale.INSTANCE
            val langId = kernel32.GetUserDefaultUILanguage().toInt() and 0xFFFF
            val buffer = CharArray(LOCALE_NAME_MAX_LENGTH)
            val written = kernel32.LCIDToLocaleName(langId, buffer, buffer.size, 0)
            if (written > 0) String(buffer, 0, written - 1) else null // -1: trailing NUL
        }.getOrNull()?.let(::languageOfOsTag)
    }
}

/**
 * Minimal kernel32 mapping for the UI-language probe (jna-platform 5.17.0 maps neither
 * call — verified). Loaded lazily at first use and touched only on Windows, like
 * [com.surgemusic.app.WindowsWindowChrome]; the W32 options carry the calling/unicode
 * conventions kernel32's exports expect.
 */
private interface Kernel32Locale : Library {
    companion object {
        val INSTANCE: Kernel32Locale = Native.load("kernel32", Kernel32Locale::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    /** The user's UI language as a LANGID (16-bit; masked to unsigned by the caller). */
    fun GetUserDefaultUILanguage(): Short

    /** LANGID → locale name (`ru-RU`) into [buffer]; returns the length incl. NUL, or 0. */
    fun LCIDToLocaleName(locale: Int, buffer: CharArray, cchName: Int, flags: Int): Int
}
