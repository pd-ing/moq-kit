package com.swmansion.moqkit.publish

import android.util.Log
import com.swmansion.moqkit.UnsupportedCodecException
import com.swmansion.moqkit.publish.encoder.AudioEncoder
import com.swmansion.moqkit.publish.encoder.AudioEncoderConfig
import com.swmansion.moqkit.publish.encoder.VideoEncoder
import com.swmansion.moqkit.publish.encoder.VideoEncoderConfig
import com.swmansion.moqkit.publish.source.AudioFrameSource
import com.swmansion.moqkit.publish.source.VideoFrameSource
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.moq.MoqBroadcastProducer
import uniffi.moq.MoqMediaProducer
import uniffi.moq.MoqTrackProducer

private const val TAG = "Publisher"

/**
 * Point-in-time snapshot of frame write activity for a [Publisher].
 *
 * Counters cover frames handed to the transport after [Publisher.start]. They are updated
 * from encoder callback threads and are safe to read from any thread, for example from a
 * watchdog that detects a stalled uplink when [lastWriteAtMs] stops advancing.
 */
class PublishActivity internal constructor(
    /** Frames successfully written to the transport since [Publisher.start]. */
    val framesWritten: Long,
    /** Payload bytes successfully written to the transport since [Publisher.start]. */
    val bytesWritten: Long,
    /** `System.currentTimeMillis()` of the last successful frame write, or 0 if none yet. */
    val lastWriteAtMs: Long,
    /** Frame writes that raised an error since [Publisher.start]. */
    val writeErrors: Long,
    /**
     * Last successful VIDEO frame write (0 if none). v4.12: the aggregate
     * [lastWriteAtMs] stays fresh while audio alone flows, which let a
     * video-dead broadcast read as healthy on the 실기기 — per-kind stamps
     * give watchdogs a track-level liveness signal.
     */
    val lastVideoWriteAtMs: Long,
    /** Last successful AUDIO frame write (0 if none). */
    val lastAudioWriteAtMs: Long,
)

/**
 * Collects tracks and publishes them as one broadcast.
 *
 * Add all tracks before calling [start]. A publisher is usually registered with
 * [com.swmansion.moqkit.Session.publish] first, then started:
 *
 * ```kotlin
 * val publisher = Publisher()
 * publisher.addVideoTrack(name = "camera", source = camera)
 * publisher.addAudioTrack(name = "mic", source = microphone)
 * session.publish(path = "live/android", publisher = publisher)
 * publisher.start()
 * ```
 *
 * Call [stop] when the broadcast should end. A stopped publisher should not be reused;
 * create a new [Publisher] for the next broadcast.
 */
class Publisher {
    private val _state = MutableStateFlow<PublisherState>(PublisherState.Idle)

    /** Current publishing state. */
    val state: StateFlow<PublisherState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PublisherEvent>(extraBufferCapacity = 16)

    /**
     * Track-level events useful for updating UI or surfacing publish errors.
     */
    val events: SharedFlow<PublisherEvent> = _events

    internal val broadcast = MoqBroadcastProducer()
    internal val clock = Clock()

    private val framesWritten = AtomicLong(0)
    private val bytesWritten = AtomicLong(0)
    private val writeErrors = AtomicLong(0)

    @Volatile
    private var lastWriteAtMs = 0L

    @Volatile
    private var lastVideoWriteAtMs = 0L

    @Volatile
    private var lastAudioWriteAtMs = 0L

    /**
     * Current frame write activity as a consistent snapshot, for example to drive an
     * uplink-stall watchdog.
     */
    val activity: PublishActivity
        get() = PublishActivity(
            framesWritten = framesWritten.get(),
            bytesWritten = bytesWritten.get(),
            lastWriteAtMs = lastWriteAtMs,
            writeErrors = writeErrors.get(),
            lastVideoWriteAtMs = lastVideoWriteAtMs,
            lastAudioWriteAtMs = lastAudioWriteAtMs,
        )

