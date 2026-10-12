import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
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
            dependencies { implementation(libs.jlibtorrent.macosx.arm64) }
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
            // loopback engine tests stay here (macOS natives)
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

android {
    namespace = "com.surgemusic.core"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    sourceSets {
        // AGP's unit-test runtime does not consume resources from KMP intermediate
        // source sets — expose the shared test fixtures directly to AGP
        getByName("test") {
            resources.srcDir("src/jvmSharedTest/resources")
        }
    }
}
