package com.swmansion.moqkit.publish.source.internal

/**
 * At-most-once-per-session delivery latch for a capture source's
 * permanent-failure callback.
 *
 * Failure and registration race freely: the capture thread reports whenever it
 * dies, while [com.swmansion.moqkit.publish.Publisher] registers the callback
 * only when the track starts — and apps typically start the source BEFORE the
 * publisher, so an init-time failure precedes registration. Whichever side
 * arrives second triggers the single delivery; the first reported message wins.
 *
 * Every capture session gets a fresh session token from [rearm]; [report]
 * carries the token of the session it belongs to and is dropped on mismatch.
 * This makes cross-session contamination structurally impossible: a stale
 * capture thread that outlives its own session (stop() interrupts but does not
 * join the read thread) can never fire the restarted session's callback.
 *
 * [close] is the owner-initiated stop: once it completes, all past and future
 * delivery for the session is suppressed. A report that wins the lock BEFORE
 * close() completes is a genuine death that linearized before the stop and may
 * still fire — suppression is close-then-quiet, not retroactive; callers must
 * treat a TrackError racing a deliberate stop as a real pre-stop death.
 * [rearm] resets the latch for a fresh capture session when the same source
 * instance is started again.
 *
 * Delivery runs outside the internal lock — a callback may re-enter the latch
 * (for example clear itself) without deadlocking.
 *
 * Free of Android runtime dependencies so the host-JVM suite can pin the
 * ordering, session-isolation, and at-most-once contract.
 */
internal class SourceErrorLatch {
    private val lock = Any()
    private var callbackField: ((String) -> Unit)? = null
    private var pending: String? = null
    private var delivered = false
    private var closed = false
    private var sessionField = 0L

    /** The current session token, as handed out by the latest [rearm] (0 before any). */
    val session: Long
        get() = synchronized(lock) { sessionField }

    /**
     * The registered failure callback. Setting a non-null callback while an
     * undelivered failure is pending delivers that failure immediately.
     */
    var callback: ((String) -> Unit)?
        get() = synchronized(lock) { callbackField }
        set(value) {
            var fire: ((String) -> Unit)? = null
            var message: String? = null
            synchronized(lock) {
                callbackField = value
                val msg = pending
                if (value != null && msg != null && !delivered && !closed) {
                    delivered = true
                    fire = value
                    message = msg
                }
            }
            message?.let { fire?.invoke(it) }
        }

    /**
     * Records a permanent failure of the given capture [session] and delivers
     * it once if a callback is registered. Dropped entirely when the session
     * token is stale or the latch is closed; later same-session reports never
     * replace the first message.
     */
    fun report(session: Long, message: String) {
        var fire: ((String) -> Unit)? = null
        var toSend: String? = null
        synchronized(lock) {
            if (closed || session != sessionField) return
            if (pending == null) pending = message
            val cb = callbackField
            if (cb != null && !delivered) {
                delivered = true
                fire = cb
                toSend = pending
            }
        }
        toSend?.let { fire?.invoke(it) }
    }

    /**
     * Owner-initiated stop: clears the callback and suppresses all delivery
     * from the moment it completes. A concurrent [report] that acquires the
     * lock first still fires — that death linearized before the stop.
     */
    fun close() {
        synchronized(lock) {
            closed = true
            callbackField = null
        }
    }

    /**
     * Re-arms for a fresh capture session and returns its session token; any
     * previous session's failure is obsolete and its late reports are dropped.
     */
    fun rearm(): Long {
        synchronized(lock) {
            closed = false
            pending = null
            delivered = false
            return ++sessionField
        }
    }
}