    // Descriptors registered before start()
    private val videoDescriptors = mutableListOf<VideoTrackDescriptor>()
    private val audioDescriptors = mutableListOf<AudioTrackDescriptor>()
    private val dataDescriptors = mutableListOf<DataTrackDescriptor>()

    // Active runtime state
    private val activeVideoTracks = mutableMapOf<String, ActiveVideoTrack>()
    private val activeAudioTracks = mutableMapOf<String, ActiveAudioTrack>()
    private val activeDataTracks = mutableMapOf<String, ActiveDataTrack>()

    /**
     * Adds a video track backed by a [VideoFrameSource].
     *
     * Built-in sources include [com.swmansion.moqkit.publish.source.CameraCapture],
     * [com.swmansion.moqkit.publish.source.MultiCameraCapture], and
     * [com.swmansion.moqkit.publish.source.ScreenCapture]. The track starts only after
     * [start] is called.
     *
     * @param name Local SDK label used by [PublishedTrack] and [PublisherEvent]. Media
     *   catalog track names are generated by the underlying muxer; discover them from
     *   `Catalog.videoTracks`. Names must be unique among video tracks in this publisher.
     * @param source Video source that will feed encoder frames.
     * @param config Encoder settings and codec choice.
     * @return A handle for observing or stopping this track.
     * @throws IllegalArgumentException if another video track already uses [name].
     */
    fun addVideoTrack(
        name: String = "video",
        source: VideoFrameSource,
        config: VideoEncoderConfig = VideoEncoderConfig(),
    ): PublishedTrack {
        require(videoDescriptors.none { it.track.name == name }) { "Video track '$name' already added" }
        val track = PublishedTrack(
            name = name,
            codecInfo = TrackCodecInfo.Video(config.codec, config.width, config.height, config.frameRate),
        )
        videoDescriptors.add(VideoTrackDescriptor(track, source, config))
        return track
    }

    /**
     * Adds an audio track backed by an [AudioFrameSource].
     *
     * Built-in sources include [com.swmansion.moqkit.publish.source.MicrophoneCapture].
     * The track starts only after [start] is called. If the source reports a permanent
     * capture failure ([AudioFrameSource.onSourceError]), the track transitions to
     * [PublishedTrackState.Stopped] and [PublisherEvent.TrackError] is emitted; whether
     * to re-arm the source or end the broadcast is the app's decision.
     *
     * @param name Local SDK label used by [PublishedTrack] and [PublisherEvent]. Media
     *   catalog track names are generated by the underlying muxer; discover them from
     *   `Catalog.audioTracks`. Names must be unique among audio tracks in this publisher.
     * @param source Audio source that provides PCM samples.
     * @param config Encoder settings and codec choice.
     * @return A handle for observing or stopping this track.
     * @throws IllegalArgumentException if another audio track already uses [name].
     */
    fun addAudioTrack(
        name: String = "audio",
        source: AudioFrameSource,
        config: AudioEncoderConfig = AudioEncoderConfig(),
    ): PublishedTrack {
        require(audioDescriptors.none { it.track.name == name }) { "Audio track '$name' already added" }
        val track = PublishedTrack(
            name = name,
            codecInfo = TrackCodecInfo.Audio(config.codec, config.sampleRate),
        )
        audioDescriptors.add(AudioTrackDescriptor(track, source, config))
        return track
    }

    /**
     * Adds a raw data track.
     *
     * Data tracks send application-defined binary payloads, such as JSON chat messages.
     * They do not need to appear in the media catalog and are read with
     * [com.swmansion.moqkit.subscribe.Broadcast.subscribeTrack].
     *
     * @param name Track name used by subscribers.
     * @param emitter Object used to send payloads after [start].
     * @return A handle for observing or stopping this track.
     * @throws IllegalArgumentException if another data track already uses [name].
     */
    fun addDataTrack(
        name: String = "data",
        emitter: DataTrackEmitter,
    ): PublishedTrack {
        require(dataDescriptors.none { it.track.name == name }) { "Data track '$name' already added" }
        val track = PublishedTrack(name = name, codecInfo = TrackCodecInfo.Data)
        dataDescriptors.add(DataTrackDescriptor(track, emitter))
        return track
    }

