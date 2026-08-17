package com.swmansion.moqkit.publish.source.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPtsCounterTest {

    // 48kHz mono with the ~80ms chunks observed on device (3840 frames).
    @Test
    fun anchorsFirstChunkAtItsCaptureStart() {
        val counter = AudioPtsCounter(48_000)
        val pts = counter.onChunk(3840, nowUs = 1_000_000L)
        // Read returned at 1_000_000 for an 80_000us chunk -> started at 920_000.
        assertEquals(920_000L, pts)
    }

    @Test
    fun advancesExactlyBySampleTimeIgnoringReadJitter() {
        val counter = AudioPtsCounter(48_000)
        var now = 1_000_000L
        val jitterUs = longArrayOf(0, 9_000, -4_000, 12_000, -7_000, 3_000, 0, 10_772)
        var previous = counter.onChunk(3840, now)
        for (j in jitterUs) {
            now += 80_000 + j
            val pts = counter.onChunk(3840, now)
            // The wall clock jitters; the timeline must not.
            assertEquals(80_000L, pts - previous)
            previous = pts
        }
    }

    // The C2 encoder trips its overlap clamp when an input PTS lands behind
    // its own per-chunk accumulation (last PTS + floor(frames*1e6/rate)).
    // The cumulative-floor timeline must never do that, including at rates
    // where per-frame duration is not an integral microsecond count.
    @Test
    fun neverStepsBehindPerChunkFlooredAccumulation() {
        for (rate in intArrayOf(48_000, 44_100, 16_000)) {
            val counter = AudioPtsCounter(rate)
            var now = 5_000_000L
            val chunkSizes = intArrayOf(1837, 1024, 3840, 941, 2048, 1837, 4096, 333)
            var lastPts = counter.onChunk(chunkSizes[0], now)
            var lastFrames = chunkSizes[0]
            for (round in 0 until 500) {
                val frames = chunkSizes[(round + 1) % chunkSizes.size]
                now += frames * 1_000_000L / rate
                val pts = counter.onChunk(frames, now)
                val encoderExpectedStart = lastPts + lastFrames * 1_000_000L / rate
                assertTrue(
                    "rate=$rate round=$round pts=$pts expected>=$encoderExpectedStart",
                    pts >= encoderExpectedStart
                )
                lastPts = pts
                lastFrames = frames
            }
        }
    }

    @Test
    fun staysWithinOneMicrosecondOfTrueRationalTimeline() {
        val rate = 44_100
        val counter = AudioPtsCounter(rate)
        val base = counter.onChunk(1837, nowUs = 41_655L) // anchor
        var frames = 1837L
        for (round in 0 until 2000) {
            val pts = counter.onChunk(1837, nowUs = 0L) // nowUs unused after anchor
            val trueUs = frames * 1_000_000.0 / rate
            val err = (pts - base) - trueUs
            assertTrue("round=$round err=$err", err <= 0.0 && err > -1.0)
            frames += 1837
        }
    }

    @Test
    fun wallDeltaTracksClockDivergence() {
        val counter = AudioPtsCounter(48_000)
        var now = 1_000_000L
        counter.onChunk(3840, now)
        assertEquals(0L, counter.wallDeltaUs(now))
        // Wall clock runs 100us/chunk ahead of the sample clock.
        for (i in 1..10) {
            now += 80_100
            counter.onChunk(3840, now)
        }
        assertEquals(1_000L, counter.wallDeltaUs(now))
    }

    @Test
    fun countsDeliveredFrames() {
        val counter = AudioPtsCounter(48_000)
        counter.onChunk(3840, 0L)
        counter.onChunk(1024, 0L)
        assertEquals(4864L, counter.framesDelivered)
    }

    // The capture loop feeds raw AudioRecord byte counts straight into
    // onChunkBytes — the byte->frame conversion, the PTS advance and the
    // delivered size must all come from the same whole-frame count. Feeding
    // bytes into the frame-domain timeline (2x mono / 4x stereo rate) is the
    // regression class this pins.
    @Test
    fun convertsBytesToWholeFramesForBothSizeAndPts() {
        val counter = AudioPtsCounter(48_000, bytesPerFrame = 4)
        // 409 whole stereo frames + 2 stray bytes.
        val first = requireNotNull(counter.onChunkBytes(1_638, nowUs = 1_000_000L))
        assertEquals(1_636, first.sizeBytes)
        assertEquals(409L, counter.framesDelivered) // frames, NOT bytes
        val second = requireNotNull(counter.onChunkBytes(1_636, nowUs = 5_000_000L))
        // Timeline advanced by exactly 409 frames of sample time, wall clock ignored.
        assertEquals(first.timestampUs + 409L * 1_000_000L / 48_000L, second.timestampUs)
        assertEquals(818L, counter.framesDelivered)
    }

    @Test
    fun subFrameReadDeliversNothingAndAdvancesNothing() {
        val counter = AudioPtsCounter(48_000, bytesPerFrame = 4)
        assertNull(counter.onChunkBytes(3, nowUs = 1_000_000L))
        assertEquals(0L, counter.framesDelivered)
        // The anchor is still unset: the first real chunk anchors normally.
        val chunk = requireNotNull(counter.onChunkBytes(4_096, nowUs = 2_000_000L))
        assertEquals(2_000_000L - 1_024L * 1_000_000L / 48_000L, chunk.timestampUs)
    }
}
