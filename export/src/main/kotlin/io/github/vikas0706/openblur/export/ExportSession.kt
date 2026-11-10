package io.github.vikas0706.openblur.export

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import io.github.vikas0706.openblur.api.ExportRequest
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.api.MaskEffectFactory
import io.github.vikas0706.openblur.api.SourceInspector
import io.github.vikas0706.openblur.core.EncoderPolicy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class StopReason { USER, TIME_LIMIT }

/** A stop request that the side asking for it and the job acting on it both hold. */
internal open class StopHandle {
    private val stop = AtomicReference<StopReason?>(null)

    /** Set by the job that runs this ticket; invoked from any thread after a stop was requested. */
    @Volatile
    var onStopRequested: (() -> Unit)? = null

    val stopReason: StopReason? get() = stop.get()

    /** The first reason wins, so a time limit that lands during a user cancel still reads as a cancel. */
    fun requestStop(reason: StopReason) {
        stop.compareAndSet(null, reason)
        onStopRequested?.invoke()
    }
}

/** One requested export, from the controller's start call until its final state is published. */
internal class ExportTicket(
    val request: ExportRequest,
    val policy: EncoderPolicy,
    val maskEffects: MaskEffectFactory,
    val inspector: SourceInspector,
    /** SystemClock.elapsedRealtime() at the start call. */
    val startedAtMs: Long,
) : StopHandle()

/**
 * Process-wide meeting point of the controller, which the UI talks to, and the service, which does the
 * work. The service is started by an Intent and cannot be handed objects, so it collects the ticket here.
 */
internal object ExportSession : SessionState<ExportTicket>()

/** The export state and the one ticket that may change it. Every function is safe to call from any thread. */
internal open class SessionState<T : StopHandle> {
    private val lock = Any()
    private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle)
    private var current: T? = null
    private var claimed = false

    val state: StateFlow<ExportState> = mutableState.asStateFlow()

    /** Registers [ticket] and moves to Running. False while another export is running. */
    fun begin(ticket: T, initial: ExportState.Running): Boolean = synchronized(lock) {
        if (current != null || mutableState.value is ExportState.Running) return false
        current = ticket
        claimed = false
        mutableState.value = initial
        true
    }

    /** Called by the service: the ticket waiting to be run, or null when there is none. */
    fun claim(): T? = synchronized(lock) {
        val ticket = current
        if (ticket == null || claimed) return null
        claimed = true
        ticket
    }

    fun update(ticket: T, running: ExportState.Running) = synchronized(lock) {
        if (current === ticket) mutableState.value = running
    }

    /** Publishes the final state of [ticket]. Ignored when the ticket is no longer the current one. */
    fun finish(ticket: T, terminal: ExportState) = synchronized(lock) {
        if (current === ticket) {
            current = null
            claimed = false
            mutableState.value = terminal
        }
    }

    /** For the case where the service never came up to collect the ticket. */
    fun finishIfUnclaimed(ticket: T, terminal: ExportState) = synchronized(lock) {
        if (current === ticket && !claimed) {
            current = null
            mutableState.value = terminal
        }
    }

    fun cancel() {
        val running = synchronized(lock) {
            val ticket = current ?: return
            if (!claimed) {
                // Nothing has started yet; the service finds no ticket and stops.
                current = null
                mutableState.value = ExportState.Cancelled
                return
            }
            ticket
        }
        // Outside the lock: the hook posts to the export thread, which publishes through this object.
        running.requestStop(StopReason.USER)
    }

    fun reset() = synchronized(lock) {
        if (current == null && mutableState.value !is ExportState.Running) mutableState.value = ExportState.Idle
    }
}

/** Tells whether an activity of the app is on screen, to decide if a finished export needs a notification. */
internal object AppVisibility : Application.ActivityLifecycleCallbacks {
    private val registered = AtomicBoolean(false)
    private val startedActivities = AtomicInteger(0)

    fun register(context: Context) {
        val application = context.applicationContext as? Application ?: return
        if (registered.compareAndSet(false, true)) application.registerActivityLifecycleCallbacks(this)
    }

    fun isVisible(): Boolean {
        if (startedActivities.get() > 0) return true
        // Covers an activity that started before the callbacks were registered.
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities.updateAndGet { count -> maxOf(0, count - 1) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
