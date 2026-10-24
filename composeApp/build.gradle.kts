import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
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
        // Desktop platform layer for the jvm target (plan Tasks 5/6 add the rest of it).
        // The default hierarchy template is disabled, so wire dependsOn manually —
        // same pattern as :core's jvmShared.
        val desktopMain by creating { dependsOn(commonMain.get()) }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            // Browser sign-in (CDP cookie capture) talks to a locally-launched Chromium
            // over OkHttp's WebSocket; core exposes ktor/okhttp only as implementation.
            implementation(libs.okhttp)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain {
            dependsOn(desktopMain)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
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

compose.desktop.application {
    // JvmApplication.mainClass is a plain String var in compose-gradle-plugin 1.8.1
    // (no Property.set) — assign directly.
    mainClass = "com.surgemusic.app.MainKt"

    // Without a target format no packaging task (packageDmg & co.) is registered.
    nativeDistributions {
        targetFormats(TargetFormat.Dmg)
        packageName = "SurgeMedia"
        // macOS installer requires MAJOR >= 1; app version stays in versionName (0.2.0).
        packageVersion = "1.0.0"
    }
}

android {
    namespace = "com.surgemusic.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.surgemusic.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
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
                storeFile = file(env["SURGEMUSIC_KEYSTORE_PATH"])
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