    /**
     * Starts all configured tracks.
     *
     * This validates codec support before any track is started. If a selected codec is not
     * available on the current device, [UnsupportedCodecException] is thrown and publishing
     * does not begin.
     *
     * @throws IllegalStateException if this publisher has already been started.
     * @throws UnsupportedCodecException if any configured media track cannot be encoded on
     *   this device.
     */
    fun start() {
        check(_state.value == PublisherState.Idle) { "Publisher already started" }
        Log.d(TAG, "Starting publisher: ${videoDescriptors.size} video, ${audioDescriptors.size} audio, ${dataDescriptors.size} data tracks")

        validateCodecSupport()

        for (desc in videoDescriptors) startVideoTrack(desc)
        for (desc in audioDescriptors) startAudioTrack(desc)
        for (desc in dataDescriptors) startDataTrack(desc)

        _state.value = PublisherState.Publishing
        checkAllTracksStopped()
    }

    /**
     * Stops all active tracks and finishes the broadcast.
     *
     * Safe to call more than once. Stopping emits [PublisherEvent.TrackStopped] for each
     * configured track and moves [state] to [PublisherState.Stopped].
     */
    fun stop() {
        val current = _state.value
        if (current == PublisherState.Stopped || current is PublisherState.Error) return
        Log.d(TAG, "Stopping publisher")

        for ((_, active) in activeVideoTracks) tearDownVideoTrack(active)
        activeVideoTracks.clear()

        for ((_, active) in activeAudioTracks) tearDownAudioTrack(active)
        activeAudioTracks.clear()

        for ((_, active) in activeDataTracks) tearDownDataTrack(active)
        activeDataTracks.clear()

        try { broadcast.finish() } catch (_: Exception) {}
        clock.reset()

        for (desc in videoDescriptors) emitTrackStopped(desc.track)
        for (desc in audioDescriptors) emitTrackStopped(desc.track)
        for (desc in dataDescriptors) emitTrackStopped(desc.track)

        _state.value = PublisherState.Stopped
    }

    // MARK: - Video

    private fun validateCodecSupport() {
        for (desc in videoDescriptors) {
            val reason = desc.config.unsupportedReason
            if (reason != null) {
                throw UnsupportedCodecException("Video track '${desc.track.name}' is not supported: $reason")
            }
        }
        for (desc in audioDescriptors) {
            val reason = desc.config.unsupportedReason
            if (reason != null) {
                throw UnsupportedCodecException("Audio track '${desc.track.name}' is not supported: $reason")
            }
        }
    }

    private fun startVideoTrack(desc: VideoTrackDescriptor) {
        val active = ActiveVideoTrack()
        val encoder = VideoEncoder(desc.config)
        active.encoder = encoder
        active.source = desc.source
        val trackHandle = desc.track
        val clock = clock
        val broadcast = broadcast

        encoder.start { frame ->
            if (active.mediaProducer == null) {
                val initData = frame.initData ?: return@start
                try {
                    active.mediaProducer = broadcast.publishMedia(desc.config.format, initData)
                    Log.d(TAG, "Video track '${trackHandle.name}' active")
                    trackHandle.transition(PublishedTrackState.Active)
                    _events.tryEmit(PublisherEvent.TrackStarted(trackHandle.name))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create video producer: $e")
                    trackHandle.transition(PublishedTrackState.Stopped)
                    _events.tryEmit(PublisherEvent.TrackError(trackHandle.name, e.message ?: "unknown"))
                    return@start
                }
            }
            try {
                active.mediaProducer?.writeFrame(frame.data, clock.timestampUs(frame.timestampUs).toULong())
                recordFrameWritten(frame.data.size, video = true)
            } catch (e: Exception) {
                recordWriteError()
                Log.w(TAG, "writeFrame error: $e")
            }
        }

        val encoderSurface = encoder.encoderInputSurface
        if (encoderSurface != null) {
            desc.source.attachEncoderSurface(encoderSurface)
        }

        trackHandle.transition(PublishedTrackState.Starting)

        trackHandle.stopAction = {
            tearDownVideoTrack(active)
            activeVideoTracks.remove(trackHandle.name)
            trackHandle.transition(PublishedTrackState.Stopped)
            _events.tryEmit(PublisherEvent.TrackStopped(trackHandle.name))
            checkAllTracksStopped()
        }

        activeVideoTracks[desc.track.name] = active
    }

