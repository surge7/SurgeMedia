package com.surgemusic.app

import java.awt.EventQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory

/**
 * `Dispatchers.Main` for the desktop JVM (plan Task 6 fix): kotlinx-coroutines resolves
 * Main only through a `META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory`
 * provider, and neither kotlinx-coroutines-swing nor -android is on the desktop runtime
 * classpath (`:composeApp:dependencies --configuration jvmRuntimeClasspath` shows only
 * kotlinx-coroutines-core; the brief allows no new dependency). commonMain's
 * SearchViewModel/SettingsViewModel build scopes on `Dispatchers.Main` in their
 * constructors, so without a provider every search/validation launch dies with the missing
 * Main dispatcher IllegalStateException.
 *
 * [DesktopMainDispatcherFactory] is registered in
 * `desktopMain/resources/META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory`
 * and installs [AwtEdtMainDispatcher] — the AWT event-dispatch thread, i.e. the same thread
 * coroutines-swing's dispatcher uses (`SwingUtilities.invokeLater` delegates to
 * `EventQueue.invokeLater`) and the thread Compose Desktop composes and renders on.
 * Platform twin of what androidMain gets for free (Dispatchers.Main = the Android main
 * thread). If an owner later prefers the real `kotlinx-coroutines-swing` dependency, delete
 * this file and the services entry together.
 */
internal object AwtEdtMainDispatcher : MainCoroutineDispatcher() {
    override val immediate: MainCoroutineDispatcher = Immediate

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !EventQueue.isDispatchThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        EventQueue.invokeLater(block)
    }

    /** Same semantics as androidMain's `HandlerContext` immediate: run inline on the EDT. */
    private object Immediate : MainCoroutineDispatcher() {
        override val immediate: MainCoroutineDispatcher get() = this

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !EventQueue.isDispatchThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            EventQueue.invokeLater(block)
        }
    }
}

/** Service-loader provider for the desktop Main dispatcher (see [AwtEdtMainDispatcher] doc). */
@OptIn(InternalCoroutinesApi::class)
internal class DesktopMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int = Int.MAX_VALUE

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher =
        AwtEdtMainDispatcher

    override fun hintOnError(): String? = null
}
