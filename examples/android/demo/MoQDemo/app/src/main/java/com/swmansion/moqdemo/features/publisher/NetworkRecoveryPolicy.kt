package com.swmansion.moqdemo.features.publisher

/**
 * Decides whether a network down→up transition warrants rebuilding the publish data
 * plane (session + publisher).
 *
 * A wedged QUIC session keeps accepting enqueues without any egress (observed on
 * device: after a Wi-Fi off/on cycle the app looked healthy but the relay received
 * nothing), and there is no transport-level signal to detect it — so a long enough
 * outage is treated as potentially wedged and the data plane is rebuilt once the
 * network reports validated again. Brief outages (handover blips) leave QUIC
 * healthy, so rebuilding would be pure cost: those keep the current session.
 *
 * Pure decision logic, unit-tested on the JVM; the ViewModel owns the actual timing.
 */
class NetworkRecoveryPolicy(
    /** Outages shorter than this keep the existing session. */
    val minOutageMs: Long = DEFAULT_MIN_OUTAGE_MS,
    /** How long after the network is validated again the rebuild should fire. */
    val stabilizeMs: Long = DEFAULT_STABILIZE_MS,
) {
    private var downSinceMs: Long? = null

    /** Records a network-down transition at [nowMs]. */
    fun onNetworkDown(nowMs: Long) {
        if (downSinceMs == null) downSinceMs = nowMs
    }

    /**
     * Records a network-up (validated) transition at [nowMs].
     *
     * @return the outage duration when it was long enough to warrant a data-plane
     *   rebuild (to be scheduled after [stabilizeMs]), or null to keep the current
     *   session.
     */
    fun onNetworkUp(nowMs: Long): Long? {
        val since = downSinceMs
        downSinceMs = null
        if (since == null) return null
        val outageMs = nowMs - since
        return if (outageMs >= minOutageMs) outageMs else null
    }

    companion object {
        const val DEFAULT_MIN_OUTAGE_MS = 1_000L
        const val DEFAULT_STABILIZE_MS = 2_500L
    }
}
