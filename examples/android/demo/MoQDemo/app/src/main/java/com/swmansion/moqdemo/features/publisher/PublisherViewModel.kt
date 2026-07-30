package com.swmansion.moqdemo.features.publisher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.swmansion.moqkit.Session
import com.swmansion.moqkit.publish.PublishActivity
import com.swmansion.moqkit.publish.Publisher
import com.swmansion.moqkit.publish.PublishedTrack
import com.swmansion.moqkit.publish.PublishedTrackState
import com.swmansion.moqkit.publish.PublisherEvent
import com.swmansion.moqkit.publish.PublisherState
import com.swmansion.moqkit.publish.encoder.AudioCodec
import com.swmansion.moqkit.publish.encoder.AudioEncoderConfig
import com.swmansion.moqkit.publish.encoder.VideoCodec
import com.swmansion.moqkit.publish.encoder.VideoEncoderConfig
import com.swmansion.moqkit.publish.source.CameraCapture
import com.swmansion.moqkit.publish.source.CameraPosition
import com.swmansion.moqkit.publish.source.CameraStreamConfig
import com.swmansion.moqkit.publish.source.MicrophoneCapture
import com.swmansion.moqkit.publish.source.MultiCameraCapture
import com.swmansion.moqkit.publish.source.ScreenCapture
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    HD("720p", 1280, 720),
    FHD("1080p", 1920, 1080),
}

enum class VideoFrameRate(val label: String, val fps: Int) {
    FPS24("24", 24),
    FPS30("30", 30),
    FPS60("60", 60),
}

enum class CameraSourceMode(val label: String) {
    SingleCamera("Single"),
    MultiCamera("MultiCam"),
}

class PublisherViewModel(application: Application) : AndroidViewModel(application) {

    // Connection settings
    var broadcastPath by mutableStateOf("live/test")

    // Source toggles
    var cameraEnabled by mutableStateOf(true)
    var micEnabled by mutableStateOf(true)
    var screenEnabled by mutableStateOf(false)
    var cameraSourceMode by mutableStateOf(CameraSourceMode.SingleCamera)
    var cameraPosition by mutableStateOf(CameraPosition.Front)
    var multiCameraMainPreviewPosition by mutableStateOf(CameraPosition.Back)

    // Codec settings
    var videoCodec by mutableStateOf(VideoCodec.H264)
    var audioCodec by mutableStateOf(AudioCodec.AAC)
    var videoResolution by mutableStateOf(VideoResolution.HD)
    var videoFrameRate by mutableStateOf(VideoFrameRate.FPS30)
    var audioSampleRate by mutableStateOf(48_000)

    // Observable state
    var sessionState by mutableStateOf<Session.State>(Session.State.Idle)
    var publisherState by mutableStateOf<PublisherState>(PublisherState.Idle)
    val trackStates = mutableStateMapOf<String, PublishedTrackState>()
    var publishedTracks by mutableStateOf<List<PublishedTrack>>(emptyList())
    var lastError by mutableStateOf<String?>(null)

    /**
     * Aspect ratio (w/h) of the ACTIVE encode, frozen at publish start. The
     * preview container follows this while publishing (rotating the phone
     * mid-broadcast must not make the preview show a different framing than
     * viewers get); null when idle, so the preview tracks the live orientation.
     */
    var publishedVideoAspect by mutableStateOf<Float?>(null)
        private set

    // Uplink watchdog / reconnect state
    var isReconnecting by mutableStateOf(false)
        private set
    var reconnectAttempt by mutableStateOf(0)
        private set
    var isPublishStalled by mutableStateOf(false)
        private set
    var isNetworkDown by mutableStateOf(false)
        private set
    var publishStatsText by mutableStateOf<String?>(null)
        private set

    val supportedVideoCodecs: List<VideoCodec>
        get() = VideoEncoderConfig.supportedCodecs()

    val supportedAudioCodecs: List<AudioCodec>
        get() = AudioEncoderConfig.supportedCodecs()

    var isMultiCameraSupported by mutableStateOf(MultiCameraCapture.isSupported(getApplication()))
        private set

