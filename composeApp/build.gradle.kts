import org.gradle.api.tasks.testing.Test
import org.gradle.testretry.TestRetryTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
    // Retry flaky JVM tests (BrowserLoginCaptureTest launches a real Chromium).
    alias(libs.plugins.test.retry)
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    // Desktop JVM target — :composeApp:compileKotlinJvm keeps commonMain multiplatform-clean.
    // Desktop-only deps (compose.desktop.currentOs) and Main.kt land in plan Task 5.
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.core)
        }
        // Shared by desktop and android (TrackerAppFactory — the AppContainer members that
        // were near-verbatim on both platforms: tracker client, prepare, startDownload,
        // validate probe). Sources here are compiled into BOTH the jvm and android target
        // compilations; hand-wired like :core's jvmShared (the default hierarchy template
        // is disabled — see gradle.properties).
        val appShared = create("appShared") { dependsOn(commonMain.get()) }
        // Desktop platform layer for the jvm target (plan Tasks 5/6 add the rest of it).
        // The default hierarchy template is disabled, so wire dependsOn manually —
        // same pattern as :core's jvmShared.
        val desktopMain = create("desktopMain") {
            dependsOn(commonMain.get())
            dependsOn(appShared)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            // Browser sign-in (CDP cookie capture) talks to a locally-launched Chromium
            // over OkHttp's WebSocket; core exposes ktor/okhttp only as implementation.
            implementation(libs.okhttp)
            implementation(libs.kotlinx.serialization.json)
            // Windows-only DWM caption theming (WindowsWindowChrome) + DPAPI session vault
            // (Crypt32Util, SessionVault); the libraries load lazily and are never touched
            // on macOS/Linux. Desktop-only so the Android APK never carries them.
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
        jvmMain {
            dependsOn(desktopMain)
        }
        androidMain {
            dependsOn(appShared)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // SAF folder destinations (Settings ▸ Storage): writing/listing/deleting in
            // user-chosen trees via DocumentFile.
            implementation(libs.androidx.documentfile)
            // QueuePersistence JSON codec (desktopMain already carries it)
            implementation(libs.kotlinx.serialization.json)
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // Desktop library tests run on the JVM target (jvmTest depends on jvmMain →
        // desktopMain, so DesktopLibrary & co. are visible). Runs via :composeApp:jvmTest.
        val jvmTest by getting
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
    jvmToolchain(21)
}

// Retry the real-Chromium jvmTest (BrowserLoginCaptureTest) against CI browser flake.
// `retry` is the TestRetryTaskExtension the plugin adds to Test tasks; Kotlin DSL has no
// generated accessor for it, so configure it by type.
tasks.named<Test>("jvmTest") {
    extensions.configure<TestRetryTaskExtension> { maxRetries.set(2) }
}

// Host OS of the machine running Gradle — it selects the desktop jlibtorrent native
// (see :core/build.gradle.kts), so every desktop installer built here carries this
// host's native and packaging is host-OS-bound (packageDmg on macOS, packageMsi/packageExe
// on Windows). The INFO line makes what was picked visible in CI logs at configuration time.
val hostOsName = System.getProperty("os.name").lowercase()
val macOSHost = hostOsName.contains("mac")
val windowsHost = hostOsName.contains("win")
val desktopNative = when {
    windowsHost -> "com.frostwire:jlibtorrent-windows"
    hostOsName.contains("nix") || hostOsName.contains("nux") -> "com.frostwire:jlibtorrent-linux-x86_64"
    else -> "com.frostwire:jlibtorrent-macosx-arm64"
}
logger.lifecycle("composeApp: host OS '$hostOsName' → desktop jlibtorrent native '$desktopNative'")

compose.desktop.application {
    // JvmApplication.mainClass is a plain String var in compose-gradle-plugin 1.12.0
    // (no Property.set) — assign directly.
    mainClass = "com.surgemusic.app.MainKt"

    // Without a target format no packaging task (packageDmg & co.) is registered. Each
    // format's task runs on its own host OS (packageDmg on macOS, packageMsi/packageExe
    // on Windows), so listing every shipped format is safe on any dev machine or runner.
    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
        packageName = "SurgeMedia"
        // Installer version: both package managers require MAJOR.MINOR.BUILD >= 1.0.0
        // (the app version itself stays in versionName / Version.APP).
        packageVersion = "1.4.0"

        if (macOSHost) {
            // Finder-launched apps have CWD=/ so the JVM's default ./hs_err_pid*.log is
            // unwritable and a native crash leaves no trace; /tmp always works.
            jvmArgs("-XX:ErrorFile=/tmp/hs_err_surgemedia_pid%p.log")
        }
        // Windows needs no ErrorFile override: per-user installs live under
        // %LOCALAPPDATA%\SurgeMedia and the launcher's CWD (that dir) is writable.

        // Without these the packaged installers fall back to the plugin's default Compose
        // icon. SurgeMedia.icns/.ico are generated by design/icons/render_{macos,windows}.sh.
        if (macOSHost) {
            macOS {
                iconFile.set(file("src/desktopMain/resources/icons/SurgeMedia.icns"))
            }
        }
        if (windowsHost) {
            windows {
                iconFile.set(file("src/desktopMain/resources/icons/SurgeMedia.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                // Per-user install: no UAC elevation, and the install dir (under
                // %LOCALAPPDATA%) is writable by the app — crash logs included.
                perUserInstall = true
                // Fixed across releases so the MSI major-upgrade finds and replaces the
                // previous install instead of installing side-by-side duplicates.
                upgradeUuid = "4D0CED5D-CCE5-4B3A-A7FD-84C45FB04D21"
            }
        }
    }
}

// Cross-packaging guard (fail fast): the native inside any desktop installer is the one
// the Gradle host picked (see [desktopNative] above), so packageMsi/packageExe only
// produce a correct Windows installer on a Windows host and packageDmg only on macOS.
// packageDistributionForCurrentOS picks the host-appropriate format by construction and
// deliberately has no guard.
//
// Two layers, one message: (1) a configuration-time check of the requested task names,
// so a wrong-host invocation fails before ANY task work — an earlier chain task such as
// checkRuntime could otherwise fail or run long first and mask the real reason; (2)
// doFirst on the packaging tasks themselves, catching invocations that bypass the
// command line (e.g. another task wiring them as a dependency) at execution start,
// before the long jpackage/installer step.
fun packagingHostError(taskNames: String, requiredHost: String): GradleException = GradleException(
    "$taskNames must run on a $requiredHost host: the desktop jlibtorrent native is selected " +
        "by the host OS and this host is ${System.getProperty("os.name")} " +
        "(packaging '$desktopNative'). Use packageDistributionForCurrentOS for the " +
        "host-appropriate format."
)

val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }.toSet()
if (!windowsHost && requestedTaskNames.any { it == "packageMsi" || it == "packageExe" }) {
    throw packagingHostError("packageMsi/packageExe", "Windows")
}
if (!macOSHost && "packageDmg" in requestedTaskNames) {
    throw packagingHostError("packageDmg", "macOS")
}

tasks.matching { it.name == "packageMsi" || it.name == "packageExe" }.configureEach {
    doFirst {
        if (!windowsHost) throw packagingHostError("packageMsi/packageExe", "Windows")
    }
}
tasks.matching { it.name == "packageDmg" }.configureEach {
    doFirst {
        if (!macOSHost) throw packagingHostError("packageDmg", "macOS")
    }
}

android {
    namespace = "com.surgemusic.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.surgemusic.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.4.0"
    }
    // Env-driven release signing: when all four SURGEMUSIC_* vars are present (store
    // upload, CI secret), releases are signed with the real keystore.
    val env = System.getenv()
    val hasReleaseKeystore = env.containsKey("SURGEMUSIC_KEYSTORE_PATH") &&
        env.containsKey("SURGEMUSIC_KEYSTORE_PASSWORD") &&
        env.containsKey("SURGEMUSIC_KEY_ALIAS") &&
        env.containsKey("SURGEMUSIC_KEY_PASSWORD")
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(env["SURGEMUSIC_KEYSTORE_PATH"] ?: "")
                storePassword = env["SURGEMUSIC_KEYSTORE_PASSWORD"]
                keyAlias = env["SURGEMUSIC_KEY_ALIAS"]
                keyPassword = env["SURGEMUSIC_KEY_PASSWORD"]
            }
        }
    }
    buildTypes {
        release {
            // Minification is off: R8 + jlibtorrent/JNI keep rules are unverified; revisit before enabling.
            isMinifyEnabled = false
            // Without the env vars, fall back to the debug keystore — local/CI release
            // builds then produce a debug-signed APK (fine for testing; never for store upload).
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // lintVital must not block store packaging during 0.x; run :composeApp:lint explicitly when needed.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
