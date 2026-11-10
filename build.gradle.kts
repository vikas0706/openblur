buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP 9 bundles an older Kotlin Gradle plugin; this raises it to the catalog version
        // for both the Android module (built-in Kotlin) and the plain JVM :core module.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
