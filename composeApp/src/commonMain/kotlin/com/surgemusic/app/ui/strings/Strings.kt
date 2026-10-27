package com.surgemusic.app.ui.strings

import androidx.compose.runtime.staticCompositionLocalOf
import com.surgemusic.app.AppLanguage

/**
 * Every user-visible string in the app, one member per text (plain `val` when
 * parameter-free, `fun` where interpolation is needed). Implemented twice —
 * [EnStrings] (the historical English text, verbatim) and [RuStrings] (Russian per the
 * project glossary) — and resolved per the Settings ▸ Appearance language option.
 * Log messages, debug-only strings and the CLI stay English and are not members here.
 */
interface AppStrings {

    // Tabs & shell
    val tabSearch: String
    val tabDownloads: String
    val tabDownloaded: String
    val tabSettings: String

    // Download snackbar (App shell)
    val addedToDownloads: String
    fun downloadFailed(error: String): String
    val actionRetry: String

    // Search
    val searchFieldLabel: String
    val searchButton: String
    val searchIdleHint: String
    fun noResults(query: String): String
    val loadMore: String
    fun searchFailed(message: String): String
    val logIn: String
    val loginHeroTitle: String
    val loginHeroBody: String

    // Sort bar
    val sortByLabel: String
    val sortByRelevance: String
    val sortBySeeds: String
    val sortBySize: String
    val sortByDate: String
    val sortByTitle: String

    // Details
    val detailsTitle: String
    val detailForum: String
    val detailSize: String
    val detailFormat: String
    val detailSeedsLeechs: String
    val detailDate: String
    val detailTopicId: String
    val lastAttemptFailed: String
    val fetchingTorrent: String
    val fetchingLabel: String
    val addedOpenDownloads: String
    val download: String
    val detailsPlaceholderTitle: String
    val detailsPlaceholderBody: String

    // Downloads
    val stateQueued: String
    val stateDownloading: String
    val statePaused: String
    val stateFinalizing: String
    val stateDone: String
    val stateFailed: String
    val stateCancelled: String
    val pause: String
    val resume: String
    val cancel: String
    val remove: String
    val deleteFiles: String
    val deleteAllFiles: String
    val clearHistory: String
    val emptyDownloads: String
    val confirmDeleteItemFiles: String
    val confirmDeleteAllFiles: String
    fun speedPerSecond(bytes: String): String
    fun peersCount(n: Int): String
    fun deletedFiles(n: Int): String
    fun deleteFailed(detail: String?): String

    // Downloaded library
    val libraryHeader: String
    val refresh: String
    val deleteAll: String
    val delete: String
    val emptyLibrary: String
    val libraryMusic: String
    val libraryMovies: String
    fun fileCount(n: Int): String
    fun confirmDeleteRelease(title: String, files: Int, size: String): String
    val confirmDeleteAllLibrary: String
    fun deletedFilesFor(n: Int, title: String): String
    fun libraryReadFailed(detail: String?): String

    // Media format chips
    val kindAudio: String
    val kindVideo: String
    val kindBook: String

    // Settings
    val sessionHeader: String
    val notLoggedIn: String
    fun loggedInAt(timestamp: String): String
    val logOut: String
    val validate: String
    val validationOk: String
    fun sessionCheckFailed(detail: String): String
    val importFailedEmptyCookie: String
    val appearanceHeader: String
    val themeCaption: String
    val themeSystem: String
    val themeLight: String
    val themeDark: String
    val languageCaption: String
    val languageSystem: String
    val languageEnglish: String
    val languageRussian: String
    val manualImportHeader: String
    val manualImportBody: String
    val cookieHeaderLabel: String
    val userAgentLabel: String
    val importButton: String
    val networkHeader: String
    val wifiOnlyLabel: String
    val mirrorUrlLabel: String
    val applyButton: String
    fun concurrentDownloads(n: Int): String
    val aboutHeader: String
    fun aboutVersion(version: String): String
    val aboutBody: String

    // Storage (download destination folders)
    val storageHeader: String
    val storageCaption: String
    val musicFolderLabel: String
    val videoFolderLabel: String
    val changeFolder: String
    val resetFolder: String

    // Torrent file chooser
    val chooseFilesTitle: String
    val addFilesTitle: String
    val addFiles: String
    val downloaded: String
    val selectAll: String
    val selectNone: String
    fun filesSummary(selected: Int, total: Int, selectedSize: String, totalSize: String): String

