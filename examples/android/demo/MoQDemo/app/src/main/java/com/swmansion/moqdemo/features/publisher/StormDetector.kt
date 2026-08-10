package com.swmansion.moqdemo.features.publisher

/**
 * AND-V48-001: detects a reconnect "framing storm" — consecutive publishes
 * that die within seconds of coming up. 실기기 확정 storm mode: process-scoped
 * native transport corruption kills every new session at its first keyframe
 * ~2.5-4.4s after "publish gen up"; neither Stop/Publish nor a full chain
 * restart clears it (only an app process restart does), so once detected the
 * reconnect loop must stop retrying instead of deepening the native churn.
 *
 * Pure logic, main-thread confined (like [NetworkRecoveryPolicy]).
 */
class StormDetector(
    private val shortLifetimeMs: Long = PublisherViewModel.STORM_SESSION_LIFETIME_MS,
    private val threshold: Int = PublisherViewModel.STORM_CONSECUTIVE_THRESHOLD,
) {

    /** Consecutive publishes that died within [shortLifetimeMs] of coming up. */
    var consecutiveShortLived = 0
        private set

    /**
     * Records a session death that is entering the reconnect path.
     *
     * @param publishUpAtMs when this session's "publish gen up" happened, or
     *   null when the death did not follow a successful publish (a failed
     *   connect attempt) — those carry no storm signal and leave the count.
     * @return true when the storm threshold is reached.
     */
    fun recordSessionEnd(publishUpAtMs: Long?, nowMs: Long): Boolean {
        if (publishUpAtMs != null) {
            consecutiveShortLived =
                if (nowMs - publishUpAtMs < shortLifetimeMs) consecutiveShortLived + 1 else 0
        }
        return consecutiveShortLived >= threshold
    }

    /** A publish proved stable (dwell elapsed) or the run ended — start over. */
    fun reset() {
        consecutiveShortLived = 0
    }
}
