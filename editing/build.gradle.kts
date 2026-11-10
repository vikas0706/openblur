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
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
    // The allocation test must see every object sample() creates, including ones the JIT could optimise away.
    jvmArgs("-XX:+IgnoreUnrecognizedVMOptions", "-XX:-DoEscapeAnalysis")
    systemProperty("openblur.modelsDir", rootProject.file("models").absolutePath)
    systemProperty("openblur.referenceModelsDir", rootProject.file("_dl/models").absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
