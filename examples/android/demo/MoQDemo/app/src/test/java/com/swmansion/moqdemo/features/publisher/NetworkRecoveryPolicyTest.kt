package com.swmansion.moqdemo.features.publisher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkRecoveryPolicyTest {

    @Test
    fun briefOutage_keepsSession() {
        val policy = NetworkRecoveryPolicy()
        policy.onNetworkDown(nowMs = 10_000)
        // 500ms blip (e.g. a network handover): QUIC survives this on its own, so a
        // rebuild would be pure cost.
        assertNull(policy.onNetworkUp(nowMs = 10_500))
    }

    @Test
    fun longOutage_requestsRebuildWithOutageDuration() {
        val policy = NetworkRecoveryPolicy()
        policy.onNetworkDown(nowMs = 10_000)
        // The observed wedged-session case: ~10s with no validated network.
        assertEquals(10_000L, policy.onNetworkUp(nowMs = 20_000))
    }

    @Test
    fun outageExactlyAtThreshold_requestsRebuild() {
        val policy = NetworkRecoveryPolicy(minOutageMs = 1_000)
        policy.onNetworkDown(nowMs = 10_000)
        assertEquals(1_000L, policy.onNetworkUp(nowMs = 11_000))
    }

    @Test
    fun networkUpWithoutPriorDown_keepsSession() {
        val policy = NetworkRecoveryPolicy()
        assertNull(policy.onNetworkUp(nowMs = 10_000))
    }

    @Test
    fun secondOutageIsMeasuredFromItsOwnStart() {
        val policy = NetworkRecoveryPolicy()
        policy.onNetworkDown(nowMs = 10_000)
        assertEquals(5_000L, policy.onNetworkUp(nowMs = 15_000))

        // A later, separate outage starts a fresh measurement.
        policy.onNetworkDown(nowMs = 30_000)
        assertNull(policy.onNetworkUp(nowMs = 30_500))
    }

    @Test
    fun repeatedDownEventsKeepTheOriginalStart() {
        val policy = NetworkRecoveryPolicy()
        policy.onNetworkDown(nowMs = 10_000)
        // Flapping: down callbacks repeat before any up; the outage still counts
        // from the first loss.
        policy.onNetworkDown(nowMs = 12_000)
        assertEquals(9_000L, policy.onNetworkUp(nowMs = 19_000))
    }
}
