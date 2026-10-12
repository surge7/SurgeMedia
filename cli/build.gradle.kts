plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
}
application { mainClass = "com.surgemusic.cli.MainKt" }
