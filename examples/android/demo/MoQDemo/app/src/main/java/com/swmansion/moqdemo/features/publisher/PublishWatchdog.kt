package com.swmansion.moqdemo.features.publisher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the frame write activity of a running publish session and reports when the
 * uplink stalls or recovers.
 *
 * Two signals feed the state machine:
 *  - Frame write timestamps. `writeFrame` only enqueues into the transport, so writes
 *    can keep "succeeding" while the network is dead; silence is therefore a strong
 *    signal but activity is a weak one.
 *  - Network availability ([setNetworkDown]), driven by a ConnectivityManager
 *    NetworkCallback. While no validated internet network exists, the publish is
 *    reported as [State.STALLED] immediately, no matter how fresh the enqueues are,
 *    because nothing can actually leave the device.
 *
 * A QUIC session can survive a network gap (the relay keeps it alive until its idle
 * timeout), so a network outage alone does not kill the session: [State.STALLED] is
 * held until the network returns or the writes also stop. Escalation to [State.DEAD]
 * happens when no frame is written for [deadAfterMs] — with or without a network —
 * which means the enqueue path itself is blocked or broken and the publish should be
 * restarted. Between [warnAfterMs] and [deadAfterMs] of silence (network up) the
 * publish is reported as stalled so the UI can flag it.
 *
 * The clock ([nowMs]) and the poll timer ([ticker]) are injectable so unit tests can
 * drive time deterministically.
 */
class PublishWatchdog(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val warnAfterMs: Long = DEFAULT_WARN_AFTER_MS,
    private val deadAfterMs: Long = DEFAULT_DEAD_AFTER_MS,
    private val ticker: suspend () -> Unit = { delay(DEFAULT_POLL_INTERVAL_MS) },
) {
    enum class State {
        /** Frames are flowing. */
        HEALTHY,

        /** No frame written for at least `warnAfterMs`, or no validated network. */
        STALLED,

        /** No frame written for at least `deadAfterMs`; the publish should be restarted. */
        DEAD,
    }

    /** Current watchdog state. */
    var state: State = State.HEALTHY
        private set

    /** Called on the polling coroutine (or from [setNetworkDown]) whenever [state] changes. */
    var onStateChanged: ((State) -> Unit)? = null

    private var job: Job? = null
    private var startedAtMs = 0L
    private var lastWriteSampler: (() -> Long)? = null

    @Volatile
    private var networkDown = false

    /**
     * Starts polling [lastWriteAtMs] on [scope]. Any previously started polling is
     * stopped. The watchdog starts in [State.HEALTHY] with a grace period: until the
     * first frame is written, silence is measured from this call.
     */
    fun start(scope: CoroutineScope, lastWriteAtMs: () -> Long) {
        stop()
        startedAtMs = nowMs()
        lastWriteSampler = lastWriteAtMs
        transition(State.HEALTHY)
        job = scope.launch {
            while (isActive) {
                ticker()
                if (!isActive) break
                val next = evaluate(nowMs(), lastWriteAtMs())
                if (next != state) transition(next)
            }
        }
    }

    /** Stops polling. Safe to call more than once. */
    fun stop() {
        job?.cancel()
        job = null
        lastWriteSampler = null
    }

    /**
     * Feeds the network-availability signal. Losing the last validated network moves
     * the watchdog to [State.STALLED] at once; regaining it re-evaluates immediately,
     * returning to [State.HEALTHY] when frames are flowing again.
     */
    fun setNetworkDown(down: Boolean) {
        if (networkDown == down) return
        networkDown = down
        val sampler = lastWriteSampler ?: return
        val next = evaluate(nowMs(), sampler())
        if (next != state) transition(next)
    }

    /**
     * Computes the watchdog state from a timestamp of the last frame write
     * (0 if no frame was written yet) at the given time. [networkDown] defaults to
     * the value last reported via [setNetworkDown] so tests can inject it directly.
     */
    fun evaluate(nowMs: Long, lastWriteAtMs: Long, networkDown: Boolean = this.networkDown): State {
        val referenceMs = if (lastWriteAtMs > 0L) lastWriteAtMs else startedAtMs
        val silentMs = nowMs - referenceMs
        if (networkDown) {
            // Nothing can leave the device: flag the stall regardless of enqueue
            // activity, but keep the session alive while enqueues continue — QUIC
            // can resume on its own when the network returns. Escalate only when
            // the enqueue path itself stalls, which means it is blocked/broken.
            return if (silentMs >= deadAfterMs) State.DEAD else State.STALLED
        }
        return when {
            silentMs >= deadAfterMs -> State.DEAD
            silentMs >= warnAfterMs -> State.STALLED
            else -> State.HEALTHY
        }
    }

    private fun transition(next: State) {
        state = next
        onStateChanged?.invoke(next)
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        const val DEFAULT_WARN_AFTER_MS = 4_000L
        const val DEFAULT_DEAD_AFTER_MS = 8_000L
    }
}
