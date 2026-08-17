package com.swmansion.moqkit.publish.source

import com.swmansion.moqkit.publish.source.internal.AudioPtsCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the capture loop's delivery selection: the consumer must receive the
 * SAMPLE-CLOCK chunk timestamp from [AudioPtsCounter], never the wall-clock
 * read-return time — wall-clock stamping is exactly the v4.13 behavior that
 * tripped the C2 encoder's overlap clamp every chunk (metallic-noise round,
 * 2026-08-11). The thread/AudioRecord half of the loop cannot run on the
 * host JVM; everything between read-return and onPcmData lives in
 * [MicrophoneCapture.deliverChunk] so it can be verified here.
 */
class MicrophoneCaptureDeliveryTest {

    @Test
    fun deliversSampleClockTimestampNotWallClock() {
        val pts = AudioPtsCounter(48_000, bytesPerFrame = 2)
        val buf = ByteArray(7_680) // 3840 mono frames = one 80ms chunk
        var deliveredSize = -1
        var deliveredTs = -1L
        val chunk = MicrophoneCapture.deliverChunk(buf, 7_680, nowUs = 1_000_000L, pts) { _, size, ts ->
            deliveredSize = size
            deliveredTs = ts
        }
        assertNotNull(chunk)
        assertEquals(7_680, deliveredSize)
        // First chunk anchors at its capture START (read-return minus 80ms),
        // which visibly differs from the wall clock handed in.
        assertEquals(920_000L, deliveredTs)

        // Second chunk advances by exactly one chunk of sample time even
        // though the wall clock jitters +13ms — passing nowUs through
        // instead of the counter's timestamp fails both assertions.
        MicrophoneCapture.deliverChunk(buf, 7_680, nowUs = 1_093_000L, pts) { _, _, ts ->
            deliveredTs = ts
        }
        assertEquals(1_000_000L, deliveredTs)
    }

    @Test
    fun subFrameReadDeliversNothing() {
        val pts = AudioPtsCounter(48_000, bytesPerFrame = 2)
        var called = false
        val chunk = MicrophoneCapture.deliverChunk(ByteArray(4), 1, nowUs = 5L, pts) { _, _, _ ->
            called = true
        }
        assertNull(chunk)
        assertFalse(called)
        assertEquals(0L, pts.framesDelivered)
    }

    @Test
    fun tornTrailingByteIsTruncatedToWholeFrames() {
        val pts = AudioPtsCounter(48_000, bytesPerFrame = 4)
        var deliveredSize = -1
        MicrophoneCapture.deliverChunk(ByteArray(1_638), 1_638, nowUs = 1_000_000L, pts) { _, size, _ ->
            deliveredSize = size
        }
        assertEquals(1_636, deliveredSize) // 409 whole stereo frames
        assertEquals(409L, pts.framesDelivered)
    }
}
