package com.swmansion.moqkit.publish

import com.swmansion.moqkit.publish.encoder.AudioCodec
import com.swmansion.moqkit.publish.source.AudioFrameSource
import com.swmansion.moqkit.publish.source.MicrophoneCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Publisher side of the capture-death signal. Publisher.start()
 * cannot run on the host JVM (its field initializers construct the native
 * MoqBroadcastProducer), so the wiring lives in the [Publisher.wireSourceError]
 * / [Publisher.clearSourceError] seam and is driven here directly: on source
 * death the track's final StateFlow state must be Stopped (the late-subscriber
 * signal — events replay nothing) and exactly one TrackError with the source's
 * message must be emitted. The one-line call sites in startAudioTrack /
 * tearDownAudioTrack are covered by review, same as the capture-thread half of
 * MicrophoneCapture.
 */
class PublisherSourceErrorWiringTest {

    private class FakeAudioSource : AudioFrameSource {
        override var onPcmData: ((data: ByteArray, size: Int, timestampUs: Long) -> Unit)? = null
        override var onSourceError: ((message: String) -> Unit)? = null
    }

    private fun track() = PublishedTrack(
        name = "mic",
        codecInfo = TrackCodecInfo.Audio(AudioCodec.AAC, 48_000),
    )

    @Test
    fun sourceDeathTransitionsTrackToStoppedAndEmitsTrackError() {
        val source = FakeAudioSource()
        val trackHandle = track()
        val events = mutableListOf<PublisherEvent>()
        val logs = mutableListOf<String>()
        // Mimic startAudioTrack order: the track is Starting before wiring.
        trackHandle.transition(PublishedTrackState.Starting)
        Publisher.wireSourceError(source, trackHandle, { events.add(it) }, { logs.add(it) })
        assertNotNull(source.onSourceError)
        assertEquals(emptyList<PublisherEvent>(), events)

        source.onSourceError?.invoke("audio capture read failed 25 times in a row (last=-32)")

        assertEquals(PublishedTrackState.Stopped, trackHandle.state.value)
        assertEquals(
            listOf<PublisherEvent>(
                PublisherEvent.TrackError(
                    "mic",
                    "audio capture read failed 25 times in a row (last=-32)",
                ),
            ),
            events,
        )
        assertEquals(1, logs.size)
    }

    @Test
    fun alreadyDeadMicrophoneFiresDuringWiring() {
        // Integration across the seam: a MicrophoneCapture whose init failed
        // BEFORE the publisher attached must surface during wiring itself, and
        // the final track state must be Stopped even though the Starting
        // transition already happened.
        val mic = MicrophoneCapture()
        mic.sourceError.report(mic.sourceError.session, "AudioRecord initialization failed")
        val trackHandle = track()
        val events = mutableListOf<PublisherEvent>()
        trackHandle.transition(PublishedTrackState.Starting)

        Publisher.wireSourceError(mic, trackHandle, { events.add(it) }, {})

        assertEquals(PublishedTrackState.Stopped, trackHandle.state.value)
        assertEquals(
            listOf<PublisherEvent>(
                PublisherEvent.TrackError("mic", "AudioRecord initialization failed"),
            ),
            events,
        )
    }

    @Test
    fun clearSourceErrorClearsRegistration() {
        val source = FakeAudioSource()
        Publisher.wireSourceError(source, track(), {}, {})
        assertNotNull(source.onSourceError)
        Publisher.clearSourceError(source)
        assertNull(source.onSourceError)
    }

    @Test
    fun clearSourceErrorIsNullSafe() {
        Publisher.clearSourceError(null)
    }
}
