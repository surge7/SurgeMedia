pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://dl.frostwire.com/maven") { name = "frostwire" }
    }
}
rootProject.name = "SurgeMusic"
include(":core", ":cli")
