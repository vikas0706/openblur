package io.github.vikas0706.openblur.export

import io.github.vikas0706.openblur.api.ExportFailure
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    private val session = SessionState<StopHandle>()
    private val preparing = ExportState.Running(ExportPhase.PREPARING, 0f, null, 0L)
    private val failed = ExportState.Failed(ExportFailure.ENCODER_FAILED, "detail")

    @Test
    fun startsIdle() {
        assertEquals(ExportState.Idle, session.state.value)
        assertNull(session.claim())
    }

    @Test
    fun beginPublishesRunningAndASecondBeginIsIgnored() {
        assertTrue(session.begin(StopHandle(), preparing))
        assertEquals(preparing, session.state.value)
        assertFalse(session.begin(StopHandle(), preparing.copy(elapsedMs = 5L)))
        assertEquals(preparing, session.state.value)
    }

    @Test
    fun theServiceClaimsATicketOnce() {
        val ticket = StopHandle()
        session.begin(ticket, preparing)
        assertSame(ticket, session.claim())
        assertNull(session.claim())
    }

    @Test
    fun onlyTheCurrentTicketCanUpdateOrFinish() {
        val ticket = StopHandle()
        val stale = StopHandle()
        session.begin(ticket, preparing)
        val encoding = ExportState.Running(ExportPhase.ENCODING, 0.4f, null, 900L)

        session.update(stale, encoding)
        session.finish(stale, failed)
        assertEquals(preparing, session.state.value)

        session.update(ticket, encoding)
        assertEquals(encoding, session.state.value)
        session.finish(ticket, failed)
        assertEquals(failed, session.state.value)

        // A late update from the finished job must not bring Running back.
        session.update(ticket, encoding)
        assertEquals(failed, session.state.value)
    }

    @Test
    fun aNewExportCanBeginFromAFinishedStateWithoutReset() {
        val first = StopHandle()
        session.begin(first, preparing)
        session.finish(first, failed)
        assertTrue(session.begin(StopHandle(), preparing))
        assertEquals(preparing, session.state.value)
    }

    @Test
    fun cancelBeforeTheServiceClaimsEndsTheExportAtOnce() {
        val ticket = StopHandle()
        session.begin(ticket, preparing)
        session.cancel()
        assertEquals(ExportState.Cancelled, session.state.value)
        assertNull(session.claim())
        assertNull(ticket.stopReason)
    }

    @Test
    fun cancelAfterTheClaimAsksTheJobToStopAndLeavesTheStateToIt() {
        val ticket = StopHandle()
        val hookCalls = AtomicInteger()
        ticket.onStopRequested = { hookCalls.incrementAndGet() }
        session.begin(ticket, preparing)
        session.claim()

        session.cancel()
        assertEquals(StopReason.USER, ticket.stopReason)
        assertEquals(1, hookCalls.get())
        assertEquals(preparing, session.state.value)

        session.finish(ticket, ExportState.Cancelled)
        assertEquals(ExportState.Cancelled, session.state.value)
    }

    @Test
    fun cancelWithNothingRunningDoesNothing() {
        session.cancel()
        assertEquals(ExportState.Idle, session.state.value)

        val ticket = StopHandle()
        session.begin(ticket, preparing)
        session.finish(ticket, failed)
        session.cancel()
        assertEquals(failed, session.state.value)
    }

    @Test
    fun resetReturnsFinishedStatesToIdleAndLeavesARunningExportAlone() {
        val ticket = StopHandle()
        session.begin(ticket, preparing)
        session.reset()
        assertEquals(preparing, session.state.value)

        session.finish(ticket, failed)
        session.reset()
        assertEquals(ExportState.Idle, session.state.value)

        session.begin(ticket, preparing)
        session.cancel()
        session.reset()
        assertEquals(ExportState.Idle, session.state.value)
    }

    @Test
    fun aTicketTheServiceNeverCollectedCanBeFailed() {
        val ticket = StopHandle()
        session.begin(ticket, preparing)
        session.finishIfUnclaimed(ticket, failed)
        assertEquals(failed, session.state.value)
        assertNull(session.claim())
    }

    @Test
    fun aCollectedTicketIsNotFailedByTheStartTimeout() {
        val ticket = StopHandle()
        session.begin(ticket, preparing)
        session.claim()
        session.finishIfUnclaimed(ticket, failed)
        assertEquals(preparing, session.state.value)
    }

    @Test
    fun theFirstStopReasonWins() {
        val ticket = StopHandle()
        ticket.requestStop(StopReason.USER)
        ticket.requestStop(StopReason.TIME_LIMIT)
        assertEquals(StopReason.USER, ticket.stopReason)
    }

    @Test
    fun onlyOneOfManyConcurrentBeginsWins() {
        val threads = 16
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val wins = AtomicInteger()
        val workers = List(threads) {
            thread {
                ready.countDown()
                go.await()
                if (session.begin(StopHandle(), preparing)) wins.incrementAndGet()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        workers.forEach { it.join(5_000) }
        assertEquals(1, wins.get())
        assertEquals(preparing, session.state.value)
    }

    @Test
    fun finishAndCancelFromDifferentThreadsLeaveOneFinalState() {
        repeat(200) {
            val local = SessionState<StopHandle>()
            val ticket = StopHandle()
            local.begin(ticket, preparing)
            local.claim()
            val go = CountDownLatch(1)
            val finisher = thread {
                go.await()
                local.finish(ticket, failed)
            }
            val canceller = thread {
                go.await()
                local.cancel()
            }
            go.countDown()
            finisher.join(5_000)
            canceller.join(5_000)
            // The job publishes the outcome; a cancel that lost the race must not overwrite it.
            assertEquals(failed, local.state.value)
        }
    }
}