    // Login overlays
    val signInTitle: String
    val saveSession: String
    val desktopLoginBody: String
    val signInViaBrowser: String
    val signInBrowserWaiting: String
    val signInBrowserValidating: String
    val signInBrowserClosed: String
    val signInBrowserNotFound: String
    fun signInBrowserLaunchFailed(detail: String): String
    val unknownError: String

    // Accessibility (icon content descriptions)
    val back: String
    val close: String
}

/** English strings — the historical app text, verbatim. */
object EnStrings : AppStrings {
    override val tabSearch = "Search"
    override val tabDownloads = "Downloads"
    override val tabDownloaded = "Downloaded"
    override val tabSettings = "Settings"

    override val addedToDownloads = "Added to Downloads."
    override fun downloadFailed(error: String) = "Download failed: $error"
    override val actionRetry = "Retry"

    override val searchFieldLabel = "Search rutracker"
    override val searchButton = "Search"
    override val searchIdleHint = "Search rutracker — movies, video, music, books and more."
    override fun noResults(query: String) = "No results for \"$query\"."
    override val loadMore = "Load more"
    override fun searchFailed(message: String) = "Search failed: $message"
    override val logIn = "Log in"
    override val loginHeroTitle = "Log in to rutracker"
    override val loginHeroBody =
        "Searching and downloading need a rutracker session. Log in once in the in-app browser."

    override val sortByLabel = "SORT"
    override val sortByRelevance = "Relevance"
    override val sortBySeeds = "Seeds"
    override val sortBySize = "Size"
    override val sortByDate = "Date"
    override val sortByTitle = "Title"

    override val detailsTitle = "Details"
    override val detailForum = "Forum"
    override val detailSize = "Size"
    override val detailFormat = "Format"
    override val detailSeedsLeechs = "Seeds / Leechs"
    override val detailDate = "Date"
    override val detailTopicId = "Topic ID"
    override val lastAttemptFailed = "Last attempt failed"
    override val fetchingTorrent = "Fetching torrent…"
    override val fetchingLabel = "FETCHING"
    override val addedOpenDownloads = "Added — open Downloads"
    override val download = "Download"
    override val detailsPlaceholderTitle = "No topic selected"
    override val detailsPlaceholderBody = "Pick a result from the list to preview its details and download."

    override val stateQueued = "Queued"
    override val stateDownloading = "Downloading"
    override val statePaused = "Paused"
    override val stateFinalizing = "Finalizing"
    override val stateDone = "Done"
    override val stateFailed = "Failed"
    override val stateCancelled = "Cancelled"
    override val pause = "Pause"
    override val resume = "Resume"
    override val cancel = "Cancel"
    override val remove = "Remove"
    override val deleteFiles = "Delete files"
    override val deleteAllFiles = "Delete all files"
    override val clearHistory = "Clear history"
    override val emptyDownloads = "No downloads yet — find a movie, album or show"
    override val confirmDeleteItemFiles = "Delete the downloaded files for this item from your device?"
    override val confirmDeleteAllFiles = "Delete ALL files Surge Media downloaded? This cannot be undone."
    override fun speedPerSecond(bytes: String) = "$bytes/s"
    override fun peersCount(n: Int) = "$n peers"
    override fun deletedFiles(n: Int) = "Deleted $n file(s)"
    override fun deleteFailed(detail: String?) = "Delete failed: ${detail ?: unknownError}"

    override val libraryHeader = "Downloaded"
    override val refresh = "Refresh"
    override val deleteAll = "Delete all"
    override val delete = "Delete"
    override val emptyLibrary = "Nothing downloaded yet — finished downloads land here"
    override val libraryMusic = "Music"
    override val libraryMovies = "Movies"
    override fun fileCount(n: Int) = "$n file(s)"
    override fun confirmDeleteRelease(title: String, files: Int, size: String) =
        "Delete \"$title\" (${fileCount(files)}, $size) from your device?"
    override val confirmDeleteAllLibrary = "Delete ALL downloaded files from every library? This cannot be undone."
    override fun deletedFilesFor(n: Int, title: String) = "${deletedFiles(n)} — $title"
    override fun libraryReadFailed(detail: String?) = "Could not read the library: ${detail ?: unknownError}"

    override val kindAudio = "Audio"
    override val kindVideo = "Video"
    override val kindBook = "Book"