    val isPublishing get() = publisherState == PublisherState.Publishing
    val canPublish get() = !isReconnecting
            && (sessionState == Session.State.Idle
            || sessionState == Session.State.Closed
            || sessionState is Session.State.Error)
            && (publisherState == PublisherState.Idle
            || publisherState == PublisherState.Stopped
            || publisherState is PublisherState.Error)
            && (cameraEnabled || micEnabled || screenEnabled)
            && publishUnsupportedReason() == null
    val canStop get() = isPublishing || isReconnecting || sessionState == Session.State.Connecting
            || sessionState == Session.State.Connected

    val stateLabel get() = when {
        isReconnecting -> "reconnecting ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)…"
        else -> when (val s = sessionState) {
            Session.State.Idle -> "idle"
            Session.State.Connecting -> "connecting…"
            Session.State.Connected -> "connected"
            is Session.State.Error -> "error: ${s.message}"
            Session.State.Closed -> "closed"
        }
    }

    val publisherStateLabel get() = when {
        isReconnecting -> "reconnecting…"
        publisherState == PublisherState.Publishing && isPublishStalled && isNetworkDown ->
            "publishing (network down)"
        publisherState == PublisherState.Publishing && isPublishStalled -> "publishing (stalled)"
        else -> when (val s = publisherState) {
            PublisherState.Idle -> "idle"
            PublisherState.Publishing -> "publishing"
            PublisherState.Stopped -> "stopped"
            is PublisherState.Error -> "error: ${s.message}"
        }
    }

    // Internal state
    private var session: Session? = null
    private var publisher: Publisher? = null
    private var camera: CameraCapture? = null
    private var multiCamera: MultiCameraCapture? = null
    private var microphone: MicrophoneCapture? = null
    private var screenCapture: ScreenCapture? = null
    private var screenProjectionData: Pair<Int, Intent>? = null
    private var previewSurface: Surface? = null
    private var frontPreviewSurface: Surface? = null
    private var backPreviewSurface: Surface? = null
    private var sessionJob: Job? = null
    private var publisherJobs = mutableListOf<Job>()

    // Reconnect bookkeeping
    private var lastRelayUrl: String? = null
    private var lastLifecycleOwner: LifecycleOwner? = null
    private var reconnectAttempts = 0
    private var reconnectStartedAtMs = 0L
    private var reconnectJob: Job? = null
    private var watchdog: PublishWatchdog? = null
    private var networkAvailability: NetworkAvailability? = null

    // Data-plane rebuild on network recovery (a wedged session looks healthy but
    // has no egress; see NetworkRecoveryPolicy)
    private var networkRecoveryPolicy = NetworkRecoveryPolicy()
    private var networkRecoveryJob: Job? = null

    /** Monotonic id identifying each session+publisher generation in logs. */
    private var publishGeneration = 0

    // Injectable seams for tests
    internal var watchdogFactory: () -> PublishWatchdog = { PublishWatchdog() }
    internal var reconnectDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val DEFAULT_RECONNECT_DELAY_MS = 3_000L

        /**
         * Total wall-clock budget for one automatic reconnect run. Each attempt is
         * bounded by the session-level fingerprint/connect timeouts, and the whole
         * run gives up once this budget is exhausted instead of dragging on for
         * several minutes.
         */
        const val RECONNECT_BUDGET_MS = 90_000L

