package com.swmansion.moqdemo.features.publisher

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class PublishWatchdogTest {

    @Test
    fun evaluate_isHealthyBelowWarnThreshold() {
        val wd = PublishWatchdog(warnAfterMs = 4_000, deadAfterMs = 8_000)
        assertEquals(
            PublishWatchdog.State.HEALTHY,
            wd.evaluate(nowMs = 13_999, lastWriteAtMs = 10_000),
        )
    }

    @Test
    fun evaluate_isStalledAtWarnThreshold() {
        val wd = PublishWatchdog(warnAfterMs = 4_000, deadAfterMs = 8_000)
        assertEquals(
            PublishWatchdog.State.STALLED,
            wd.evaluate(nowMs = 14_000, lastWriteAtMs = 10_000),
        )
    }

    @Test
    fun evaluate_isDeadAtDeadThreshold() {
        val wd = PublishWatchdog(warnAfterMs = 4_000, deadAfterMs = 8_000)
        assertEquals(
            PublishWatchdog.State.DEAD,
            wd.evaluate(nowMs = 18_000, lastWriteAtMs = 10_000),
        )
    }

    @Test
    fun evaluate_recoversWhenWritesResume() {
        val wd = PublishWatchdog(warnAfterMs = 4_000, deadAfterMs = 8_000)
        assertEquals(
            PublishWatchdog.State.DEAD,
            wd.evaluate(nowMs = 18_000, lastWriteAtMs = 10_000),
        )
        // A fresh write close to "now" brings the publish back to healthy.
        assertEquals(
            PublishWatchdog.State.HEALTHY,
            wd.evaluate(nowMs = 18_500, lastWriteAtMs = 18_000),
        )
    }

    @Test
    fun evaluate_beforeFirstWrite_usesStartTimeAsGracePeriod() = runBlocking {
        val now = 10_000L
        val wd = PublishWatchdog(
            nowMs = { now },
            warnAfterMs = 4_000,
            deadAfterMs = 8_000,
        )
        wd.start(this) { 0L }
        wd.stop()

        assertEquals(PublishWatchdog.State.HEALTHY, wd.evaluate(nowMs = 13_999, lastWriteAtMs = 0))
        assertEquals(PublishWatchdog.State.STALLED, wd.evaluate(nowMs = 14_000, lastWriteAtMs = 0))
        assertEquals(PublishWatchdog.State.DEAD, wd.evaluate(nowMs = 18_000, lastWriteAtMs = 0))
    }

    @Test
    fun pollingLoop_reportsStalledThenDeadAsClockAdvances() {
        var now = 0L
        val transitions = mutableListOf<PublishWatchdog.State>()
        lateinit var wd: PublishWatchdog
        wd = PublishWatchdog(
            nowMs = { now },
            warnAfterMs = 4_000,
            deadAfterMs = 8_000,
            ticker = {
                // Injected timer: each poll advances the fake clock by one second
                // instead of waiting in real time.
                now += 1_000
                if (wd.state == PublishWatchdog.State.DEAD) wd.stop() else yield()
            },
        )
        wd.onStateChanged = { transitions += it }

        // runBlocking waits for the polling coroutine, which stops itself at DEAD.
        runBlocking { wd.start(this) { 0L } }

        assertEquals(
            listOf(
                PublishWatchdog.State.HEALTHY,
                PublishWatchdog.State.STALLED,
                PublishWatchdog.State.DEAD,
            ),
            transitions,
        )
    }

    @Test
    fun pollingLoop_recoversToHealthyWhenWritesResume() {
        var now = 0L
        var lastWrite = 0L
        val transitions = mutableListOf<PublishWatchdog.State>()
        lateinit var wd: PublishWatchdog
        wd = PublishWatchdog(
            nowMs = { now },
            warnAfterMs = 4_000,
            deadAfterMs = 8_000,
            ticker = {
                now += 1_000
                // Simulate the network coming back: a frame is written at t=6s.
                if (now == 6_000L) lastWrite = now
                if (now >= 10_000L) wd.stop() else yield()
            },
        )
        wd.onStateChanged = { transitions += it }

        runBlocking { wd.start(this) { lastWrite } }

        assertEquals(
            listOf(
                PublishWatchdog.State.HEALTHY,
                PublishWatchdog.State.STALLED,
                PublishWatchdog.State.HEALTHY,
            ),
            transitions,
        )
    }

    @Test
    fun evaluate_networkDown_isStalledEvenWithFreshWrites() {
        val wd = PublishWatchdog(warnAfterMs = 4_000, deadAfterMs = 8_000)
        // Enqueues keep succeeding (last write is "now"), but nothing can leave
        // the device: the publish must be flagged stalled, not healthy.
        assertEquals(
            PublishWatchdog.State.STALLED,
            wd.evaluate(nowMs = 10_000, lastWriteAtMs = 10_000, networkDown = true),
        )
    }

    @Test
    fun evaluate_networkDown_isDeadWhenWritesAlsoStop() {
        val wd = PublishWatchdog(warnAfterMs = 4_000, deadAfterMs = 8_000)
        // Network gone AND the enqueue path itself silent past the dead threshold:
        // the write path is blocked/broken, so escalate to the reconnect path.
        assertEquals(
            PublishWatchdog.State.DEAD,
            wd.evaluate(nowMs = 18_000, lastWriteAtMs = 10_000, networkDown = true),
        )
    }

    @Test
    fun evaluate_networkRecovered_clearsStallWhenWritesFlow() {
        val wd = PublishWatchdog(warnAfterMs = 4_000, deadAfterMs = 8_000)
        assertEquals(
            PublishWatchdog.State.STALLED,
            wd.evaluate(nowMs = 10_000, lastWriteAtMs = 10_000, networkDown = true),
        )
        // The network returns and enqueues never stopped: back to healthy without
        // killing the session (QUIC survived the gap).
        assertEquals(
            PublishWatchdog.State.HEALTHY,
            wd.evaluate(nowMs = 10_500, lastWriteAtMs = 10_400, networkDown = false),
        )
    }

    @Test
    fun setNetworkDown_transitionsImmediatelyWithoutWaitingForPoll() = runBlocking {
        var now = 0L
        val transitions = mutableListOf<PublishWatchdog.State>()
        val wd = PublishWatchdog(
            nowMs = { now },
            warnAfterMs = 4_000,
            deadAfterMs = 8_000,
            ticker = { yield() },
        )
        wd.onStateChanged = { transitions += it }

        wd.start(this) { now }
        wd.setNetworkDown(true)
        assertEquals(PublishWatchdog.State.STALLED, wd.state)

        // Enqueues continue while the network is down: the stall must not reset.
        now += 1_000
        wd.setNetworkDown(true)
        assertEquals(PublishWatchdog.State.STALLED, wd.state)

        // Network restored with frames still flowing: cleared at once.
        wd.setNetworkDown(false)
        assertEquals(PublishWatchdog.State.HEALTHY, wd.state)
        wd.stop()

        assertEquals(
            listOf(
                PublishWatchdog.State.HEALTHY,
                PublishWatchdog.State.STALLED,
                PublishWatchdog.State.HEALTHY,
            ),
            transitions,
        )
    }
}