    override val sessionHeader = "Session"
    override val notLoggedIn = "Not logged in"
    override fun loggedInAt(timestamp: String) = "Logged in (saved $timestamp)"
    override val logOut = "Log out"
    override val validate = "Validate"
    override val validationOk = "Session OK — test search succeeded."
    override fun sessionCheckFailed(detail: String) = "Session check failed: $detail"
    override val importFailedEmptyCookie = "Import failed: the cookie header is empty."
    override val appearanceHeader = "Appearance"
    override val themeCaption = "Theme — System follows your device's dark-mode setting."
    override val themeSystem = "System"
    override val themeLight = "Light"
    override val themeDark = "Dark"
    override val languageCaption = "Language — App language. System follows your device setting."
    override val languageSystem = "System"
    override val languageEnglish = "English"
    override val languageRussian = "Русский"
    override val manualImportHeader = "Manual import (advanced)"
    override val manualImportBody =
        "Paste the raw Cookie header and User-Agent copied from a browser where you are " +
            "signed in (DevTools → Network → first request → Request Headers). " +
            "Both values must come from the same browser — cf_clearance is bound to the " +
            "User-Agent. Step-by-step guide for each browser: docs/LOGIN.md in the repository."
    override val cookieHeaderLabel = "Cookie header"
    override val userAgentLabel = "User-Agent"
    override val importButton = "Import"
    override val networkHeader = "Network"
    override val wifiOnlyLabel = "Download on Wi-Fi only"
    override val mirrorUrlLabel = "Mirror URL"
    override val applyButton = "Apply"
    override fun concurrentDownloads(n: Int) = "Concurrent downloads: $n"
    override val aboutHeader = "About"
    override fun aboutVersion(version: String) = "Surge Media $version"
    override val aboutBody = "Movies, video and music, straight from rutracker to your library."

    override val storageHeader = "Storage"
    override val storageCaption = "Where finished downloads are saved. Music and video can go to separate folders."
    override val musicFolderLabel = "Music folder"
    override val videoFolderLabel = "Video folder"
    override val changeFolder = "Change"
    override val resetFolder = "Reset"

    override val chooseFilesTitle = "Choose files to download"
    override val addFilesTitle = "Add more files to download"
    override val addFiles = "Add files"
    override val downloaded = "Downloaded"
    override val selectAll = "Select all"
    override val selectNone = "None"
    override fun filesSummary(selected: Int, total: Int, selectedSize: String, totalSize: String) =
        "$selected of $total files · $selectedSize of $totalSize"

    override val signInTitle = "Sign in"
    override val saveSession = "Save session"
    override val desktopLoginBody =
        "Click the button and a Chrome, Edge or Yandex Browser window opens on the " +
            "tracker's login page — sign in there and the session returns to the app " +
            "automatically. With Safari or Firefox, use manual import below (docs/LOGIN.md)."
    override val signInViaBrowser = "Sign in via browser"
    override val signInBrowserWaiting =
        "Browser window open — sign in there. This dialog closes itself when done."
    override val signInBrowserValidating = "Session captured — validating…"
    override val signInBrowserClosed =
        "The browser window was closed before login finished."
    override val signInBrowserNotFound =
        "No Chromium-based browser found (Chrome, Edge, Yandex Browser). " +
            "Use manual import below."
    override fun signInBrowserLaunchFailed(detail: String) =
        "Couldn't launch the browser: $detail"
    override val unknownError = "unknown error"

    override val back = "Back"
    override val close = "Close"
}

/**
 * Russian strings — glossary terms verbatim, the rest natural informal-polite Russian
 * (no ты-forms), «ё» where natural, «…» quotes without inner padding.
 */
object RuStrings : AppStrings {
    override val tabSearch = "Поиск"
    override val tabDownloads = "Загрузки"
    override val tabDownloaded = "Скачанное"
    override val tabSettings = "Настройки"

    override val addedToDownloads = "Добавлено в загрузки."
    override fun downloadFailed(error: String) = "Не удалось скачать: $error"
    override val actionRetry = "Повторить"

