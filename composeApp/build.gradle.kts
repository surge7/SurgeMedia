import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    }
    jvmToolchain(21)
}

compose.desktop.application {
    // JvmApplication.mainClass is a plain String var in compose-gradle-plugin 1.8.1
    // (no Property.set) — assign directly.
    mainClass = "com.surgemusic.app.MainKt"
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
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
