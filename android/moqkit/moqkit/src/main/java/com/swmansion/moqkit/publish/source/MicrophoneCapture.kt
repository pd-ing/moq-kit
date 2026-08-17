package com.swmansion.moqkit.publish.source

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import com.swmansion.moqkit.publish.encoder.AudioEncoderConfig
import com.swmansion.moqkit.publish.source.internal.AudioPtsCounter
import com.swmansion.moqkit.publish.source.internal.SourceErrorLatch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

private const val TAG = "MicrophoneCapture"

/** How often the capture-health diagnostic line is emitted. */
private const val DIAG_INTERVAL_US = 60_000_000L

/**
 * Consecutive non-positive [AudioRecord.read] returns before capture gives up.
 * A dead route (audioserver/HAL restart -> ERROR_DEAD_OBJECT) fails every read
 * immediately instead of blocking; at ~one backoff per chunk duration this is
 * roughly two seconds of persistent failure.
 */
private const val MAX_CONSECUTIVE_READ_ERRORS = 25

/**
 * Bound on how long [MicrophoneCapture.stop] waits for the reader thread to
 * exit before releasing the [AudioRecord] anyway. One blocking read is ~80ms,
 * so a healthy reader joins in well under this; the bound only keeps a wedged
 * reader from hanging the caller (2026-08-14 QA D-02: releasing while the
 * reader was still inside the native read() left the AudioFlinger record
 * track alive on the SM-S921N — the mic stayed "in use" until force-stop).
 */
private const val CLOSE_JOIN_TIMEOUT_MS = 500L

/**
 * Pulls PCM frames from the device microphone.
 *
 * Calling apps must both request and declare `RECORD_AUDIO` in their own manifest. The moqkit
 * library does not add that permission transitively.
 *
 * @param sampleRate Samples per second. Use the same value in [AudioEncoderConfig].
 * @param channels Channel count. `1` is mono, `2` is stereo.
 * @param enableNoiseSuppressor Attach the platform [NoiseSuppressor] to the
 *   capture session when available (2026-08-14 QA D-06: the raw
 *   [MediaRecorder.AudioSource.MIC] feed passes every ambient sound through
 *   unfiltered). Session effects transform PCM CONTENT only — frame counts
 *   and the sample-counter PTS are untouched, so the v4.14 metallic-noise fix
 *   cannot regress.
 * @param enableAutomaticGainControl Attach the platform [AutomaticGainControl]
 *   when available (voice level consistency).
 * @param enableAcousticEchoCanceler Attach the platform [AcousticEchoCanceler]
 *   when available. OFF by default: a one-way broadcast has no local playback
 *   reference stream, so AEC yields little and can add artifacts.
 */
