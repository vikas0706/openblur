package io.github.vikas0706.openblur.analysis

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Lets a new scan wait until scans that are shutting down have let go of their decoder. Hardware decoders are
 * a limited resource, so a scan started right after a cancel could otherwise fail to open one.
 */
internal class TeardownGate {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var pending = 0

    fun begin() {
        lock.withLock { pending++ }
    }

    fun end() {
        lock.withLock {
            if (pending > 0) pending--
            if (pending == 0) idle.signalAll()
        }
    }

    /** Returns when nothing is shutting down, after [timeoutMs], or once [cancelled] turns true, whichever is first. */
    fun awaitIdle(timeoutMs: Long, cancelled: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (pending > 0 && !cancelled()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return
                try {
                    idle.awaitNanos(minOf(remaining, POLL_NANOS))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private companion object {
        // [cancelled] is polled because nothing signals the condition when a waiting scan is cancelled.
        val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50)
    }
}
