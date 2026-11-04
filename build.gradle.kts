plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    // pinned at root so subprojects resolve the versioned plugin markers on the classpath
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    // Retries flaky JVM tests (native/Chromium flakes in CI); applied to :core and :composeApp.
    alias(libs.plugins.test.retry) apply false
}