        private const val TAG = "PublisherViewModel"
    }

    // Rotation-change signal for the moqkit renderer. Keying on the Compose
    // Configuration.orientation alone misses reverse flips (ROTATION_90 <->
    // ROTATION_270 and 0 <-> 180 keep the same orientation Int and may not
    // dispatch a configuration change at all), which would re-introduce
    // upside-down video; DisplayManager reports every display rotation change.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onDisplayRotationChanged()
        }
    }

    init {
        if (videoCodec !in supportedVideoCodecs) {
            videoCodec = supportedVideoCodecs.firstOrNull() ?: videoCodec
        }
        if (audioCodec !in supportedAudioCodecs) {
            audioCodec = supportedAudioCodecs.firstOrNull() ?: audioCodec
        }
        refreshMultiCameraSupport()
        (getApplication<Application>().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    fun refreshMultiCameraSupport() {
        if (!MultiCameraCapture.isSupported(getApplication())) {
            isMultiCameraSupported = false
            if (cameraSourceMode == CameraSourceMode.MultiCamera) {
                cameraSourceMode = CameraSourceMode.SingleCamera
            }
            return
        }

        viewModelScope.launch {
            val supported = try {
                MultiCameraCapture.isFrontBackSupported(getApplication())
            } catch (_: Exception) {
                false
            }
            isMultiCameraSupported = supported
            if (!supported && cameraSourceMode == CameraSourceMode.MultiCamera) {
                lastError = "Multi-camera capture is not supported by this emulator/device"
                cameraSourceMode = CameraSourceMode.SingleCamera
            }
        }
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        camera?.setPreviewSurface(surface)
    }

    fun setMultiCameraPreviewSurface(position: CameraPosition, surface: Surface?) {
        when (position) {
            CameraPosition.Front -> {
                frontPreviewSurface = surface
                multiCamera?.frontSource?.setPreviewSurface(surface)
            }
            CameraPosition.Back -> {
                backPreviewSurface = surface
                multiCamera?.backSource?.setPreviewSurface(surface)
            }
        }
    }

    fun startCamera(lifecycleOwner: LifecycleOwner) {
        if (!cameraEnabled) return

        when (cameraSourceMode) {
            CameraSourceMode.SingleCamera -> startSingleCamera(lifecycleOwner)
            CameraSourceMode.MultiCamera -> startMultiCamera(lifecycleOwner)
        }
    }

    private fun startSingleCamera(lifecycleOwner: LifecycleOwner) {
        stopMultiCamera()
        if (camera != null) return

        val cam = CameraCapture(position = cameraPosition)
        camera = cam
        viewModelScope.launch {
            try {
                cam.start(getApplication(), lifecycleOwner)
                cam.setDisplayRotation(currentDisplayRotationDegrees())
                cam.setPreviewSurface(previewSurface)
            } catch (e: Exception) {
                lastError = "Camera start failed: ${e.message}"
                camera = null
            }
        }
    }

    private fun startMultiCamera(lifecycleOwner: LifecycleOwner) {
        if (!isMultiCameraSupported) {
            lastError = "Multi-camera capture is not supported on this device"
            cameraSourceMode = CameraSourceMode.SingleCamera
            startSingleCamera(lifecycleOwner)
            return
        }

        stopSingleCamera()

        val videoConfig = currentVideoConfig()
        val existing = multiCamera
        if (existing != null && isMultiCamera(existing, videoConfig)) {
            return
        }

        stopMultiCamera()

        val capture = makeMultiCameraCapture(videoConfig)
        capture.setDisplayRotation(currentDisplayRotationDegrees())
        applyMultiCameraPreviewSurfaces(capture)
        multiCamera = capture

        viewModelScope.launch {
            try {
                if (!MultiCameraCapture.isFrontBackSupported(getApplication())) {
                    error("No concurrent front/back camera pair is available on this emulator/device")
                }
                capture.start(getApplication(), lifecycleOwner)
            } catch (e: Exception) {
                if (multiCamera === capture) {
                    lastError = "Multi-camera start failed: ${e.message}"
                    multiCamera = null
                    isMultiCameraSupported = false
                    cameraSourceMode = CameraSourceMode.SingleCamera
                    startSingleCamera(lifecycleOwner)
                }
                capture.stop()
            }
        }
    }

    fun stopCamera() {
        stopSingleCamera()
        stopMultiCamera()
    }

    fun flipCamera() {
        if (cameraSourceMode != CameraSourceMode.SingleCamera) return

        cameraPosition = if (cameraPosition == CameraPosition.Front) CameraPosition.Back else CameraPosition.Front
        viewModelScope.launch {
            try {
                camera?.switchCamera()
            } catch (e: Exception) {
                lastError = "Camera flip failed: ${e.message}"
            }
        }
    }

    fun swapMultiCameraPreview() {
        multiCameraMainPreviewPosition =
            if (multiCameraMainPreviewPosition == CameraPosition.Front) {
                CameraPosition.Back
            } else {
                CameraPosition.Front
            }
    }

    fun setScreenProjection(resultCode: Int, intent: Intent) {
        screenProjectionData = Pair(resultCode, intent)
    }

    fun clearScreenProjection() {
        screenProjectionData = null
    }

    fun selectAudioCodec(codec: AudioCodec) {
        audioCodec = codec
        if (codec == AudioCodec.OPUS && audioSampleRate != 48_000) {
            audioSampleRate = 48_000
        }
    }

    fun publish(lifecycleOwner: LifecycleOwner, relayUrl: String) {
        cancelReconnect()
        lastRelayUrl = relayUrl
        lastLifecycleOwner = lifecycleOwner
        startPublishing(lifecycleOwner, relayUrl)
    }

    private fun startPublishing(lifecycleOwner: LifecycleOwner, relayUrl: String) {
        lastError = null
        trackStates.clear()
        publishedTracks = emptyList()

        val url = relayUrl.trim()
        if (url.isEmpty()) {
            lastError = "Relay URL is required"
            return
        }

        val videoConfig = currentVideoConfig()
        val audioConfig = currentAudioConfig()
        publishUnsupportedReason(videoConfig, audioConfig)?.let {
            lastError = it
            return
        }
        Log.i(
            TAG,
            "video config ${videoConfig.width}x${videoConfig.height}@${videoConfig.frameRate} " +
                "(displayRotation=${currentDisplayRotationDegrees()} portrait=$isDisplayPortrait)",
        )
        publishedVideoAspect = videoConfig.width.toFloat() / videoConfig.height

        // A previous session may have ended on its own (idle timeout, transport error)
        // without an explicit stop; release it before starting a new one.
        if (session != null || publisher != null) {
            stopPublishing(keepCameraPreview = true)
        }

        val generation = ++publishGeneration
        val s = Session(url = url, parentScope = viewModelScope)
        session = s

        sessionJob = s.state.onEach { state ->
            sessionState = state
            if (state is Session.State.Error || state is Session.State.Closed) {
                onSessionEnded(s, state)
            }
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            try {
                s.connect()

                val pub = Publisher()
                publisher = pub

                val tracks = mutableListOf<PublishedTrack>()

                if (cameraEnabled) {
                    when (cameraSourceMode) {
                        CameraSourceMode.SingleCamera -> {
                            val cam = camera ?: CameraCapture(position = cameraPosition).also {
                                it.start(getApplication(), lifecycleOwner)
                                it.setPreviewSurface(previewSurface)
                                camera = it
                            }
                            cam.setDisplayRotation(currentDisplayRotationDegrees())
                            tracks += pub.addVideoTrack(name = "camera", source = cam, config = videoConfig)
                            trackStates["camera"] = PublishedTrackState.Idle
                        }

                        CameraSourceMode.MultiCamera -> {
                            val capture = runningMultiCameraCapture(videoConfig, lifecycleOwner)
                            tracks += pub.addVideoTrack(
                                name = "front-camera",
                                source = capture.frontSource,
                                config = videoConfig,
                            )
                            trackStates["front-camera"] = PublishedTrackState.Idle

                            tracks += pub.addVideoTrack(
                                name = "back-camera",
                                source = capture.backSource,
                                config = videoConfig,
                            )
                            trackStates["back-camera"] = PublishedTrackState.Idle
                        }
                    }
                }

                if (micEnabled) {
                    val mic = MicrophoneCapture(sampleRate = audioSampleRate)
                    microphone = mic
                    mic.start()
                    tracks += pub.addAudioTrack(name = "mic", source = mic, config = audioConfig)
                    trackStates["mic"] = PublishedTrackState.Idle
                }

                if (screenEnabled) {
                    val (resultCode, intent) = screenProjectionData
                        ?: error("Screen capture permission not granted. Toggle screen capture off and on again.")
                    ContextCompat.startForegroundService(
                        getApplication<Application>(),
                        Intent(getApplication(), ScreenCaptureService::class.java)
                    )
                    withTimeout(5_000) {
                        ScreenCaptureService.awaitStarted()
                    }
                    // Orientation-aware dims: a portrait screen must be captured
                    // and encoded portrait (the virtual display is already
                    // display-oriented, so no extra rotation is applied to it).
                    val screen = ScreenCapture(
                        intent = intent,
                        resultCode = resultCode,
                        width = videoConfig.width,
                        height = videoConfig.height,
                        frameRate = videoFrameRate.fps,
                    )
                    screenCapture = screen
                    screen.start(getApplication())
                    tracks += pub.addVideoTrack(name = "screen", source = screen, config = videoConfig)
                    trackStates["screen"] = PublishedTrackState.Idle
                }

                publishedTracks = tracks
                s.publish(broadcastPath, pub)
                pub.start()

                observePublisher(pub, tracks)
                isReconnecting = false
                reconnectAttempt = 0
                lastError = null
                Log.i(TAG, "publish gen $generation up: tracks=${tracks.joinToString(",") { it.name }}")
                startWatchdog(pub)
            } catch (e: Exception) {
                Log.w(TAG, "publish gen $generation failed: ${e.message}")
                if (isReconnecting) {
                    // A reconnect attempt failed; keep the camera preview alive and
                    // schedule the next attempt instead of giving up.
                    val cause = e.message ?: "Unknown error"
                    resetAfterPublishFailure(keepCameraPreview = true)
                    scheduleReconnectRetry(cause)
                } else {
                    lastError = e.message ?: "Unknown error"
                    resetAfterPublishFailure()
                }
            }
        }
    }

    fun stop() {
        cancelReconnect()
        stopPublishing(keepCameraPreview = true)
    }

    private fun stopPublishing(keepCameraPreview: Boolean) {
        watchdog?.stop()
        watchdog = null
        networkAvailability?.stop()
        networkAvailability = null
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        publisherJobs.forEach { it.cancel() }
        publisherJobs.clear()
        sessionJob?.cancel()
        sessionJob = null

        val pub = publisher
        val sess = session

        publisher = null
        session = null
        publishedTracks = emptyList()
        trackStates.clear()
        publisherState = PublisherState.Idle
        sessionState = Session.State.Idle
        isPublishStalled = false
        isNetworkDown = false
        publishStatsText = null
        publishedVideoAspect = null

        viewModelScope.launch {
            pub?.stop()
            sess?.close()
        }

        cleanupSources(keepCameraPreview = keepCameraPreview)
    }

    override fun onCleared() {
        super.onCleared()
        (getApplication<Application>().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.unregisterDisplayListener(displayListener)
        cancelReconnect()
        lastLifecycleOwner = null
        stopPublishing(keepCameraPreview = false)
    }

    private fun resetAfterPublishFailure(keepCameraPreview: Boolean = false) {
        watchdog?.stop()
        watchdog = null
        networkAvailability?.stop()
        networkAvailability = null
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        publisherJobs.forEach { it.cancel() }
        publisherJobs.clear()
        sessionJob?.cancel()
        sessionJob = null

        val pub = publisher
        val sess = session

        publisher = null
        session = null
        publishedTracks = emptyList()
        trackStates.clear()
        publisherState = PublisherState.Idle
        sessionState = Session.State.Idle
        isPublishStalled = false
        isNetworkDown = false
        publishStatsText = null
        publishedVideoAspect = null

        viewModelScope.launch {
            try {
                pub?.stop()
            } catch (_: Exception) {}
            try {
                sess?.close()
            } catch (_: Exception) {}
        }

        cleanupSources(keepCameraPreview = keepCameraPreview)
    }

    // MARK: - Uplink watchdog & reconnect

    private fun startWatchdog(pub: Publisher) {
        val nav = NetworkAvailability(getApplication())
        nav.onChanged = { down -> onNetworkAvailabilityChanged(pub, down) }
        nav.start()
        isNetworkDown = nav.isDown
        networkAvailability = nav

        networkRecoveryPolicy = NetworkRecoveryPolicy()
        if (nav.isDown) {
            // The network dropped between connect and watchdog start; treat it like
            // any other outage so a later recovery rebuilds the data plane.
            networkRecoveryPolicy.onNetworkDown(System.currentTimeMillis())
        }

        val wd = watchdogFactory()
        wd.onStateChanged = { state -> onWatchdogState(pub, state) }
        wd.start(viewModelScope) {
            val activity = pub.activity
            if (!nav.isDown) {
                // While the network is down nothing actually leaves the device, so
                // freeze the counters instead of showing ever-growing enqueue totals.
                publishStatsText = formatPublishStats(activity)
            }
            activity.lastWriteAtMs
        }
        // Seed the watchdog with the current network state (it starts HEALTHY).
        if (nav.isDown) wd.setNetworkDown(true)
        watchdog = wd
    }

    /**
     * Feeds the ConnectivityManager signal into the watchdog and, on a down→up
     * transition during publishing, schedules a full data-plane rebuild.
     */
    private fun onNetworkAvailabilityChanged(pub: Publisher, down: Boolean) {
        isNetworkDown = down
        watchdog?.setNetworkDown(down)
        if (down) {
            networkRecoveryJob?.cancel()
            networkRecoveryJob = null
            networkRecoveryPolicy.onNetworkDown(System.currentTimeMillis())
            return
        }
        val outageMs = networkRecoveryPolicy.onNetworkUp(System.currentTimeMillis()) ?: run {
            Log.i(TAG, "network restored (brief outage); keeping publish gen $publishGeneration")
            return
        }
        scheduleDataPlaneRebuild(pub, outageMs)
    }

    /**
     * Rebuilds the session and publisher once the network has been validated for
     * [NetworkRecoveryPolicy.stabilizeMs]. A QUIC session can come out of a network
     * outage wedged — it accepts enqueues but nothing egresses, and never errors —
     * so recovery requires recreating the data plane, not just flagging the stall.
     * Uses the regular reconnect path, so failures follow the same retry/budget
     * policy as any other reconnect.
     */
    private fun scheduleDataPlaneRebuild(pub: Publisher, outageMs: Long) {
        networkRecoveryJob?.cancel()
        networkRecoveryJob = viewModelScope.launch {
            Log.i(
                TAG,
                "network restored after ${outageMs}ms outage; rebuilding data plane " +
                    "(gen $publishGeneration) once validated for ${networkRecoveryPolicy.stabilizeMs}ms",
            )
            delay(networkRecoveryPolicy.stabilizeMs)
            if (pub !== publisher || publisherState != PublisherState.Publishing || isReconnecting) {
                Log.i(TAG, "data-plane rebuild skipped (gen $publishGeneration no longer publishing)")
                return@launch
            }
            beginReconnect("Network restored after ${outageMs}ms outage — rebuilding the connection")
        }
    }

    private fun onWatchdogState(pub: Publisher, state: PublishWatchdog.State) {
        if (pub !== publisher) return
        when (state) {
            PublishWatchdog.State.HEALTHY -> isPublishStalled = false
            PublishWatchdog.State.STALLED -> isPublishStalled = true
            PublishWatchdog.State.DEAD ->
                beginReconnect("Publish stalled: no frames reached the relay")
        }
    }

    private fun onSessionEnded(s: Session, state: Session.State) {
        if (s !== session || isReconnecting) return
        if (publisherState != PublisherState.Publishing) return
        val reason = when (state) {
            is Session.State.Error -> "Session error: ${state.message}"
            else -> "Session closed unexpectedly"
        }
        beginReconnect(reason)
    }

    private fun beginReconnect(reason: String) {
        if (isReconnecting) return
        Log.i(TAG, "beginReconnect: $reason")
        isReconnecting = true
        reconnectAttempts = 0
        reconnectAttempt = 0
        reconnectStartedAtMs = System.currentTimeMillis()
        teardownForReconnect()
        scheduleReconnectRetry(reason)
    }

    private fun scheduleReconnectRetry(cause: String?) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            giveUpReconnect(
                "Connection lost; automatic reconnect failed after " +
                    "$MAX_RECONNECT_ATTEMPTS attempts. Press Publish to try again."
            )
            return
        }
        val elapsedMs = System.currentTimeMillis() - reconnectStartedAtMs
        if (elapsedMs >= RECONNECT_BUDGET_MS) {
            giveUpReconnect(
                "Connection lost; automatic reconnect gave up after ${elapsedMs / 1000}s " +
                    "(budget ${RECONNECT_BUDGET_MS / 1000}s). Press Publish to try again."
            )
            return
        }
        val owner = lastLifecycleOwner
        val url = lastRelayUrl
        if (owner == null || url.isNullOrEmpty()) {
            giveUpReconnect("Connection lost; cannot reconnect automatically. Press Publish to try again.")
            return
        }
        reconnectAttempts++
        reconnectAttempt = reconnectAttempts
        Log.i(
            TAG,
            "reconnect attempt $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS " +
                "(elapsed ${elapsedMs}ms of ${RECONNECT_BUDGET_MS}ms budget, cause: ${cause ?: "unknown"})",
        )
        lastError = if (cause.isNullOrEmpty()) {
            "Connection lost — reconnecting ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)…"
        } else {
            "Connection lost — reconnecting ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)… [$cause]"
        }
        reconnectJob = viewModelScope.launch {
            delay(reconnectDelayMs)
            startPublishing(owner, url)
        }
    }

    private fun giveUpReconnect(message: String) {
        Log.w(TAG, "reconnect gave up (gen $publishGeneration): $message")
        isReconnecting = false
        reconnectAttempt = 0
        lastError = message
        // Full cleanup returns the UI to idle so the Publish button is enabled again.
        stopPublishing(keepCameraPreview = true)
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false
        reconnectAttempts = 0
        reconnectAttempt = 0
        reconnectStartedAtMs = 0L
    }

    private fun teardownForReconnect() {
        watchdog?.stop()
        watchdog = null
        networkAvailability?.stop()
        networkAvailability = null
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        publisherJobs.forEach { it.cancel() }
        publisherJobs.clear()
        sessionJob?.cancel()
        sessionJob = null

        val pub = publisher
        val sess = session

        publisher = null
        session = null
        publishedTracks = emptyList()
        trackStates.clear()
        publisherState = PublisherState.Idle
        sessionState = Session.State.Idle
        isPublishStalled = false
        isNetworkDown = false
        publishStatsText = null

        viewModelScope.launch {
            try {
                pub?.stop()
            } catch (_: Exception) {}
            try {
                sess?.close()
            } catch (_: Exception) {}
        }

        cleanupSources(keepCameraPreview = true)
    }

    private fun formatPublishStats(activity: PublishActivity): String {
        val mb = activity.bytesWritten / (1024.0 * 1024.0)
        // writeFrame only enqueues into the transport; these are not confirmed sends.
        return String.format(Locale.US, "queued %,d frames · %.1f MB (enqueue)", activity.framesWritten, mb)
    }

    private fun cleanupSources(keepCameraPreview: Boolean) {
        microphone?.stop()
        microphone = null
        screenCapture?.stop()
        screenCapture = null
        getApplication<Application>().stopService(
            Intent(getApplication(), ScreenCaptureService::class.java)
        )
        if (!keepCameraPreview) {
            stopCamera()
        }
    }

    private fun stopSingleCamera() {
        camera?.stop()
        camera = null
    }

    private fun stopMultiCamera() {
        multiCamera?.stop()
        multiCamera = null
    }

    private suspend fun runningMultiCameraCapture(
        videoConfig: VideoEncoderConfig,
        lifecycleOwner: LifecycleOwner,
    ): MultiCameraCapture {
        check(isMultiCameraSupported) {
            "Multi-camera capture is not supported on this device"
        }
        check(MultiCameraCapture.isFrontBackSupported(getApplication())) {
            "No concurrent front/back camera pair is available on this emulator/device"
        }

        val existing = multiCamera
        if (existing != null && isMultiCamera(existing, videoConfig)) {
            existing.setDisplayRotation(currentDisplayRotationDegrees())
            existing.start(getApplication(), lifecycleOwner)
            return existing
        }

        stopMultiCamera()

        val capture = makeMultiCameraCapture(videoConfig)
        capture.setDisplayRotation(currentDisplayRotationDegrees())
        applyMultiCameraPreviewSurfaces(capture)
        multiCamera = capture
        try {
            capture.start(getApplication(), lifecycleOwner)
            return capture
        } catch (e: Exception) {
            if (multiCamera === capture) {
                multiCamera = null
            }
            capture.stop()
            throw e
        }
    }

    private fun makeMultiCameraCapture(videoConfig: VideoEncoderConfig): MultiCameraCapture =
        MultiCameraCapture(
            front = CameraStreamConfig(
                position = CameraPosition.Front,
                width = videoConfig.width,
                height = videoConfig.height,
                frameRate = videoConfig.frameRate,
            ),
            back = CameraStreamConfig(
                position = CameraPosition.Back,
                width = videoConfig.width,
                height = videoConfig.height,
                frameRate = videoConfig.frameRate,
            ),
        )

    private fun applyMultiCameraPreviewSurfaces(capture: MultiCameraCapture) {
        capture.frontSource.setPreviewSurface(frontPreviewSurface)
        capture.backSource.setPreviewSurface(backPreviewSurface)
    }

    private fun isMultiCamera(
        capture: MultiCameraCapture,
        videoConfig: VideoEncoderConfig,
    ): Boolean =
        capture.front.width == videoConfig.width
            && capture.front.height == videoConfig.height
            && capture.front.frameRate == videoConfig.frameRate
            && capture.back.width == videoConfig.width
            && capture.back.height == videoConfig.height
            && capture.back.frameRate == videoConfig.frameRate

    /**
     * Display rotation in degrees for the moqkit renderer (0 when unavailable).
     */
    private fun currentDisplayRotationDegrees(): Int {
        val dm = getApplication<Application>().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        return when (dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private val isDisplayPortrait: Boolean
        get() = getApplication<Application>().resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    /**
     * Pushes the current display rotation into the running camera sources. Call
     * from UI on every configuration/rotation change; encoder dimensions stay as
     * chosen at publish start (the renderer keeps frames world-upright and
     * center-crops orientation mismatches).
     */
    fun onDisplayRotationChanged() {
        val degrees = currentDisplayRotationDegrees()
        camera?.setDisplayRotation(degrees)
        multiCamera?.setDisplayRotation(degrees)
    }

    /**
     * AND-V42-002: the encoded frame must follow the device orientation. A fixed
     * landscape 1280x720 encode of a portrait-held phone forced an isotropic
     * center-crop that discarded ~69% of the vertical field of view — the 실기기
     * "가로로 늘어나고 과도하게 확대" report. In portrait the WxH are swapped
     * (720x1280), which makes the upright camera frame and the encode target the
     * same aspect: full FOV, no crop, no zoom.
     */
    private fun currentVideoConfig(): VideoEncoderConfig {
        val portrait = isDisplayPortrait
        return VideoEncoderConfig(
            codec = videoCodec,
            width = if (portrait) videoResolution.height else videoResolution.width,
            height = if (portrait) videoResolution.width else videoResolution.height,
            frameRate = videoFrameRate.fps,
        )
    }

    private fun currentAudioConfig(): AudioEncoderConfig = AudioEncoderConfig(
        codec = audioCodec,
        sampleRate = audioSampleRate,
    )

    private fun publishUnsupportedReason(
        videoConfig: VideoEncoderConfig = currentVideoConfig(),
        audioConfig: AudioEncoderConfig = currentAudioConfig(),
    ): String? {
        if (cameraEnabled && cameraSourceMode == CameraSourceMode.MultiCamera && !isMultiCameraSupported) {
            return "Multi-camera capture is not supported on this device"
        }
        if ((cameraEnabled || screenEnabled) && !videoConfig.isSupported) {
            return videoConfig.unsupportedReason ?: "Selected video codec is not supported"
        }
        if (micEnabled && !audioConfig.isSupported) {
            return audioConfig.unsupportedReason ?: "Selected audio codec is not supported"
        }
        return null
    }

    private fun observePublisher(pub: Publisher, tracks: List<PublishedTrack>) {
        publisherJobs += pub.state.onEach { publisherState = it }.launchIn(viewModelScope)

        publisherJobs += pub.events.onEach { event ->
            when (event) {
                is PublisherEvent.TrackStarted -> trackStates[event.name] = PublishedTrackState.Active
                is PublisherEvent.TrackStopped -> trackStates[event.name] = PublishedTrackState.Stopped
                is PublisherEvent.TrackError -> {
                    trackStates[event.name] = PublishedTrackState.Stopped
                    lastError = "${event.name}: ${event.message}"
                }
            }
        }.launchIn(viewModelScope)

        for (track in tracks) {
            publisherJobs += track.state.onEach { state ->
                trackStates[track.name] = state
            }.launchIn(viewModelScope)
        }
    }
}