    private fun tearDownVideoTrack(active: ActiveVideoTrack) {
        active.source?.detachEncoderSurface()
        active.encoder?.stop()
        try { active.mediaProducer?.finish() } catch (_: Exception) {}
    }

    // MARK: - Audio

    private fun startAudioTrack(desc: AudioTrackDescriptor) {
        val active = ActiveAudioTrack()
        val encoder = AudioEncoder(desc.config)
        active.encoder = encoder
        active.source = desc.source
        val trackHandle = desc.track
        val clock = clock
        val broadcast = broadcast

        encoder.start(desc.source) { frame ->
            if (active.mediaProducer == null) {
                val initData = frame.initData ?: return@start
                try {
                    active.mediaProducer = broadcast.publishMedia(desc.config.format, initData)
                    Log.d(TAG, "Audio track '${trackHandle.name}' active")
                    trackHandle.transition(PublishedTrackState.Active)
                    _events.tryEmit(PublisherEvent.TrackStarted(trackHandle.name))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create audio producer: $e")
                    trackHandle.transition(PublishedTrackState.Stopped)
                    _events.tryEmit(PublisherEvent.TrackError(trackHandle.name, e.message ?: "unknown"))
                    return@start
                }
            }
            try {
                active.mediaProducer?.writeFrame(frame.data, clock.timestampUs(frame.timestampUs).toULong())
                recordFrameWritten(frame.data.size, video = false)
            } catch (e: Exception) {
                recordWriteError()
                Log.w(TAG, "writeFrame error: $e")
            }
        }

        trackHandle.transition(PublishedTrackState.Starting)

        trackHandle.stopAction = {
            tearDownAudioTrack(active)
            activeAudioTracks.remove(trackHandle.name)
            trackHandle.transition(PublishedTrackState.Stopped)
            _events.tryEmit(PublisherEvent.TrackStopped(trackHandle.name))
            checkAllTracksStopped()
        }

        activeAudioTracks[desc.track.name] = active

        // A source that dies on its own (for example MicrophoneCapture's
        // read-error give-up) is otherwise indistinguishable from silence: the
        // encoder keeps running and the broadcast continues video-only with no
        // signal to the app. Surface it exactly like the encoder-path failure
        // above. Wired LAST because a source that already died fires during
        // registration — the Stopped transition must land after the Starting
        // transition above so the track's final state is honest even for apps
        // that only watch track state.
        wireSourceError(desc.source, trackHandle, { _events.tryEmit(it) }) { Log.e(TAG, it) }
    }

    private fun tearDownAudioTrack(active: ActiveAudioTrack) {
        active.source?.onPcmData = null
        clearSourceError(active.source)
        active.encoder?.stop()
        try { active.mediaProducer?.finish() } catch (_: Exception) {}
    }

    // MARK: - Data

