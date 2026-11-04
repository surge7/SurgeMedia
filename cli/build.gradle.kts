plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}
application { mainClass = "com.surgemusic.cli.MainKt" }

tasks.test {
    // JUnit5 backend: with useJUnitPlatform() configured, kotlin("test") resolves the
    // kotlin-test-junit5 variant instead of the JUnit4 default.
    useJUnitPlatform()
    // Blank reads as "unset" for the isNullOrBlank() env checks in Credentials.kt, so the
    // file-loading tests exercise the injected base dir even on machines that export real
    // RUTRACKER_* values.
    environment("RUTRACKER_COOKIES", "")
    environment("RUTRACKER_USER", "")
    environment("RUTRACKER_PASS", "")
}
