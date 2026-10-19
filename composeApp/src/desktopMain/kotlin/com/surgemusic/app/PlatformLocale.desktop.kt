package com.surgemusic.app

/** desktopMain actual of the commonMain locale probe. */
actual fun systemLanguageIsRussian(): Boolean = java.util.Locale.getDefault().language.startsWith("ru")