    override val searchFieldLabel = "Поиск по rutracker"
    override val searchButton = "Поиск"
    override val searchIdleHint = "Поиск по rutracker — фильмы, видео, музыка, книги и другое."
    override fun noResults(query: String) = "Ничего не найдено по запросу «$query»."
    override val loadMore = "Показать ещё"
    override fun searchFailed(message: String) = "Ошибка поиска: $message"
    override val logIn = "Войти"
    override val loginHeroTitle = "Войти в rutracker"
    override val loginHeroBody =
        "Для поиска и скачивания нужна сессия rutracker. Войдите один раз во встроенном браузере."

    override val sortByLabel = "Сортировка"
    override val sortByRelevance = "По релевантности"
    override val sortBySeeds = "Раздачи"
    override val sortBySize = "Размер"
    override val sortByDate = "Дата"
    override val sortByTitle = "Название"

    override val detailsTitle = "Подробнее"
    override val detailForum = "Раздел"
    override val detailSize = "Размер"
    override val detailFormat = "Формат"
    override val detailSeedsLeechs = "Раздачи / Личи"
    override val detailDate = "Дата"
    override val detailTopicId = "ID темы"
    override val lastAttemptFailed = "Последняя попытка не удалась"
    override val fetchingTorrent = "Получение торрент-файла…"
    override val fetchingLabel = "Поиск…"
    override val addedOpenDownloads = "Добавлено — открыть загрузки"
    override val download = "Скачать"
    override val detailsPlaceholderTitle = "Тема не выбрана"
    override val detailsPlaceholderBody = "Выберите результат в списке, чтобы посмотреть подробности и скачать."

    override val stateQueued = "В очереди"
    override val stateDownloading = "Скачивается"
    override val statePaused = "На паузе"
    override val stateFinalizing = "Завершение"
    override val stateDone = "Готово"
    override val stateFailed = "Ошибка"
    override val stateCancelled = "Отменено"
    override val pause = "Пауза"
    override val resume = "Продолжить"
    override val cancel = "Отмена"
    override val remove = "Убрать"
    override val deleteFiles = "Удалить файлы"
    override val deleteAllFiles = "Удалить все файлы"
    override val clearHistory = "Очистить историю"
    override val emptyDownloads = "Пока нет загрузок — найдите фильм, альбом или сериал"
    override val confirmDeleteItemFiles = "Удалить скачанные файлы этого элемента с устройства?"
    override val confirmDeleteAllFiles = "Удалить ВСЕ файлы, скачанные Surge Media? Это действие нельзя отменить."
    override fun speedPerSecond(bytes: String) = "$bytes/с" // Cyrillic «с»: formatBytes units are already Cyrillic
    override fun peersCount(n: Int) = "$n " + ruPlural(n, "пир", "пира", "пиров")
    override fun deletedFiles(n: Int) =
        if (n % 10 == 1 && n % 100 != 11) "Удалён $n файл" else "Удалено $n " + ruPlural(n, "файл", "файла", "файлов")
    override fun deleteFailed(detail: String?) = "Не удалось удалить: ${detail ?: unknownError}"

    override val libraryHeader = "Скачанное"
    override val refresh = "Обновить"
    override val deleteAll = "Удалить все"
    override val delete = "Удалить"
    override val emptyLibrary = "Здесь пока пусто — завершённые загрузки появятся тут"
    override val libraryMusic = "Музыка"
    override val libraryMovies = "Фильмы"
    override fun fileCount(n: Int) = "$n " + ruPlural(n, "файл", "файла", "файлов")
    override fun confirmDeleteRelease(title: String, files: Int, size: String) =
        "Удалить «$title» (${fileCount(files)}, $size) с устройства?"
    override val confirmDeleteAllLibrary = "Удалить ВСЕ скачанные файлы из всех библиотек? Это действие нельзя отменить."
    override fun deletedFilesFor(n: Int, title: String) = "${deletedFiles(n)} — $title"
    override fun libraryReadFailed(detail: String?) = "Не удалось прочитать библиотеку: ${detail ?: unknownError}"

    override val kindAudio = "Аудио"
    override val kindVideo = "Видео"
    override val kindBook = "Книга"

