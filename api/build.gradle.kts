plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.vikas0706.openblur.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        optIn.add("androidx.media3.common.util.UnstableApi")
    }
}

dependencies {
    api(project(":core"))
    api(libs.androidx.media3.common)
    api(libs.kotlinx.coroutines.core)
}
