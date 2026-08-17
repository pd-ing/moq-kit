package com.swmansion.moqkit.publish.source

import android.view.Surface

/**
 * Advanced extension point for custom video capture sources.
 *
 * Most apps should use [CameraCapture], [MultiCameraCapture], or [ScreenCapture]. Implement
 * this interface only when the app needs to feed frames from its own rendering or capture
 * pipeline.
 */
interface VideoFrameSource {
    /**
     * Connects the source to the encoder input surface.
     *
     * [com.swmansion.moqkit.publish.Publisher] calls this when a video track starts.
     */
    fun attachEncoderSurface(surface: Surface)

    /**
     * Disconnects the encoder surface.
     *
     * [com.swmansion.moqkit.publish.Publisher] calls this when a video track stops.
     */
    fun detachEncoderSurface()

    /**
     * Sets an optional preview surface for local display.
     *
     * Pass `null` to clear the preview.
     */
    fun setPreviewSurface(surface: Surface?)
}

/**
 * Advanced extension point for custom audio capture sources.
 *
 * Most apps should use [MicrophoneCapture]. Implement this interface only when the app
 * needs to publish PCM samples from another source.
 */
interface AudioFrameSource {
    /**
     * Callback that receives PCM 16-bit audio samples.
     *
     * [com.swmansion.moqkit.publish.Publisher] sets this when an audio track starts and
     * clears it when the track stops. The timestamp is in microseconds.
     */
    var onPcmData: ((data: ByteArray, size: Int, timestampUs: Long) -> Unit)?

    /**
     * Optional callback fired at most once per capture session when the source
     * permanently stops producing audio on its own — an unrecoverable capture failure.
     * A deliberate stop suppresses the signal from the moment it runs; a death that
     * races ahead of the stop is a genuine pre-stop failure and may still fire.
     *
     * Without this signal a dead source is indistinguishable from silence: encoders keep
     * running and a broadcast silently continues video-only.
     * [com.swmansion.moqkit.publish.Publisher] sets this when an audio track starts and
     * clears it when the track stops, surfacing the failure as
     * [com.swmansion.moqkit.publish.PublisherEvent.TrackError].
     *
     * The default accessors discard registrations, so sources that cannot fail
     * permanently need not override. Sources that can die on their own should override
     * with real storage and fire the registered callback when capture ends — including
     * delivering a failure that happened BEFORE registration, since sources are often
     * started before the publisher attaches (see [MicrophoneCapture]).
     */
    var onSourceError: ((message: String) -> Unit)?
        get() = null
        set(value) {}
}
