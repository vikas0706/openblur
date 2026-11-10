package io.github.vikas0706.openblur.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ONNX Runtime could not be loaded, rejected a model, or failed during a run.
 * Unchecked so it passes through the core interfaces; the cause is the original error.
 */
class ModelException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal object OrtRuntime {
    /** The process-wide environment. ONNX Runtime keeps a single one and never needs it closed. */
    fun environment(): OrtEnvironment =
        try {
            OrtEnvironment.getEnvironment()
        } catch (e: LinkageError) {
            // A native library that fails to load surfaces as an Error, which callers would not catch.
            throw ModelException("ONNX Runtime could not be loaded", e)
        } catch (e: RuntimeException) {
            throw ModelException("ONNX Runtime could not be loaded", e)
        }

    /** CPU provider only. [configure] runs last, for options that depend on the model. */
    fun createSession(
        modelBytes: ByteArray,
        threads: Int,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession {
        val env = environment()
        try {
            OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setIntraOpNumThreads(threads)
                options.setInterOpNumThreads(1)
                // Spinning keeps idle worker threads busy-waiting between runs, which drains the battery.
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                // The SFace file lists its weights as graph inputs, which logs a warning per weight on every load.
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
                configure(options)
                return env.createSession(modelBytes, options)
            }
        } catch (e: OrtException) {
            throw ModelException("The model could not be loaded: ${e.message}", e)
        }
    }

    fun directFloats(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** The tensor reads and writes [data] in place, so refilling the buffer changes what the next run sees. */
    fun tensorOver(data: FloatBuffer, vararg shape: Long): OnnxTensor {
        rewind(data)
        return OnnxTensor.createTensor(environment(), data, shape)
    }

    // Buffer's own position and clear exist on every Android release; the ByteBuffer and
    // FloatBuffer overrides the compiler would otherwise pick arrived with Java 9.
    fun seek(buffer: Buffer, position: Int) {
        buffer.position(position)
    }

    fun rewind(buffer: Buffer) {
        buffer.clear()
    }
}

/** Closes what it owns in reverse order of creation. One resource failing does not stop the others closing. */
internal class NativeResources {
    private val owned = ArrayList<AutoCloseable>()

    fun <T : AutoCloseable> own(resource: T): T {
        owned.add(resource)
        return resource
    }

    fun closeAll() {
        var failure: Exception? = null
        for (i in owned.indices.reversed()) {
            try {
                owned[i].close()
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        owned.clear()
        if (failure != null) throw ModelException("A native resource did not close cleanly", failure)
    }
}
