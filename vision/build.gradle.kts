import java.nio.ByteBuffer
import java.nio.ByteOrder
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

// ONNX Runtime 1.28 for Windows needs msvcp140.dll 14.40 or newer, and a JVM loads the copy in its own
// bin folder before the system one. Oracle JDK 21.0.8 ships 14.36 there, so the library fails to load in it.

/** Major and minor version of the msvcp140.dll a JDK bundles, as major * 65536 + minor; null when it bundles none. */
fun bundledMsvcpVersion(javaHome: File): Int? {
    val dll = File(javaHome, "bin/msvcp140.dll")
    if (!dll.isFile) return null
    val bytes = dll.readBytes()
    // The version resource holds 0xFEEF04BD, a structure version, then the file version.
    val signature = byteArrayOf(0xBD.toByte(), 0x04, 0xEF.toByte(), 0xFE.toByte())
    val at = (0..bytes.size - 16).firstOrNull { i -> signature.indices.all { bytes[i + it] == signature[it] } }
        ?: return 0
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(at + 8)
}

fun canLoadOnnxRuntime(javaHome: File): Boolean {
    if (!File(javaHome, "bin/java.exe").isFile) return false
    val release = File(javaHome, "release")
    val major = if (!release.isFile) 0 else release.readLines()
        .firstOrNull { it.startsWith("JAVA_VERSION=") }
        ?.substringAfter('"')?.substringBefore('.')?.substringBefore('"')?.toIntOrNull() ?: 0
    if (major < 17) return false
    val msvcp = bundledMsvcpVersion(javaHome) ?: return true
    return msvcp >= 14 * 65536 + 40
}

/** A JDK to run the tests on when the build's own JVM cannot load ONNX Runtime, or null to use the build's JVM. */
fun onnxRuntimeTestJavaHome(): File? {
    val chosen = providers.gradleProperty("openblur.testJavaHome")
        .orElse(providers.environmentVariable("OPENBLUR_TEST_JAVA_HOME")).orNull
    if (chosen != null) return File(chosen)
    if (!System.getProperty("os.name").startsWith("Windows")) return null
    if (canLoadOnnxRuntime(File(System.getProperty("java.home")))) return null

    val programFiles = File(System.getenv("ProgramFiles") ?: "C:/Program Files")
    val userHome = File(System.getProperty("user.home"))
    val parents = listOf(
        File(userHome, ".jdks"),
        File(userHome, ".gradle/jdks"),
        File(programFiles, "Microsoft"),
        File(programFiles, "Eclipse Adoptium"),
        File(programFiles, "Java"),
        File(programFiles, "Zulu"),
        File(programFiles, "Amazon Corretto"),
        File(programFiles, "BellSoft"),
    )
    val candidates = parents.flatMap { it.listFiles()?.sorted().orEmpty() } +
        File(programFiles, "Android/Android Studio/jbr")
    return candidates.firstOrNull { canLoadOnnxRuntime(it) }
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
    systemProperty("openblur.modelsDir", rootProject.file("models").absolutePath)
    systemProperty("openblur.referenceModelsDir", rootProject.file("_dl/models").absolutePath)
    onnxRuntimeTestJavaHome()?.let { javaHome ->
        val java = File(javaHome, if (File(javaHome, "bin/java.exe").isFile) "bin/java.exe" else "bin/java")
        executable = java.absolutePath
        val note = "vision tests run on ${java.absolutePath}"
        doFirst { logger.lifecycle(note) }
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