class MicrophoneCapture(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1,
    private val enableNoiseSuppressor: Boolean = DEFAULT_ENABLE_NOISE_SUPPRESSOR,
    private val enableAutomaticGainControl: Boolean = DEFAULT_ENABLE_AUTOMATIC_GAIN_CONTROL,
    private val enableAcousticEchoCanceler: Boolean = DEFAULT_ENABLE_ACOUSTIC_ECHO_CANCELER,
) : AudioFrameSource {

    /**
     * Callback used by [com.swmansion.moqkit.publish.Publisher] to receive microphone PCM.
     *
     * Apps using [MicrophoneCapture] directly should not set this manually.
     */
    override var onPcmData: ((data: ByteArray, size: Int, timestampUs: Long) -> Unit)? = null

    /**
     * Capture-death signal (init failure or the read-error give-up below).
     * Internal rather than private so the host-JVM suite can drive [report]
     * through the real property delegation — the capture thread itself needs
     * a live AudioRecord and cannot run in unit tests.
     */
    internal val sourceError = SourceErrorLatch()

    /**
     * Fired at most once per capture session when capture dies on its own.
     * A failure that happens before registration (apps start the microphone
     * before the publisher attaches) is delivered upon registration. [stop]
     * suppresses the signal from the moment it runs; a give-up that races
     * ahead of it is a genuine pre-stop death and may still fire.
     */
    override var onSourceError: ((message: String) -> Unit)?
        get() = sourceError.callback
        set(value) {
            sourceError.callback = value
        }

    /**
     * Debug-only fault injection: while true, every read from the AudioRecord
     * is treated as a failed read (as if the audio route died), driving the
     * consecutive read-error give-up and the [onSourceError] signal after
     * roughly two seconds. Lets apps and device tests exercise the
     * capture-death path on demand — the real triggers (audioserver death,
     * route revocation) are not reproducible on unrooted devices. Never set
     * in production.
     */
    @Volatile
    var debugForceReadFailures: Boolean = false

    private var record: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var running = false

    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null

    /**
     * Times [stop] waited [CLOSE_JOIN_TIMEOUT_MS] without the reader exiting.
     * Diagnostic only — release still runs (leaking is worse than racing a
     * wedged reader); a non-zero count in the ownership log is the smoking gun
     * for "mic still in use after Stop" reports.
     */
    internal val closeTimeouts = AtomicLong(0)

    /**
     * Starts microphone capture.
     *
     * Requires `RECORD_AUDIO` permission. If Android cannot initialize the microphone, the
     * call returns without producing audio.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        // Re-entry guard (2026-08-14 QA D-02 hardening): a second start() on a
        // live instance used to overwrite `record`/`recordThread`, stranding
        // the previous AudioRecord beyond every teardown path — the OS then
        // reports the mic "in use" until force-stop. Tear the old session down
        // first; stop() is bounded so this cannot wedge the caller.
        if (record != null || recordThread?.isAlive == true) {
            Log.w(TAG, "start() while a capture session is live — stopping the previous session first")
            // Review R1+R2 (v4.16): the internal teardown must not sever the
            // consumer wiring — stop() nulls onPcmData AND closes the death
            // latch (nulling onSourceError); a restart of a still-wired
            // instance would then capture into a null sink AND lose the very
            // death signal a dead mic depends on. Snapshot and restore BOTH
            // across the internal stop (the setter is inert while the latch
            // is closed; rearm() below re-opens it for the new session).
            val wiredPcm = onPcmData
            val wiredErr = onSourceError
            stop()
            onPcmData = wiredPcm
            onSourceError = wiredErr
        }
        // Each start() is a fresh capture session: the token ties this
        // session's death reports to this session, so a stale read thread from
        // a previous session (stop() joins bounded; a wedged reader may
        // outlive it) can never fire the restarted session's callback.
        val session = sourceError.rearm()
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val bytesPerFrame = 2 * channels
        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        // The read chunk keeps the historical ~80ms cadence: PTS granularity and
        // encoder feed pacing are set by this, not by the ring below.
        val readChunkBytes = maxOf(minBufSize * 2, 4096)
        // The AudioRecord internal ring is sized independently of the read chunk:
        // ring depth only buys tolerance to reader-thread stalls (a stall longer
        // than the ring silently overwrites PCM). ~400ms of headroom; the read
        // cadence stays ~80ms because the reader drains eagerly.
        val ringBytes = maxOf(readChunkBytes, sampleRate * bytesPerFrame * 2 / 5)

        val newRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            ringBytes,
        )

        if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
            newRecord.release()
            Log.e(TAG, "AudioRecord initialization failed")
            sourceError.report(session, "AudioRecord initialization failed")
            return
        }

        // 2026-08-14 QA D-06: attach platform capture effects to the session
        // BEFORE recording starts. These transform PCM content only; the
        // delivered frame counts and the AudioPtsCounter sample-clock PTS are
        // untouched, so the v4.14 metallic-noise regression cannot return.
        attachEffects(newRecord.audioSessionId)

        record = newRecord
        running = true
        newRecord.startRecording()

        recordThread = Thread {
            // The reader competes with camera/encoder/GC work; default priority
            // risks ring overruns under load. Must be set from inside the thread
            // body — java.lang.Thread priorities do not map to Android scheduling.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val buf = ByteArray(readChunkBytes)
            val chunkDurUs = (readChunkBytes / bytesPerFrame) * 1_000_000L / sampleRate
            // PTS follows the microphone's sample clock, not the wall clock at
            // read() return — see AudioPtsCounter for why. wallDeltaUs in the
            // diagnostic line tracks the divergence between the two clocks so a
            // future embedded-PTS consumer can decide whether it needs tethering.
            val pts = AudioPtsCounter(sampleRate, bytesPerFrame)

            // Capture-health diagnostics (one line per DIAG_INTERVAL_US):
            // burstReads counts reads returning in under half a chunk duration —
            // the signature of the ring draining a backlog after a reader stall.
            var chunkSeq = 0L
            var burstReads = 0L
            var maxAbsWallDeltaUs = 0L
            var lastReadUs = 0L
            var lastDiagUs = 0L

            var consecutiveReadErrors = 0
            while (running) {
                // The injected failure must replace the blocking read entirely:
                // a real dead route fails immediately instead of blocking, and
                // the ~2s give-up cadence comes from the backoff sleep below.
                val read = if (debugForceReadFailures) {
                    AudioRecord.ERROR_DEAD_OBJECT
                } else {
                    newRecord.read(buf, 0, buf.size)
                }
                if (read <= 0) {
                    // A dead AudioRecord returns the error immediately instead of
                    // blocking, and this thread runs at URGENT_AUDIO — without a
                    // backoff the loop would peg a core at near-realtime priority
                    // for the rest of the session. Capture cannot self-heal a dead
                    // route, so after ~2s of persistent failure stop delivering: a
                    // dead audio track is diagnosable, a realtime spin is not.
                    consecutiveReadErrors++
                    if (consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                        Log.e(
                            TAG,
                            "audio capture read failed $consecutiveReadErrors times in a row " +
                                "(last=$read); stopping capture",
                        )
                        sourceError.report(
                            session,
                            "audio capture read failed $consecutiveReadErrors times in a row " +
                                "(last=$read)",
                        )
                        // 2026-08-14 QA D-02/D-05: stop recording at give-up so
                        // the dead route releases the AudioFlinger record track
                        // NOW — before this fix the record kept "recording"
                        // (mic shown in-use) for as long as the exhausted
                        // generation stayed live. The object itself is released
                        // by stop() (double AudioRecord.stop is a caught no-op).
                        try {
                            newRecord.stop()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping AudioRecord at give-up: $e")
                        }
                        break
                    }
                    try {
                        Thread.sleep(chunkDurUs / 1_000L)
                    } catch (_: InterruptedException) {
                        // stop() interrupts the thread; the loop re-checks running.
                    }
                    continue
                }
                consecutiveReadErrors = 0
                val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
                val chunk = deliverChunk(buf, read, nowUs, pts, onPcmData) ?: continue
                chunkSeq++

                if (lastReadUs != 0L && nowUs - lastReadUs < chunkDurUs / 2) burstReads++
                lastReadUs = nowUs
                val wallDeltaUs = pts.wallDeltaUs(nowUs)
                if (abs(wallDeltaUs) > maxAbsWallDeltaUs) maxAbsWallDeltaUs = abs(wallDeltaUs)
                if (lastDiagUs == 0L) lastDiagUs = nowUs
                if (nowUs - lastDiagUs >= DIAG_INTERVAL_US) {
                    Log.i(
                        TAG,
                        String.format(
                            Locale.US,
                            "audio_pts seq=%d frames=%d wallDeltaUs=%d maxAbsWallDeltaUs=%d burstReads=%d ringBytes=%d chunkBytes=%d",
                            chunkSeq, pts.framesDelivered, wallDeltaUs, maxAbsWallDeltaUs,
                            burstReads, ringBytes, readChunkBytes,
                        )
                    )
                    maxAbsWallDeltaUs = 0L
                    burstReads = 0L
                    lastDiagUs = nowUs
                }

            }
        }.apply {
            name = "MicCapture"
            isDaemon = true
            start()
        }
    }

    /**
     * The pure effect-attachment decision (2026-08-14 QA D-06): which platform
     * effects to attach given the configuration and device availability.
     * Extracted so the host-JVM suite can pin the policy — the effects
     * themselves need a live capture session and cannot run in unit tests.
     */
    internal data class EffectPlan(
        val noiseSuppressor: Boolean,
        val automaticGainControl: Boolean,
        val acousticEchoCanceler: Boolean,
    )

    internal companion object {
        /**
         * The D-06 default contract (2026-08-14 QA), single source of truth:
         * NS/AGC on, AEC off. Review R4: these lived only as constructor
         * default literals, which no host-JVM test can observe (the sole
         * reader, attachEffects, needs a live AudioRecord) — a default flip
         * shipped green through the whole suite. The test suite pins THESE
         * constants, and the constructor defaults reference them.
         */
        internal const val DEFAULT_ENABLE_NOISE_SUPPRESSOR = true
        internal const val DEFAULT_ENABLE_AUTOMATIC_GAIN_CONTROL = true
        internal const val DEFAULT_ENABLE_ACOUSTIC_ECHO_CANCELER = false

        /** See [EffectPlan]: enable requested AND device-available, per effect. */
        internal fun planEffects(
            enableNoiseSuppressor: Boolean,
            enableAutomaticGainControl: Boolean,
            enableAcousticEchoCanceler: Boolean,
            noiseSuppressorAvailable: Boolean,
            automaticGainControlAvailable: Boolean,
            acousticEchoCancelerAvailable: Boolean,
        ): EffectPlan = EffectPlan(
            noiseSuppressor = enableNoiseSuppressor && noiseSuppressorAvailable,
            automaticGainControl = enableAutomaticGainControl && automaticGainControlAvailable,
            acousticEchoCanceler = enableAcousticEchoCanceler && acousticEchoCancelerAvailable,
        )

        /**
         * Converts one blocking read into a delivered chunk: byte->frame
         * conversion, PTS advance and delivered size all come from
         * [AudioPtsCounter], and the consumer receives the SAMPLE-CLOCK
         * chunk timestamp — never the wall-clock read-return time (stamping
         * the wall clock is defect (a) of the 2026-08-11 metallic-noise
         * round). A sub-frame read delivers nothing and advances nothing.
         * Only whole 16-bit frames go downstream; a torn trailing byte
         * (never observed in blocking mode, but not API-guaranteed) must
         * not shift sample alignment for the encoder.
         *
         * Free of Android runtime dependencies so the host-JVM suite can
         * pin the delivery selection — the capture thread itself needs a
         * real AudioRecord and cannot run in unit tests.
         */
        internal fun deliverChunk(
            buf: ByteArray,
            read: Int,
            nowUs: Long,
            pts: AudioPtsCounter,
            onPcmData: ((data: ByteArray, size: Int, timestampUs: Long) -> Unit)?,
        ): AudioPtsCounter.Chunk? {
            val chunk = pts.onChunkBytes(read, nowUs) ?: return null
            onPcmData?.invoke(buf, chunk.sizeBytes, chunk.timestampUs)
            return chunk
        }
    }

    /**
     * Stops microphone capture and releases the underlying [AudioRecord].
     *
     * 2026-08-14 QA D-02: the release is ordered stop -> bounded join ->
     * release. Releasing while the reader thread was still inside the native
     * blocking read() raced the AudioFlinger record-track teardown — on the
     * SM-S921N the input session survived the release and the OS kept the mic
     * "in use" until the app was force-stopped (AudioService dump: no
     * `rec stop` for the session). AudioRecord.stop() makes an in-flight
     * read() return, the bounded join proves the reader is off the record,
     * and only then is the object released. Each stage logs ownership so a
     * held-mic report is diagnosable from logcat alone.
     */
    fun stop() {
        running = false
        onPcmData = null
        // Deliberate stop: from here on the death signal is suppressed. A
        // give-up that linearizes ahead of this close is a genuine pre-stop
        // death and may still fire — suppression is close-then-quiet, not
        // retroactive.
        sourceError.close()
        val rec = record
        val reader = recordThread
        record = null
        recordThread = null
        // Review R2 (v4.16): stop() may be invoked FROM the reader itself (a
        // direct-API onSourceError handler calling stop() on the give-up
        // delivery). Interrupting/joining the current thread would throw an
        // immediate InterruptedException out of join() and falsely count a
        // closeTimeout — the exact diagnostic used to chase held-mic reports
        // — while the reader is simply unwinding. Skip the join accounting
        // for a self-stop; the release below is still safe (the reader is
        // past its read loop when it delivers the give-up).
        val selfStop = reader === Thread.currentThread()
        // Unblock the native read FIRST (stop() forces read() to return),
        // then wake the error-backoff sleep via the interrupt.
        try {
            rec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: $e")
        }
        if (!selfStop) reader?.interrupt()
        var joinedOff = true
        var joinMs = 0L
        if (!selfStop && reader != null && reader.isAlive) {
            val joinStart = SystemClock.elapsedRealtime()
            try {
                reader.join(CLOSE_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            joinMs = SystemClock.elapsedRealtime() - joinStart
            joinedOff = !reader.isAlive
            if (!joinedOff) closeTimeouts.incrementAndGet()
        }
        releaseEffects()
        // Release even on a join timeout — a wedged reader is the abnormal
        // case, and leaking the record (a mic held until force-stop) is worse
        // than racing it.
        try {
            rec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: $e")
        }
        if (rec != null || reader != null) {
            Log.i(
                TAG,
                "mic stop: reader " +
                    (if (reader == null) "already exited"
                    else if (selfStop) "is the caller (self-stop; unwinding)"
                    else if (joinedOff) "joined in ${joinMs}ms"
                    else "JOIN TIMEOUT after ${joinMs}ms (closeTimeouts=${closeTimeouts.get()})") +
                    "; AudioRecord ${if (rec != null) "released" else "already gone"}",
            )
        }
    }

    /**
     * Attaches the configured platform capture effects to the record session.
     * Availability is device-dependent; every decision is logged so a noisy
     * capture is diagnosable ("attached" vs "unavailable") from logcat.
     */
    /**
     * The instance's effect plan for the given device availability — the
     * constructor flags' single exit point, used by [attachEffects] and
     * pinned directly by the host-JVM suite. Review R6: the DEFAULT_ENABLE_*
     * VALUES were pinned but the constructor->constant binding was not, so
     * re-inlining a constructor default to a literal shipped green while
     * every default-constructed capture (the demo's only construction path)
     * silently lost its effects.
     */
    internal fun configuredEffectPlan(
        noiseSuppressorAvailable: Boolean,
        automaticGainControlAvailable: Boolean,
        acousticEchoCancelerAvailable: Boolean,
    ): EffectPlan = planEffects(
        enableNoiseSuppressor = enableNoiseSuppressor,
        enableAutomaticGainControl = enableAutomaticGainControl,
        enableAcousticEchoCanceler = enableAcousticEchoCanceler,
        noiseSuppressorAvailable = noiseSuppressorAvailable,
        automaticGainControlAvailable = automaticGainControlAvailable,
        acousticEchoCancelerAvailable = acousticEchoCancelerAvailable,
    )

    private fun attachEffects(sessionId: Int) {
        val plan = configuredEffectPlan(
            noiseSuppressorAvailable = NoiseSuppressor.isAvailable(),
            automaticGainControlAvailable = AutomaticGainControl.isAvailable(),
            acousticEchoCancelerAvailable = AcousticEchoCanceler.isAvailable(),
        )
        if (enableNoiseSuppressor) {
            if (plan.noiseSuppressor) {
                noiseSuppressor = try {
                    NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.w(TAG, "NoiseSuppressor create failed: $e")
                    null
                }
                Log.i(TAG, "NoiseSuppressor ${if (noiseSuppressor?.enabled == true) "attached" else "create failed; capturing unfiltered"}")
            } else {
                Log.w(TAG, "NoiseSuppressor unavailable on this device; capturing unfiltered")
            }
        }
        if (enableAutomaticGainControl) {
            if (plan.automaticGainControl) {
                automaticGainControl = try {
                    AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.w(TAG, "AutomaticGainControl create failed: $e")
                    null
                }
                Log.i(TAG, "AutomaticGainControl ${if (automaticGainControl?.enabled == true) "attached" else "create failed"}")
            } else {
                Log.w(TAG, "AutomaticGainControl unavailable on this device")
            }
        }
        if (enableAcousticEchoCanceler) {
            if (plan.acousticEchoCanceler) {
                acousticEchoCanceler = try {
                    AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.w(TAG, "AcousticEchoCanceler create failed: $e")
                    null
                }
                Log.i(TAG, "AcousticEchoCanceler ${if (acousticEchoCanceler?.enabled == true) "attached" else "create failed"}")
            } else {
                Log.w(TAG, "AcousticEchoCanceler unavailable on this device")
            }
        }
    }

    private fun releaseEffects() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        try { automaticGainControl?.release() } catch (_: Exception) {}
        automaticGainControl = null
        try { acousticEchoCanceler?.release() } catch (_: Exception) {}
        acousticEchoCanceler = null
    }
}