    private fun startDataTrack(desc: DataTrackDescriptor) {
        val producer = broadcast.publishTrack(desc.track.name)
        val active = ActiveDataTrack(desc.emitter, producer)
        desc.emitter.attach(producer)

        val trackHandle = desc.track
        trackHandle.stopAction = {
            tearDownDataTrack(active)
            activeDataTracks.remove(trackHandle.name)
            trackHandle.transition(PublishedTrackState.Stopped)
            _events.tryEmit(PublisherEvent.TrackStopped(trackHandle.name))
            checkAllTracksStopped()
        }

        activeDataTracks[desc.track.name] = active
        trackHandle.transition(PublishedTrackState.Active)
        _events.tryEmit(PublisherEvent.TrackStarted(trackHandle.name))
    }

    private fun tearDownDataTrack(active: ActiveDataTrack) {
        active.emitter?.detach()
        try { active.producer?.finish() } catch (_: Exception) {}
        try { active.producer?.close() } catch (_: Exception) {}
    }

    // MARK: - Lifecycle

    private fun recordFrameWritten(byteCount: Int, video: Boolean) {
        framesWritten.incrementAndGet()
        bytesWritten.addAndGet(byteCount.toLong())
        val now = System.currentTimeMillis()
        lastWriteAtMs = now
        if (video) lastVideoWriteAtMs = now else lastAudioWriteAtMs = now
    }

    private fun recordWriteError() {
        writeErrors.incrementAndGet()
    }

    private fun checkAllTracksStopped() {
        if (activeVideoTracks.isEmpty() && activeAudioTracks.isEmpty() && activeDataTracks.isEmpty()
            && _state.value == PublisherState.Publishing
        ) {
            _state.value = PublisherState.Stopped
        }
    }

    private fun emitTrackStopped(track: PublishedTrack) {
        track.transition(PublishedTrackState.Stopped)
        _events.tryEmit(PublisherEvent.TrackStopped(track.name))
    }

    // MARK: - Internal descriptor / runtime types

    private data class VideoTrackDescriptor(
        val track: PublishedTrack,
        val source: VideoFrameSource,
        val config: VideoEncoderConfig,
    )

    private data class AudioTrackDescriptor(
        val track: PublishedTrack,
        val source: AudioFrameSource,
        val config: AudioEncoderConfig,
    )

    private data class DataTrackDescriptor(
        val track: PublishedTrack,
        val emitter: DataTrackEmitter,
    )

    private class ActiveVideoTrack {
        var source: VideoFrameSource? = null
        var encoder: VideoEncoder? = null
        var mediaProducer: MoqMediaProducer? = null
    }

    private class ActiveAudioTrack {
        var source: AudioFrameSource? = null
        var encoder: AudioEncoder? = null
        var mediaProducer: MoqMediaProducer? = null
    }

    private data class ActiveDataTrack(
        val emitter: DataTrackEmitter?,
        val producer: MoqTrackProducer?,
    )

    internal companion object {
        /**
         * Wires a source's death signal to the track: on failure the track
         * transitions to [PublishedTrackState.Stopped] and a
         * [PublisherEvent.TrackError] is emitted — the same surface as the
         * encoder-path failures in the track-start blocks. The source fires at
         * most once per capture session and only for deaths that linearize
         * before a deliberate stop; registration itself fires when the source
         * is already dead. Extracted from [startAudioTrack] so the host-JVM
         * suite can pin this wiring without the native broadcast producer —
         * the one-line call sites are covered by review.
         */
        internal fun wireSourceError(
            source: AudioFrameSource,
            trackHandle: PublishedTrack,
            emit: (PublisherEvent) -> Unit,
            log: (String) -> Unit,
        ) {
            source.onSourceError = { message ->
                log("Audio track '${trackHandle.name}' source failed: $message")
                trackHandle.transition(PublishedTrackState.Stopped)
                emit(PublisherEvent.TrackError(trackHandle.name, message))
            }
        }

        /** Clears the death-signal registration; safe on a null source. */
        internal fun clearSourceError(source: AudioFrameSource?) {
            source?.onSourceError = null
        }
    }
}
