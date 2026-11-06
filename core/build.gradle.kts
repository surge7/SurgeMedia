import org.gradle.api.tasks.testing.Test
import org.gradle.testretry.TestRetryTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Host OS selector for the desktop jlibtorrent native, below. Values: "mac" | "windows" | "linux".
val hostOs: String = run {
    val name = System.getProperty("os.name").lowercase()
    when {
        name.contains("win") -> "windows"
        name.contains("nix") || name.contains("nux") -> "linux"
        else -> "mac"
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    // @Serializable for PersistedDownload (QueuePersistence); the json runtime is already
    // a commonMain dependency.
    alias(libs.plugins.kotlin.serialization)
    // Retry flaky JVM tests (EngineSmokeTest drives the real jlibtorrent native).
    alias(libs.plugins.test.retry)
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        // shared by desktop JVM and Android: Ktor, Jsoup, engine
        val jvmShared by creating { dependsOn(commonMain.get()) }
        jvmMain {
            dependsOn(jvmShared)
            // Desktop native per host OS (jlibtorrent's loader picks lib/<arch>/ by
            // os.name/os.arch, but each artifact carries exactly one platform binary, so
            // the host picks which one lands in the app). Packaging is host-OS-bound too —
            // packageDmg runs on macOS, packageMsi on Windows — so each installer ships
            // exactly its own native and nothing else. The pure-Java jlibtorrent bindings
            // come via jvmShared.
            dependencies {
                when (hostOs) {
                    "windows" -> implementation(libs.jlibtorrent.windows)
                    "linux" -> implementation(libs.jlibtorrent.linux.x8664)
                    else -> implementation(libs.jlibtorrent.macosx.arm64)
                }
            }
        }
        androidMain {
            dependsOn(jvmShared)
            dependencies {
                implementation(libs.jlibtorrent)
                implementation(libs.jlibtorrent.android.arm64)
                implementation(libs.jlibtorrent.android.x8664)
            }
        }
        jvmShared.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.jsoup)
            implementation(libs.jlibtorrent)
        }
        val jvmSharedTest by creating { dependsOn(commonTest.get()) }
        jvmTest {
            dependsOn(jvmSharedTest)
            // loopback engine tests stay here (natives come from jvmMain, per host OS)
        }
        androidUnitTest {
            dependsOn(jvmSharedTest)
        }
        jvmSharedTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mockwebserver)
        }
    }
    jvmToolchain(21)
}

// Retry the native-driving jvmTest (EngineSmokeTest) — CI occasionally flakes on the
// jlibtorrent native. `retry` is the TestRetryTaskExtension the plugin adds to Test tasks;
// Kotlin DSL has no generated accessor for it, so configure it by type.
tasks.named<Test>("jvmTest") {
    extensions.configure<TestRetryTaskExtension> { maxRetries.set(2) }
}

// Same retry parity for the Android unit-test variant of the shared suite — it runs the
// same DownloadManager race tests (e.g. addFilesRacingCompletionRestartsWithUnion) that
// can time out under CI load.
tasks.withType<Test>().matching { it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest" }.configureEach {
    extensions.configure<TestRetryTaskExtension> { maxRetries.set(2) }
}

android {
    namespace = "com.surgemusic.core"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    sourceSets {
        // AGP's unit-test runtime does not consume resources from KMP intermediate
        // source sets — expose the shared test fixtures directly to AGP
        getByName("test") {
            resources.srcDir("src/jvmSharedTest/resources")
        }
    }
}
