package com.surgemusic.app

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform DI container seam (desktop-locale task): commonMain can only see the members
 * declared here — the real containers live under the same name in desktopMain /
 * androidMain and carry the actuals.
 *
 * [localeTick] bumps whenever the app must re-resolve SYSTEM strings from the OS locale:
 * on desktop the OS-language monitor (`DesktopLocaleMonitor`) bumps it when the OS
 * language changes, so the shell re-resolves live without an app restart; android keeps
 * it at 0 forever (the OS recreates the process on a language change, so a restart
 * re-resolves naturally).
 */
expect object AppContainer {
    /** OS-language-change tick: bumped by the desktop monitor, static 0 on android. */
    val localeTick: StateFlow<Int>
}