    override val sessionHeader = "Сессия"
    override val notLoggedIn = "Вход не выполнен"
    override fun loggedInAt(timestamp: String) = "Вход выполнен (сохранён $timestamp)"
    override val logOut = "Выйти"
    override val validate = "Проверить"
    override val validationOk = "Сессия в порядке — пробный поиск прошёл успешно."
    override fun sessionCheckFailed(detail: String) = "Проверка сессии не удалась: $detail"
    override val importFailedEmptyCookie = "Импорт не удалён: заголовок Cookie пуст."
    override val appearanceHeader = "Оформление"
    override val themeCaption = "Тема — «Системная» следует настройке тёмной темы устройства."
    override val themeSystem = "Системная"
    override val themeLight = "Светлая"
    override val themeDark = "Тёмная"
    override val languageCaption = "Язык интерфейса. «Системный» следует настройке устройства."
    override val languageSystem = "Системный"
    override val languageEnglish = "English"
    override val languageRussian = "Русский"
    override val manualImportHeader = "Ручной импорт (для опытных)"
    override val manualImportBody =
        "Вставьте заголовок Cookie и User-Agent, скопированные из браузера, где выполнен вход " +
            "(DevTools → Network → первый запрос → заголовки запроса). " +
            "Оба значения должны быть из одного и того же браузера: cf_clearance привязан " +
            "к User-Agent. Пошаговая инструкция для каждого браузера — docs/LOGIN.md в репозитории."
    override val cookieHeaderLabel = "Заголовок Cookie"
    override val userAgentLabel = "User-Agent"
    override val importButton = "Импортировать"
    override val networkHeader = "Сеть"
    override val wifiOnlyLabel = "Скачивать только по Wi-Fi"
    override val mirrorUrlLabel = "URL зеркала"
    override val applyButton = "Применить"
    override fun concurrentDownloads(n: Int) = "Одновременные загрузки: $n"
    override val aboutHeader = "О приложении"
    override fun aboutVersion(version: String) = "Surge Media $version"
    override val aboutBody = "Фильмы, видео и музыка — прямо с rutracker в вашу библиотеку."

    override val storageHeader = "Хранилище"
    override val storageCaption = "Куда сохраняются завершённые загрузки. Музыку и видео можно разнести по разным папкам."
    override val musicFolderLabel = "Папка для музыки"
    override val videoFolderLabel = "Папка для видео"
    override val changeFolder = "Изменить"
    override val resetFolder = "Сбросить"

    override val chooseFilesTitle = "Выберите файлы для загрузки"
    override val addFilesTitle = "Добавьте файлы к загрузке"
    override val addFiles = "Добавить файлы"
    override val downloaded = "Загружено"
    override val selectAll = "Выбрать все"
    override val selectNone = "Ничего"
    override fun filesSummary(selected: Int, total: Int, selectedSize: String, totalSize: String) =
        "$selected из $total файлов · $selectedSize из $totalSize"

    override val signInTitle = "Вход"
    override val saveSession = "Сохранить сессию"
    override val desktopLoginBody =
        "Нажмите кнопку — откроется окно Chrome, Edge или Яндекс Браузера со страницей " +
            "входа трекера. Войдите в нём, и сессия вернётся в приложение автоматически. " +
            "Если пользуетесь Safari или Firefox — ручной импорт ниже (docs/LOGIN.md)."
    override val signInViaBrowser = "Войти через браузер"
    override val signInBrowserWaiting =
        "Окно браузера открыто — войдите в нём. Этот диалог закроется сам."
    override val signInBrowserValidating = "Сессия получена — проверяем…"
    override val signInBrowserClosed =
        "Окно браузера закрылось до завершения входа."
    override val signInBrowserNotFound =
        "Не найден браузер на Chromium (Chrome, Edge, Яндекс Браузер). " +
            "Используйте ручной импорт ниже."
    override fun signInBrowserLaunchFailed(detail: String) =
        "Не удалось запустить браузер: $detail"
    override val unknownError = "неизвестная ошибка"

    override val back = "Назад"
    override val close = "Закрыть"
}

/** Russian plural form: один/два-четыре/пять-и-дальше, with 11–14 always «many». */
private fun ruPlural(n: Int, one: String, few: String, many: String): String = when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}

/** Active [AppStrings]; provided by `App` from the language setting. Defaults to English. */
val LocalStrings = staticCompositionLocalOf<AppStrings> { EnStrings }

/** Resolve the strings for a language preference; SYSTEM follows [systemIsRussian]. */
fun stringsFor(language: AppLanguage, systemIsRussian: Boolean): AppStrings = when (language) {
    AppLanguage.ENGLISH -> EnStrings
    AppLanguage.RUSSIAN -> RuStrings
    AppLanguage.SYSTEM -> if (systemIsRussian) RuStrings else EnStrings
}
