package io.github.vikas0706.openblur.analysis

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeardownGateTest {
    private fun elapsedMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    @Test
    fun returnsAtOnceWhenNothingIsShuttingDown() {
        val gate = TeardownGate()
        assertTrue(elapsedMs { gate.awaitIdle(5_000) { false } } < 1_000)
    }

    @Test
    fun waitsForEveryTeardownToEnd() {
        val gate = TeardownGate()
        gate.begin()
        gate.begin()
        val released = AtomicBoolean(false)
        val waiting = CountDownLatch(1)
        val waiter = thread {
            waiting.countDown()
            gate.awaitIdle(20_000) { false }
            released.set(true)
        }
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        gate.end()
        Thread.sleep(200)
        assertFalse("one teardown is still running", released.get())
        gate.end()
        waiter.join(5_000)
        assertTrue(released.get())
    }

    @Test
    fun givesUpAfterTheTimeout() {
        val gate = TeardownGate()
        gate.begin()
        val waited = elapsedMs { gate.awaitIdle(300) { false } }
        assertTrue("waited $waited ms", waited in 250..5_000)
    }

    @Test
    fun stopsWaitingWhenTheWaiterIsCancelled() {
        val gate = TeardownGate()
        gate.begin()
        val cancelled = AtomicBoolean(false)
        val waiter = thread { gate.awaitIdle(60_000) { cancelled.get() } }
        Thread.sleep(100)
        assertTrue(waiter.isAlive)
        cancelled.set(true)
        waiter.join(5_000)
        assertFalse(waiter.isAlive)
    }

    @Test
    fun extraEndCallsDoNotUnbalanceTheCount() {
        val gate = TeardownGate()
        gate.end()
        gate.begin()
        val waited = elapsedMs { gate.awaitIdle(300) { false } }
        assertTrue("an earlier stray end must not cancel a later begin, waited $waited ms", waited >= 250)
    }
}
