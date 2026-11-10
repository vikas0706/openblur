import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    // The app supplies onnxruntime-android at run time; both artifacts expose the same ai.onnxruntime API.
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
    systemProperty("openblur.modelsDir", rootProject.file("models").absolutePath)
    systemProperty("openblur.referenceModelsDir", rootProject.file("_dl/models").absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
