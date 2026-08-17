package com.swmansion.moqdemo.features.publisher

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

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

    /**
     * Relay URL, owned by the ViewModel (AND-V43-007: a screen-scoped
     * rememberSaveable was disposed on Publisher re-entry and reset the field to
     * the public default). Restored from the last SUCCESSFUL publish across app
     * restarts; [initRelayUrl] seeds it once from the navigation default.
     */
    var relayUrl by mutableStateOf("")

    fun initRelayUrl(defaultUrl: String) {
        if (relayUrl.isNotEmpty()) return
        val saved = getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LAST_RELAY_URL, null)
        relayUrl = if (saved.isNullOrBlank()) defaultUrl else saved
    }

    private fun persistLastRelayUrl(url: String) {
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_RELAY_URL, url)
            .apply()
    }

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

    /**
     * AND-V48-001: set when the reconnect loop detects a framing storm —
     * consecutive publishes dying within seconds of coming up. The verified
     * storm state is process-scoped native transport corruption that neither
     * Stop/Publish nor a full chain restart clears (only an app process
     * restart does), so the loop stops retrying and the UI offers a restart.
     */
    var stormDetected by mutableStateOf(false)
        private set

    /**
     * v4.11 (OBS-V49-003, 요구 확정): a mid-broadcast orientation flip
     * republishes automatically with the new encode orientation. ON by
     * default; the toggle covers deliberate fixed-orientation broadcasts.
     */
    var autoRotateBroadcast by mutableStateOf(true)

    // 2026-08-12 audio capture death: the SDK surfaces a dead mic as a
    // TrackError + Stopped mic track (previously indistinguishable from
    // silence). The app policy is a bounded auto re-arm (live republish),
    // then a persistent banner with a manual retry.
    /** True while an automatic audio re-arm republish is pending or in flight. */
    var audioRecovering by mutableStateOf(false)
        private set

    /** True once the auto re-arm budget is exhausted — persistent banner + manual retry. */
    var audioDead by mutableStateOf(false)
        private set

    /** Auto re-arm attempts consumed in this broadcast (observable for the banner/tester). */
    var audioRearmAttempts by mutableStateOf(0)
        private set

    private var audioRearmJob: Job? = null

    /**
     * Debug-only: injects read failures into the live and every newly created
     * microphone (MicrophoneCapture.debugForceReadFailures), so the ~2s
     * give-up and the whole death->re-arm->banner chain can be exercised on
     * an unrooted device. Set via [setDebugForceMicFailure].
     */
    var debugForceMicFailure by mutableStateOf(false)
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

    /**
     * v4.12 (실기기 DEFECT-01, P0 프라이버시): every per-generation resource in
     * ONE bundle owned by its generation. The single-slot fields below remain
     * the "current generation" view for UI/stats, but teardown ownership
     * lives here — a field overwritten by a newer generation used to strand
     * the old instance beyond every teardown path's reach, and an abandoned
     * (2s-timeout) native close then left an orphan mic streaming behind an
     * idle UI until force-stop.
     */
    private class GenResources(val generation: Int, val session: Session) {
        var publisher: Publisher? = null
        var microphone: MicrophoneCapture? = null
        var screenCapture: ScreenCapture? = null
        /** True once the generation became the OWNED live publish (commit). */
        var committed = false
        var released = false
    }

    /** The most recently started generation's resource bundle (Main-confined). */
    private var activeGenResources: GenResources? = null

    /**
     * Idempotently releases one generation's resources. The capture sources
     * die FIRST and synchronously — mic/screen stop cannot wedge, so even if
     * the bounded native close below is abandoned, the orphan has no live
     * source left and the relay's QUIC idle-timeout clears the residue
     * (실기기: abandoned close가 idle UI 뒤에서 마이크를 20분+ 송출시킴).
     * Returns the bounded native-close job, or null if already released.
     */
    private fun releaseGenResources(gen: GenResources?, why: String): Job? {
        gen ?: return null
        // 2026-08-16 QA D-03 (지각 등록 레이스): a teardown can release this
        // bundle while the connect coroutine sits between two suspensions
        // BEFORE its mic/screen exist — the already-dispatched continuation
        // then creates, registers and STARTS the capture past the supersede
        // fence, and the coroutine's finally re-release used to early-return
        // on `released`, stranding an Active AudioRecord until force-stop
        // (실기기 flinger: sessions 481/513 both ref 2, Active). Capture refs
        // are consumed (nulled) as they are stopped, so EVERY call sweeps
        // late registrations and repeats are no-ops; capture stop() is
        // idempotent. Session/publisher cannot join late (both register
        // before the first suspension after the fence), so the bounded
        // native close still runs exactly once, on the first release.
        val mic = gen.microphone
        gen.microphone = null
        val screen = gen.screenCapture
        gen.screenCapture = null
        val plan = genReleasePlan(
            alreadyReleased = gen.released,
            hasMic = mic != null,
            hasScreen = screen != null,
        )
        if (plan.logLateReclaim) {
            Log.w(
                TAG,
                "gen ${gen.generation} late-registered capture reclaimed ($why): " +
                    "mic=${mic != null} screen=${screen != null}",
            )
        }
        if (plan.stopMic) runCatching { mic?.stop() }
        if (plan.stopScreen) runCatching { screen?.stop() }
        if (!plan.runNativeClose) return null
        gen.released = true
        val pub = gen.publisher
        return viewModelScope.launch {
            // v4.12 재테스트 OBS-01: the timeout warning must say WHICH close
            // stage overran and how long each took, so a server-side residue
            // (relay session lingering until idle-timeout) can be matched to
            // its app-side stage from a single log line.
            val pubStartMs = System.currentTimeMillis()
            val pubOk = kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                try {
                    pub?.stop()
                } catch (_: Exception) {}
            } != null
            val pubMs = System.currentTimeMillis() - pubStartMs
            val sessStartMs = System.currentTimeMillis()
            val sessOk = kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                try {
                    gen.session.close()
                } catch (_: Exception) {}
            } != null
            val sessMs = System.currentTimeMillis() - sessStartMs
            if (!pubOk || !sessOk) {
                Log.w(
                    TAG,
                    "gen ${gen.generation} native close timed out ($why) — " +
                        "publisher.stop ${if (pubOk) "ok" else "TIMEOUT"} ${pubMs}ms, " +
                        "session.close ${if (sessOk) "ok" else "TIMEOUT"} ${sessMs}ms; " +
                        "capture sources already stopped; the relay idle-timeout " +
                        "clears the session residue",
                )
            }
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
    // The in-flight connect+publish coroutine of startPublishing. Tracked so
    // every teardown path can cancel it: an untracked connect racing Stop (or a
    // superseding publish) could otherwise complete afterwards and resurrect a
    // live uplink + watchdog that nothing owns (review round 2, kit-lifecycle).
    private var connectJob: Job? = null
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

    /**
     * AND-V48-001 (immortal-reconnect fix): true from beginReconnect until the
     * recovered publish has stayed up for [STABLE_RESET_MS]. v4.8 cleared the
     * attempt count and the 90s budget clock the instant "publish gen up" was
     * logged, so a storm whose sessions lived 2.5-4.4s re-armed a fresh budget
     * every cycle and giveUpReconnect could never fire (실기기: 12분·121세대
     * 폭풍에서 give-up 0회). While the run is active, transient successes keep
     * the run's clock and attempt count.
     */
    private var reconnectRunActive = false
    private var stabilityResetJob: Job? = null

    /** AND-V48-001 storm detection: consecutive publishes that died young. */
    private val stormDetector = StormDetector()
    private var lastPublishUpAtMs = 0L

    /** AND-V48-001: availability seen by the reconnect watch (null = unknown). */
    @Volatile
    private var reconnectNetworkIsDown: Boolean? = null

    /**
     * OBS-V49-001: true while the app UI is STARTED (visible). A full-screen
     * system interposition (Samsung FOTA install screen, 실기기 08-07) pushed
     * the app behind mid-broadcast: the camera feed stops, background dials
     * time out (and Samsung Freecess freezes the process in bursts), so the
     * 90s wall-clock budget burned with zero usable attempts and the run
     * died in give-up — the user had to re-Publish manually. While invisible,
     * retries HOLD and the budget clock pauses; returning to the foreground
     * wakes the run immediately (same recovery semantics as a validated
     * network re-appearing).
     */
    @Volatile
    private var appVisible = true
    private var invisibleSinceMs = 0L
    private var reconnectPausedTotalMs = 0L

    /**
     * v4.11 방송 유지 (요구 확정): true from publish() until Stop / give-up /
     * storm. While active, the typed camera/mic foreground service anchors
     * the process and [cameraOwner] keeps the camera bound across
     * backgrounding — the broadcast survives full-screen interpositions
     * instead of holding and resuming (v4.10 behavior stays as the FALLBACK
     * when the service cannot start).
     */
    private var broadcastActive = false

    /**
     * True while OUR camera/mic-typed keep-alive FGS anchors the process.
     * Review R2: this must track only the keep-alive service — recognizing
     * ScreenCaptureService's mediaProjection FGS here made fgsActive a lie
     * the moment a reconnect teardown stopped that service, which disabled
     * the visibility hold and burned the budget in the background.
     */
    @Volatile
    private var fgsActive = false

    /** True only when WE started BroadcastForegroundService (owns its stop). */
    private var keepAliveServiceStarted = false

    /**
     * Review R2: whether reconnect dials may run (and the budget clock may
     * tick) while the app is invisible. Only the keep-alive FGS survives
     * teardownForReconnect, and a screen-source republish must restart
     * ScreenCaptureService — an FGS start that is background-restricted (and
     * mediaProjection tokens are version-fragile to reuse) — so ANY broadcast
     * that includes the screen source keeps the v4.10 hold+budget-pause
     * semantics and recovers on foreground return.
     */
    private fun backgroundDialCapable(): Boolean =
        keepAliveServiceStarted && fgsActive && !screenEnabled

    /** v4.11: broadcast-aware lifecycle the cameras bind against. */
    private val cameraOwner = BroadcastCameraLifecycleOwner()

    private fun updateCameraOwnerState() {
        // Review R1: broadcastActive alone kept the camera bound while
        // backgrounded even when the FGS never started — the OS then kills
        // the background camera with an error instead of the clean v4.10
        // hold. Background retention is only legal under a typed FGS.
        cameraOwner.setActive(appVisible || (broadcastActive && fgsActive))
    }

    private fun hasRuntimePermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Review R1: the keep-alive FGS types must mirror the ACTIVE sources and
     * their granted runtime permissions — API 34+ throws SecurityException
     * from startForeground for a camera/microphone type whose permission is
     * missing (screen-only broadcasts never request CAMERA at all).
     */
    private fun requiredKeepAliveFgsTypes(): Int {
        var types = 0
        if (cameraEnabled && hasRuntimePermission(Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (micEnabled && hasRuntimePermission(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun startBroadcastKeepAlive() {
        if (!fgsActive) {
            val types = requiredKeepAliveFgsTypes()
            if (types == 0) {
                // Review R2: no mediaProjection "anchor recognition" here — a
                // screen-only broadcast does keep running in the background
                // under ScreenCaptureService, but that service does NOT
                // survive reconnect teardown, so it must not unlock
                // background dials (backgroundDialCapable) and fgsActive must
                // never claim an FGS this class doesn't own. Camera/mic
                // sources without their runtime permission cannot legally
                // hold a typed FGS either (API 34+ SecurityException).
                Log.w(TAG, "broadcast keep-alive unavailable (no permitted FGS type) — visibility-hold fallback")
            } else {
                try {
                    // Review R2 (async rejection): startForegroundService only
                    // schedules — a later OEM-policy startForeground failure
                    // surfaces via this callback, which reverts to the
                    // visibility-hold fallback instead of leaving fgsActive a
                    // lie (background camera would die with an error).
                    BroadcastForegroundService.onStartFailed = { handleKeepAliveStartFailure() }
                    BroadcastForegroundService.start(getApplication(), types)
                    fgsActive = true
                    keepAliveServiceStarted = true
                } catch (e: Exception) {
                    // Notification/FGS unavailable: v4.10 hold+budget-pause path
                    // still covers backgrounding, just without live continuation.
                    Log.w(TAG, "broadcast foreground service unavailable (${e.message}) — visibility-hold fallback")
                    BroadcastForegroundService.onStartFailed = null
                    fgsActive = false
                }
            }
        }
        broadcastActive = true
        updateCameraOwnerState()
    }

    /**
     * Review R2: the service's startForeground runs asynchronously in
     * onStartCommand — an OEM rejection there lands here (main thread) so the
     * VM state stops claiming an FGS that never came up.
     */
    private fun handleKeepAliveStartFailure() {
        if (!keepAliveServiceStarted) return
        Log.w(TAG, "keep-alive FGS rejected after start — reverting to visibility-hold fallback")
        keepAliveServiceStarted = false
        fgsActive = false
        updateCameraOwnerState()
        // A held reconnect loop re-reads the gates on its next 2s tick; wake
        // it so a background run switches to hold semantics promptly.
        reconnectWake?.complete(Unit)
    }

    private fun stopBroadcastKeepAlive() {
        broadcastActive = false
        fgsActive = false
        updateCameraOwnerState()
        BroadcastForegroundService.onStartFailed = null
        if (keepAliveServiceStarted) {
            runCatching { BroadcastForegroundService.stop(getApplication()) }
            keepAliveServiceStarted = false
        }
    }

    /**
     * v4.9: the previous generation's fire-and-forget native close, joined by
     * the reconnect retry path before dialing the next generation. Serializing
     * close→connect removes native create/destroy overlap — the churn pressure
     * that accumulates toward the storm state — without blocking the UI.
     */
    private var pendingTeardown: Job? = null

    /**
     * Monotonic id identifying each session+publisher generation in logs.
     * 2026-08-16 QA D-05: increments ONLY when a new publish starts — one
     * logical republish costs exactly +1. The supersede fencing that teardown
     * paths used to express by bumping this counter (making every republish
     * consume +2 and every observed generation odd, which QA read as a double
     * republish) lives in [publishEpoch] now.
     */
    private var publishGeneration = 0

    /**
     * 2026-08-16 QA D-05: supersede fence, separate from the wire generation.
     * Bumped by EVERY teardown path and every publish start — exactly the
     * sites that bumped [publishGeneration] before — so in-flight connect
     * coroutines keep the identical invalidation semantics: a coroutine
     * captures the epoch at start and treats any mismatch as superseded.
     */
    private var publishEpoch = 0

    // Injectable seams for tests
    internal var watchdogFactory: () -> PublishWatchdog = { PublishWatchdog() }
    internal var reconnectDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS

    /** Delay before the NEXT scheduled retry; see beginReconnect(immediateFirstAttempt). */
    private var nextRetryDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS

    /**
     * AND-V47-002: reconnect-scoped network watch + wake. teardownForReconnect
     * stops the publisher-scoped [NetworkAvailability], so before v4.8 a
     * reconnect loop had NO network signal at all — after a 30s cut the fast
     * retry window had burned out DURING the outage and the first usable
     * moment landed inside a full 3s sleep (실기기 3.25s tail, ≤2s SLA 3/5
     * 위반). This second, reconnect-lifetime listener wakes the pending sleep
     * the instant a validated network (re)appears and re-arms the fast
     * cadence, so the winning attempt fires at network-usable time.
     */
    private var reconnectNetworkWatch: NetworkAvailability? = null
    private var reconnectWake: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var lastNetworkWakeAtMs: Long = 0L

    /**
     * Review R2 (v4.8): start time of the most recent reconnect ATTEMPT — the
     * reference for the 1s minimum inter-attempt spacing that a network wake
     * must respect. Without the floor, sustained network flapping plus
     * fast-failing connects collapsed the cadence below 1s and the 95-attempt
     * cap fired before the 90s budget (the invariant "time budget governs"
     * relies on attempts costing at least FAST_RECONNECT_DELAY_MS each).
     */
    private var lastAttemptStartedAtMs: Long = 0L

    companion object {
        // Sized so the 90s TIME budget is the genuine bound under EVERY
        // cadence mix: the invariant is cap >= budget / fastest cadence
        // (90s / 1s = 90) + margin, because AND-V47-002's network-wake can
        // legitimately hold the fast 1s cadence for the whole budget on a
        // flapping link (each validated re-appearance re-arms the fast
        // window). With 95, the attempt cap can only fire after the time
        // budget in every mix, and remains purely the pathological
        // tight-loop stop. (History: 5 died ~5s in; 40 — sized for the
        // pre-wake 15s fast window — exhausted at ~40s under sustained fast
        // cadence and halved the documented budget.)
        const val MAX_RECONNECT_ATTEMPTS = 95
        private const val PREFS_NAME = "moqdemo"
        private const val PREF_LAST_RELAY_URL = "last_relay_url"
        const val DEFAULT_RECONNECT_DELAY_MS = 3_000L

        /**
         * Total wall-clock budget for one automatic reconnect run. Each attempt is
         * bounded by the session-level fingerprint/connect timeouts, and the whole
         * run gives up once this budget is exhausted instead of dragging on for
         * several minutes.
         */
        const val RECONNECT_BUDGET_MS = 90_000L

        /** AND-V45-002: retry cadence inside the early-recovery fast window. */
        const val FAST_RECONNECT_DELAY_MS = 1_000L

        /** AND-V45-002: how long after beginReconnect the fast cadence applies. */
        const val FAST_RETRY_WINDOW_MS = 15_000L

        /**
         * AND-V48-001: how long a recovered publish must stay up before the
         * reconnect run bookkeeping (attempt count + 90s budget clock) is
         * cleared. Storm sessions live 2.5-4.4s; healthy recoveries hold
         * indefinitely — the value only needs to sit well above the storm
         * lifetime while keeping genuinely separate outages on fresh budgets.
         */
        const val STABLE_RESET_MS = 15_000L

        /** AND-V48-001: a publish dying this soon after "up" is short-lived. */
        const val STORM_SESSION_LIFETIME_MS = 5_000L

        /** AND-V48-001: consecutive short-lived publishes that trip storm mode. */
        const val STORM_CONSECUTIVE_THRESHOLD = 3

        /**
         * Review R1 (v4.9): a death after the publish held at least this long
         * is a SEPARATE outage and gets a fresh 90s budget even when the 15s
         * dwell has not cleared the run yet. Without this, an outage landing
         * in the (recovery, recovery+15s] window inherited the previous run's
         * spent budget and could give up after zero retries. Sits above the
         * storm lifetime (2.5-4.4s) so storm cycles still share one budget.
         */
        // 2026-08-14 QA D-05: raised 10s -> STABLE_RESET_MS. The corruption
        // regime also produces 12-15s-lived sessions; those evaded the 10s
        // gate, so every death started a FRESH 90s budget ("attempt 1/95,
        // elapsed ~30ms" forever) and the churn never gave up. A death before
        // the stability dwell cleared the run IS the same outage.
        const val RUN_CONTINUATION_MAX_LIFETIME_MS = STABLE_RESET_MS

        /**
         * Review R2 (v4.16): the storm STREAK clears only after this much
         * sustained stability — decoupled from the 15s run clear, because a
         * corruption regime living 15-25s per cycle sits past the run window
         * and a same-instant streak reset made the storm latch unreachable
         * (streak 0<->1 forever). Three signature deaths inside a minute of
         * each other now latch regardless of the per-cycle run bookkeeping.
         */
        const val STORM_STREAK_CLEAR_MS = 60_000L

        /** v4.9: bound on joining the previous generation's native close. */
        const val TEARDOWN_JOIN_TIMEOUT_MS = 3_000L

        /**
         * v4.11 auto-rotate: debounce so a rotation cascade (0→90→270 within
         * the same physical motion) republishes once, at the settled
         * orientation.
         */
        const val ROTATION_REPUBLISH_DEBOUNCE_MS = 1_000L

        /**
         * v4.12 (DEFECT-06): video writes older than this while audio stays
         * fresh mark a video-starved session (테스터 재검증 기준 §8-3의
         * "audio-only 5초 초과=FAIL"과 정렬).
         */
        const val VIDEO_STARVED_ESCALATE_MS = 5_000L

        /** Consecutive starvation rebuilds before holding with audio only. */
        const val VIDEO_STARVATION_REBUILD_CAP = 3

        /**
         * 2026-08-12 (operator policy): a dead audio capture triggers a
         * bounded automatic live republish — a silent broadcast is effectively
         * a dead broadcast, and a republish costs ~0.7-2.3s (v4.12 실측). The
         * budget resets only at logical boundaries — an explicit user action
         * (publish/retryAudio/stop), a reconnect give-up, or the START of a
         * genuinely fresh reconnect run (separate outage) — NEVER on run
         * continuations: 2026-08-14 QA D-01 proved that a per-cycle reset
         * hands a permanently-failing mic an infinite series of fresh budgets
         * (gen 143→261 runaway). AND-V48-001's immortal reconnect is the
         * cautionary tale.
         */
        const val MAX_AUDIO_REARM_ATTEMPTS = 2

        /** Backoff before an automatic audio re-arm republish. */
        const val AUDIO_REARM_DELAY_MS = 3_000L

        /**
         * 2026-08-14 QA D-05: how long a reconnect-recovered generation may
         * run with a non-active mic track before ONE bounded audio recovery
         * fires (report SLA: A/V both normal within ~3s of recovery).
         */
        const val AUDIO_READY_TIMEOUT_MS = 3_000L

        private const val TAG = "PublisherViewModel"

        /**
         * Verified native-corruption close signatures (실기기 08-06: every
         * storm cycle died with one of these). Used to keep storm detection
         * specific — a server-side transient closing young sessions on a
         * healthy link must ride the normal bounded reconnect instead.
         */
        internal val STORM_REASON_SIGNATURE =
            Regex("short frame|frame too large|invalid frame", RegexOption.IGNORE_CASE)

        /**
         * 2026-08-14 QA D-03 (pure, host-JVM pinned): non-null when the
         * display orientation category no longer matches the broadcast's
         * frozen encode aspect. Landscape encode = aspect >= 1.
         */
        internal fun orientationMismatchMessage(publishedAspect: Float?, displayPortrait: Boolean): String? {
            val aspect = publishedAspect ?: return null
            val publishedPortrait = aspect < 1f
            if (displayPortrait == publishedPortrait) return null
            val want = if (publishedPortrait) "portrait" else "landscape"
            return "rotate back to $want (the broadcast orientation) before retrying"
        }

        /**
         * 2026-08-16 QA D-03 (pure, host-JVM pinned): what one
         * releaseGenResources call must do given the bundle's state. Capture
         * sources are swept on EVERY call — a resource can join the bundle
         * AFTER an earlier release ran (the late-registration race that
         * stranded Active AudioRecords 481/513 on the 실기기) — the late
         * reclaim is logged only when it actually reclaims something, and the
         * bounded native close runs exactly once, on the first release.
         */
        internal data class GenReleasePlan(
            val stopMic: Boolean,
            val stopScreen: Boolean,
            val logLateReclaim: Boolean,
            val runNativeClose: Boolean,
        )

        internal fun genReleasePlan(alreadyReleased: Boolean, hasMic: Boolean, hasScreen: Boolean): GenReleasePlan =
            GenReleasePlan(
                stopMic = hasMic,
                stopScreen = hasScreen,
                logLateReclaim = alreadyReleased && (hasMic || hasScreen),
                runNativeClose = !alreadyReleased,
            )

        /** Pure storm-signal decision for [StormDetector.recordSessionEnd] — see beginReconnect. */
        internal data class StormSignal(val countableUpAtMs: Long?, val forceShortLived: Boolean)

        /**
         * 2026-08-14 QA D-01/D-05 (pure, host-JVM pinned): which session
         * deaths feed the storm detector, and which count toward the streak
         * regardless of lifetime. Long CLEAN deaths stay countable (they
         * reset the streak); signature deaths on a validated network are
         * FORCED short (the 12-15s corruption regime evaded the lifetime
         * test); deaths without an "up" carry no signal.
         */
        internal fun stormSignal(
            lastPublishUpAtMs: Long,
            nowMs: Long,
            networkUpAtDeath: Boolean,
            corruptionSignature: Boolean,
        ): StormSignal {
            val lifetimeMs = if (lastPublishUpAtMs != 0L) nowMs - lastPublishUpAtMs else -1L
            val countable = lastPublishUpAtMs != 0L &&
                (lifetimeMs >= STORM_SESSION_LIFETIME_MS || (networkUpAtDeath && corruptionSignature))
            return StormSignal(
                countableUpAtMs = if (countable) lastPublishUpAtMs else null,
                forceShortLived = networkUpAtDeath && corruptionSignature,
            )
        }

        /**
         * Review R3 (pure, host-JVM pinned): which stability timer a freshly
         * committed publish generation schedules. A live republish (rotation,
         * audio re-arm) landing AFTER the 15s run-clear but BEFORE the 60s
         * streak-clear cancels the pending dwell via
         * cancelReconnectScheduling and used to reschedule NOTHING (run
         * already inactive, liveRepublish skips the reset branch) — the storm
         * streak was orphaned with no timer, so one later signature death
         * could latch a false storm on a long-stable broadcast. A nonzero
         * streak on a benign republish now re-arms a streak-only dwell.
         */
        internal fun postCommitStormAction(
            reconnectRunActive: Boolean,
            liveRepublish: Boolean,
            stormStreak: Int,
        ): PostCommitStormAction = when {
            reconnectRunActive -> PostCommitStormAction.FULL_DWELL
            !liveRepublish -> PostCommitStormAction.RESET_NOW
            stormStreak > 0 -> PostCommitStormAction.STREAK_ONLY_DWELL
            else -> PostCommitStormAction.NONE
        }

        /**
         * Review R6 (pure, host-JVM pinned): whether a session death entering
         * the reconnect path starts a GENUINELY FRESH run (fresh 90s budget +
         * audio re-arm budget reset) or continues the existing one. A death
         * before the 15s stability dwell cleared the run is the SAME outage —
         * treating it as fresh re-armed a new budget every storm cycle
         * (AND-V48-001/D-05 immortal reconnect); a publish that held >=
         * [RUN_CONTINUATION_MAX_LIFETIME_MS] makes the death a separate
         * outage even when the dwell has not fired yet. This was the last
         * storm-relevant decision still inline after stormSignal and
         * postCommitStormAction were extracted.
         */
        internal fun startsFreshRun(reconnectRunActive: Boolean, lifetimeMs: Long): Boolean =
            !reconnectRunActive || lifetimeMs >= RUN_CONTINUATION_MAX_LIFETIME_MS

        /**
         * Review R8/R12 (pure, host-JVM pinned): which committed generations
         * arm the audio readiness check. R8 widened reconnect-recovered
         * generations (D-05) to audio-recovery republishes (steady-state
         * wedge). R12 (operator-directed follow-up) widens to EVERY
         * mic-bearing generation: a mic wedged at Starting on a PLAIN first
         * publish or a rotation republish never reaches Stopped either, so
         * onAudioTrackDied can never fire and the broadcast ran silent
         * video-only with NO banner at all — the pre-existing gap the R8/R9
         * reviews parked. The invariant is now uniform: every generation
         * that should carry audio must prove it (mic Active) within
         * [AUDIO_READY_TIMEOUT_MS] or trigger the bounded recovery chain
         * (shared budget -> audioDead + capture release). The first two
         * parameters are DELIBERATELY ignored — they remain in the signature
         * so the truth-table pins kill any regression that re-introduces the
         * old gating.
         */
        internal fun armsPostRecoveryAudioCheck(
            reconnectRunActive: Boolean,
            audioRecovering: Boolean,
            micEnabled: Boolean,
        ): Boolean = micEnabled
    }

    /** What the publish-up commit does about storm bookkeeping — see [postCommitStormAction]. */
    internal enum class PostCommitStormAction { FULL_DWELL, RESET_NOW, STREAK_ONLY_DWELL, NONE }

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
        // Review R1 (cold-start preview): appVisible starts true, and the
        // screen's visibility observer replay is swallowed by the same-value
        // guard in onAppVisibilityChanged — without this push the camera
        // owner would sit at CREATED (black preview) until the first publish
        // or a background/foreground round-trip.
        updateCameraOwnerState()
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
                cam.start(getApplication(), cameraOwner)
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
                capture.start(getApplication(), cameraOwner)
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

    /**
     * OBS-V49-001: UI visibility feed from the screen's lifecycle observer.
     * Main-thread only (lifecycle callbacks).
     */
    fun onAppVisibilityChanged(visible: Boolean) {
        if (visible == appVisible) return
        appVisible = visible
        updateCameraOwnerState()
        val now = System.currentTimeMillis()
        if (!visible) {
            invisibleSinceMs = now
            if (isReconnecting && !backgroundDialCapable()) {
                Log.i(TAG, "app backgrounded — holding reconnect (budget paused)")
            }
            return
        }
        if (invisibleSinceMs != 0L) {
            // v4.11: with background dials unlocked (keep-alive FGS, no
            // screen source), backgrounding never held the run, so nothing
            // is excluded.
            if (reconnectRunActive && !backgroundDialCapable()) {
                // Clamp to the run start: a run that BEGAN while invisible
                // pauses from its own start, not from when the screen left.
                val pausedMs = now - maxOf(invisibleSinceMs, reconnectStartedAtMs)
                if (pausedMs > 0) {
                    reconnectPausedTotalMs += pausedMs
                    Log.i(TAG, "app visible again — resuming reconnect (${pausedMs}ms excluded from budget)")
                }
            }
            invisibleSinceMs = 0L
        }
        reconnectWake?.complete(Unit)
        // Review R1: rotation events are DEFERRED while backgrounded (the
        // guard in maybeAutoRotateRepublish) — re-derive the category match
        // once on return so an orientation that settled while away still
        // lands (debounced; no-op when everything matches).
        maybeAutoRotateRepublish()
    }

    /**
     * Budget clock for the current reconnect run: wall-clock minus the spans
     * the app spent invisible (OBS-V49-001). Freecess process freezes are
     * covered too — the visibility flip lands before the freeze, and the
     * post-thaw read subtracts the whole invisible span.
     */
    private fun reconnectElapsedMs(nowMs: Long): Long {
        var paused = reconnectPausedTotalMs
        if (!appVisible && !backgroundDialCapable() && invisibleSinceMs != 0L) {
            val ongoing = nowMs - maxOf(invisibleSinceMs, reconnectStartedAtMs)
            if (ongoing > 0) paused += ongoing
        }
        return nowMs - reconnectStartedAtMs - paused
    }

    fun publish(lifecycleOwner: LifecycleOwner, relayUrl: String) {
        // An explicit user publish re-arms the audio auto-recovery budget and
        // clears its banners; the automatic live republishes below (rotation,
        // audio re-arm) deliberately keep the budget.
        clearAudioRearmState()
        publishInternal(lifecycleOwner, relayUrl, liveRepublish = false)
    }

    private fun publishInternal(
        lifecycleOwner: LifecycleOwner,
        relayUrl: String,
        liveRepublish: Boolean,
    ) {
        // 2026-08-14 QA D-01 (P0): a LIVE republish (rotation, audio re-arm)
        // must cancel only the pending reconnect SCHEDULING — the full
        // cancelReconnect() also zeroes the storm streak, the attempt count
        // and the 90s budget clock, which is how the republish↔transport-death
        // loop kept all three bounds unreachable forever. Only an explicit
        // user publish resets the run bookkeeping.
        if (liveRepublish) cancelReconnectScheduling() else cancelReconnect()
        // AND-V48-001: an explicit user publish is a fresh start — clear the
        // storm latch so the banner reflects only what happens from here on
        // (if the process is still corrupted, three quick deaths re-latch it).
        stormDetected = false
        // v4.12: an explicit publish also re-arms the video-starvation budget.
        videoStarvationRebuilds = 0
        videoStarvationLatched = false
        lastRelayUrl = relayUrl
        lastLifecycleOwner = lifecycleOwner
        // v4.11 방송 유지: publish is always a foreground tap (or the
        // auto-rotate republish of a live broadcast), which is exactly when a
        // typed camera/mic FGS may start — anchor the broadcast before the
        // camera/session come up.
        startBroadcastKeepAlive()
        startPublishing(lifecycleOwner, relayUrl, liveRepublish)
    }

    private fun startPublishing(
        lifecycleOwner: LifecycleOwner,
        relayUrl: String,
        liveRepublish: Boolean = false,
    ) {
        lastError = null

        // Review R2: validation must precede ANY live-state mutation and must
        // never destroy a LIVE broadcast — this runs on top of one during an
        // auto-rotate republish, and the early returns below used to clear
        // the track UI and release the keep-alive FGS while the old encode
        // kept streaming (zombie state). wasLive gates the release: only a
        // publish that had nothing running may drop the anchor it just took.
        val wasLive = session != null || publisher != null

        val url = relayUrl.trim()
        if (url.isEmpty()) {
            lastError = "Relay URL is required"
            // Review R1 (FGS leak): publishInternal anchored the keep-alive
            // before validation — a rejected publish must release it, or the
            // "Broadcasting" notification outlives a broadcast that never
            // started. Reconnect retries keep theirs (the run is still live).
            if (!isReconnecting && !wasLive) stopBroadcastKeepAlive()
            // Review R3: a reconnect retry that fails validation returns
            // BEFORE the connect coroutine exists, so the catch-path
            // rescheduling never runs — a bare return froze the run as
            // "reconnecting…" forever (no retry, no give-up, Publish gated
            // off). Keep the loop alive so the 90s budget governs; a user
            // rotating back to a supported orientation then auto-recovers on
            // the next attempt. (scheduleReconnectRetry's yield boundary
            // makes this synchronous re-entry safe — the new job is assigned
            // before its body runs.)
            if (isReconnecting) scheduleReconnectRetry(lastError)
            return
        }

        val videoConfig = currentVideoConfig()
        val audioConfig = currentAudioConfig()
        publishUnsupportedReason(videoConfig, audioConfig)?.let {
            lastError = it
            if (!isReconnecting && !wasLive) stopBroadcastKeepAlive()
            // Review R3: same as above — the reconnect run must outlive a
            // transiently-unsupported orientation (portrait 1080x1920 on a
            // 1080-capped encoder) instead of freezing.
            if (isReconnecting) scheduleReconnectRetry(it)
            return
        }

        // Validation passed — only now may the new generation take over the
        // visible track state (review R2: these clears used to run before the
        // gates and wiped a live broadcast's track UI on a rejected republish).
        trackStates.clear()
        publishedTracks = emptyList()

        Log.i(
            TAG,
            "video config ${videoConfig.width}x${videoConfig.height}@${videoConfig.frameRate} " +
                "(displayRotation=${currentDisplayRotationDegrees()} portrait=$isDisplayPortrait)",
        )
        // A previous session may have ended on its own (idle timeout, transport error)
        // without an explicit stop; release it before starting a new one.
        if (session != null || publisher != null) {
            stopPublishing(keepCameraPreview = true)
        }
        // Assigned AFTER the defensive stopPublishing above (which nulls it):
        // the frozen encode aspect must hold for the entire new broadcast so
        // the preview keeps showing the framing viewers get (AND-V42-002).
        publishedVideoAspect = videoConfig.width.toFloat() / videoConfig.height

        val generation = ++publishGeneration
        val epoch = ++publishEpoch
        val s = Session(url = url, parentScope = viewModelScope)
        session = s
        // v4.12 (DEFECT-01): register the generation bundle the moment its
        // first resource exists — every resource created below joins it, and
        // every teardown path (plus the coroutine's own finally) releases it.
        val genRes = GenResources(generation, s)
        activeGenResources = genRes

        sessionJob = s.state.onEach { state ->
            sessionState = state
            if (state is Session.State.Error || state is Session.State.Closed) {
                onSessionEnded(s, state)
            }
        }.launchIn(viewModelScope)

        connectJob = viewModelScope.launch {
            try {
                s.connect()
                // Teardown may have superseded this generation while connect was
                // in flight (cancel() only lands at a suspension point); do not
                // resurrect state for a publish nobody owns anymore. The
                // finally below releases this generation's resources.
                if (epoch != publishEpoch) {
                    Log.i(TAG, "publish gen $generation superseded during connect; closing")
                    return@launch
                }

                val pub = Publisher()
                publisher = pub
                genRes.publisher = pub

                val tracks = mutableListOf<PublishedTrack>()

                if (cameraEnabled) {
                    when (cameraSourceMode) {
                        CameraSourceMode.SingleCamera -> {
                            val cam = camera ?: CameraCapture(position = cameraPosition).also {
                                it.start(getApplication(), cameraOwner)
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
                    // Debug fault injection follows the toggle onto every new
                    // capture session so re-arm attempts also die while ON.
                    mic.debugForceReadFailures = debugForceMicFailure
                    microphone = mic
                    genRes.microphone = mic
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
                    genRes.screenCapture = screen
                    screen.start(getApplication())
                    tracks += pub.addVideoTrack(name = "screen", source = screen, config = videoConfig)
                    trackStates["screen"] = PublishedTrackState.Idle
                }

                // Final commit gate: everything below makes this generation the
                // OWNED live publish (broadcast + watchdog + network callback).
                // A teardown that raced the non-suspending setup above must win.
                if (epoch != publishEpoch) {
                    Log.i(TAG, "publish gen $generation superseded before commit; discarding")
                    // v4.12 (DEFECT-01): the old code called cleanupSources
                    // here, which stops the FIELD sources — by now those can
                    // belong to the SUPERSEDING generation (killing the new
                    // broadcast's mic while stranding this one's). The finally
                    // releases exactly this generation's bundle instead.
                    return@launch
                }
                publishedTracks = tracks
                s.publish(broadcastPath, pub)
                pub.start()
                // v4.12: commit — from here this generation owns the live
                // publish and only a teardown (generation bump) releases it.
                genRes.committed = true

                observePublisher(pub, tracks)
                isReconnecting = false
                reconnectAttempt = 0
                lastError = null
                lastPublishUpAtMs = System.currentTimeMillis()
                // AND-V48-001 (immortal-reconnect fix): do NOT clear the run
                // bookkeeping here — storm publishes live 2.5-4.4s, and an
                // instant reset re-armed a fresh 90s budget every cycle. The
                // run (attempts, budget clock, network watch) only ends after
                // the publish stays up for STABLE_RESET_MS. Review R1 (v4.8)
                // still holds: the watch stops when the run ends, so it cannot
                // leak into steady-state publishing beyond the dwell.
                when (postCommitStormAction(reconnectRunActive, liveRepublish, stormDetector.consecutiveShortLived)) {
                    PostCommitStormAction.FULL_DWELL -> {
                        stabilityResetJob?.cancel()
                        stabilityResetJob = viewModelScope.launch {
                            delay(STABLE_RESET_MS)
                            if (epoch != publishEpoch) return@launch
                            reconnectRunActive = false
                            reconnectAttempts = 0
                            reconnectStartedAtMs = 0L
                            stopReconnectNetworkWatch()
                            Log.i(
                                TAG,
                                "publish gen $generation stable for ${STABLE_RESET_MS / 1000}s — reconnect run cleared",
                            )
                            // Review R2 (v4.16): the STORM STREAK outlives the run
                            // clear. With streak-reset at the same 15s as the
                            // run-continuation window, a corruption regime living
                            // 15-25s per cycle wiped the streak every cycle
                            // (0<->1 forever) and re-armed fresh budgets — the
                            // D-05 churn relocated one lifetime band up. Signature
                            // deaths keep feeding the streak across "fresh" runs;
                            // only SUSTAINED stability clears it.
                            delay(STORM_STREAK_CLEAR_MS - STABLE_RESET_MS)
                            if (epoch != publishEpoch) return@launch
                            stormDetector.reset()
                            stabilityResetJob = null
                            Log.i(
                                TAG,
                                "publish gen $generation stable for ${STORM_STREAK_CLEAR_MS / 1000}s — storm streak cleared",
                            )
                        }
                    }
                    PostCommitStormAction.RESET_NOW ->
                        // 2026-08-14 QA D-01: a LIVE republish must not reset the
                        // storm streak — the republish↔signature-death interleave
                        // used to wipe it here every cycle, keeping the latch
                        // unreachable. Only an explicit user publish starts clean.
                        stormDetector.reset()
                    PostCommitStormAction.STREAK_ONLY_DWELL -> {
                        // Review R3: this benign republish cancelled a pending
                        // streak-clear dwell (run already inactive) — without a
                        // fresh timer the streak stays pinned forever and one
                        // later signature death can false-latch the storm. A
                        // later republish cancels and re-arms this identically,
                        // so the timer is self-healing.
                        stabilityResetJob?.cancel()
                        stabilityResetJob = viewModelScope.launch {
                            delay(STORM_STREAK_CLEAR_MS)
                            if (epoch != publishEpoch) return@launch
                            stormDetector.reset()
                            stabilityResetJob = null
                            Log.i(
                                TAG,
                                "publish gen $generation stable for ${STORM_STREAK_CLEAR_MS / 1000}s — storm streak cleared",
                            )
                        }
                    }
                    PostCommitStormAction.NONE -> Unit
                }
                Log.i(TAG, "publish gen $generation up: tracks=${tracks.joinToString(",") { it.name }}")
                persistLastRelayUrl(url)
                startWatchdog(pub)
                // 2026-08-14 QA D-05: a reconnect-RECOVERED generation gets a
                // one-shot audio readiness check — video recovering while the
                // mic track never becomes active must trigger ONE bounded
                // audio recovery (through the per-broadcast budget), not sit
                // silent behind a playing picture.
                // Review R8: an AUDIO-RECOVERY republish gets the same check —
                // its rebuilt mic can wedge at Starting in STEADY STATE
                // (reconnectRunActive false), where onAudioTrackDied can never
                // re-fire (the wedge never reaches Stopped) and audioRecovering
                // stayed latched forever behind a "recovering" banner with no
                // Retry. The chain stays finite: each dial consumes the shared
                // budget and exhaustion latches audioDead + stops the capture
                // (R5). Review R12 (operator-directed): EVERY mic-bearing
                // generation now arms it — a wedge on a plain first publish or
                // a rotation republish was silent video-only with NO banner at
                // all (the parked pre-existing gap). The armed check no-ops on
                // an Active mic, so healthy generations pay nothing.
                // Decision = pure companion armsPostRecoveryAudioCheck.
                if (armsPostRecoveryAudioCheck(reconnectRunActive, audioRecovering, micEnabled)) {
                    armPostRecoveryAudioCheck(epoch, lifecycleOwner, url)
                }
            } catch (e: CancellationException) {
                // Cancelled by a teardown path, which owns all cleanup — do not
                // run the failure path (it would tear down the preview a Stop
                // deliberately kept and overwrite lastError after a clean stop).
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "publish gen $generation failed: ${e.message}")
                if (epoch != publishEpoch) {
                    // A superseded generation's failure is not news about the
                    // current one; teardown already reset the visible state.
                    Log.i(TAG, "publish gen $generation failure ignored (superseded)")
                    return@launch
                }
                if (isReconnecting) {
                    // A reconnect attempt failed; keep the camera preview alive and
                    // schedule the next attempt instead of giving up.
                    val cause = e.message ?: "Unknown error"
                    resetAfterPublishFailure(keepCameraPreview = true)
                    scheduleReconnectRetry(cause)
                } else if (liveRepublish) {
                    // Review R1: a live republish (auto-rotate, audio re-arm)
                    // must not demote a transient connect failure into a full
                    // stop — until this attempt the feed was healthy, so fall
                    // into the same bounded reconnect any mid-broadcast outage
                    // gets. beginReconnect owns the teardown (its
                    // teardownForReconnect closes the partial session and its
                    // pendingTeardown is what the retry loop joins — a prior
                    // reset here would shadow that join with a no-op).
                    beginReconnect("Live republish failed: ${e.message ?: "unknown error"}")
                } else {
                    lastError = e.message ?: "Unknown error"
                    resetAfterPublishFailure()
                    // Review R1 (FGS leak): a first publish that never came up
                    // ends here — release the keep-alive anchor with it.
                    stopBroadcastKeepAlive()
                }
            } finally {
                // v4.12 (실기기 DEFECT-01): EVERY exit that no longer owns the
                // live publish releases its OWN resources — the teardown paths
                // cover the common flows, but a cancellation rethrow or any
                // missed path used to strand this generation's mic and native
                // session (idle UI 뒤 오디오 송출·force-stop만이 정지). An
                // uncommitted exit releases even at the current generation:
                // cancelReconnect can kill this coroutine and its caller may
                // return before any generation bump (validation early-return),
                // which would otherwise strand the bundle. Idempotent with the
                // teardown-side release.
                if (!genRes.committed || epoch != publishEpoch) {
                    releaseGenResources(genRes, "connect coroutine exit")
                }
            }
        }
    }

    fun stop() {
        cancelReconnect()
        rotationRepublishJob?.cancel()
        rotationRepublishJob = null
        // The audio banners describe a broadcast that no longer exists; the
        // budget is per broadcast anyway.
        clearAudioRearmState()
        stopBroadcastKeepAlive()
        stopPublishing(keepCameraPreview = true)
    }

    private fun stopPublishing(keepCameraPreview: Boolean) {
        // Supersede and cancel any in-flight connect: bump the epoch fence
        // first (the guard the coroutine checks between non-suspending
        // steps), then cancel so it also dies at its next suspension point.
        // 2026-08-16 QA D-05: fence only — the wire generation increments
        // when the NEXT publish starts, not here.
        publishEpoch++
        connectJob?.cancel()
        connectJob = null
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

        val gen = activeGenResources
        activeGenResources = null

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

        // v4.12 (DEFECT-01): release via the generation bundle — the capture
        // sources die synchronously FIRST (privacy), then the bounded native
        // close runs; the retry loop keeps joining pendingTeardown.
        releaseGenResources(gen, "teardown")?.let { pendingTeardown = it }

        cleanupSources(keepCameraPreview = keepCameraPreview)
    }

    override fun onCleared() {
        super.onCleared()
        (getApplication<Application>().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.unregisterDisplayListener(displayListener)
        cancelReconnect()
        rotationRepublishJob?.cancel()
        rotationRepublishJob = null
        clearAudioRearmState()
        stopBroadcastKeepAlive()
        lastLifecycleOwner = null
        stopPublishing(keepCameraPreview = false)
    }

    private fun resetAfterPublishFailure(keepCameraPreview: Boolean = false) {
        // Called from the connect coroutine's own failure path: cancelling
        // connectJob here marks the (already-failing) coroutine cancelled,
        // which is harmless — its catch block is past the cancellation point.
        // 2026-08-16 QA D-05: epoch fence only (wire generation increments at
        // the next publish start).
        publishEpoch++
        connectJob?.cancel()
        connectJob = null
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

        val gen = activeGenResources
        activeGenResources = null

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

        // v4.12 (DEFECT-01): release via the generation bundle — the capture
        // sources die synchronously FIRST (privacy), then the bounded native
        // close runs; the retry loop keeps joining pendingTeardown.
        releaseGenResources(gen, "teardown")?.let { pendingTeardown = it }

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
            maybeEscalateVideoStarvation(pub, activity)
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
        // AND-V46-002: UNCONDITIONAL availability marker. The 08-04 재테스트
        // could not measure the recovery SLA because the only VALIDATED-side
        // log line lived inside the rebuild branch — and with the v4.6 relay
        // evicting dead sessions in 6s, recovery is normally triggered by the
        // session-error path first, so that branch (and its log) never ran.
        // This line fires on EVERY change while the listener is armed, giving
        // the VALIDATED timestamp independently of which path wins. (During an
        // in-flight reconnect the listener is torn down; the session-error
        // path's own markers — beginReconnect / reconnect attempt / publish
        // gen up — carry the measurement there. 시나리오 §2-6 참조.)
        Log.i(
            TAG,
            "network availability changed: down=$down " +
                "(gen $publishGeneration, reconnecting=$isReconnecting)",
        )
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
            beginReconnect(
                "Network restored after ${outageMs}ms outage — rebuilding the connection",
                immediateFirstAttempt = true,
            )
        }
    }

    /**
     * v4.12 (실기기 DEFECT-06): the aggregate watchdog reads only
     * [PublishActivity.lastWriteAtMs], so a broadcast whose VIDEO track died
     * stayed "healthy" for 40s+ on audio writes alone (0.avc1 NotFound 무한·
     * 앱은 camera active 표시). Escalate a video-starved-while-audio-flows
     * session into the normal bounded reconnect; three rebuilds without a
     * stable run stop the churn and surface the state instead.
     */
    private var videoStarvationRebuilds = 0
    private var videoStarvationLatched = false

    private fun maybeEscalateVideoStarvation(pub: Publisher, activity: PublishActivity) {
        if (pub !== publisher || isReconnecting || publisherState != PublisherState.Publishing) return
        // Review R1 (v4.12): CAMERA only. Screen capture is event-driven —
        // a static shared screen legitimately produces zero frames (no
        // onFrameAvailable, no REPEAT_PREVIOUS_FRAME), so an encoder-output
        // staleness test would tear down healthy slide-share broadcasts and
        // latch a false "video stopped" error. The camera sensor streams
        // frames regardless of scene motion, and the 실기기 DEFECT-06 failure
        // was camera-track death — the escalation keeps its teeth there.
        if (!cameraEnabled) return
        // Audio must be flowing for the ASYMMETRIC case this owns; total
        // silence stays with the aggregate watchdog.
        if (activity.lastAudioWriteAtMs == 0L) return
        if (networkAvailability?.isDown == true) return
        val now = System.currentTimeMillis()
        val audioFresh = now - activity.lastAudioWriteAtMs < 2_000L
        // Review R1 (v4.12, 렌즈A F1): a generation whose video NEVER started
        // (0.avc1 NotFound — the 실기기 30s-outage headline) keeps
        // lastVideoWriteAtMs at 0 forever, and the aggregate watchdog stays
        // healthy on audio writes alone — bailing on 0 exempted exactly the
        // case this escalation exists for. Measure never-started starvation
        // from the publish-up instant instead.
        val videoStarvedMs = when {
            activity.lastVideoWriteAtMs != 0L -> now - activity.lastVideoWriteAtMs
            lastPublishUpAtMs != 0L -> now - lastPublishUpAtMs
            else -> return
        }
        // A rebuild that actually restored video earns its budget back — but
        // only after the publish has been stable for the usual dwell, so a
        // rebuild whose video lives seconds before starving again cannot
        // launder the cap into an infinite churn loop.
        if (videoStarvedMs < 2_000L) {
            if ((videoStarvationRebuilds != 0 || videoStarvationLatched) &&
                lastPublishUpAtMs != 0L && now - lastPublishUpAtMs > STABLE_RESET_MS
            ) {
                videoStarvationRebuilds = 0
                videoStarvationLatched = false
            }
            return
        }
        if (!audioFresh || videoStarvedMs < VIDEO_STARVED_ESCALATE_MS) return
        if (videoStarvationLatched) return
        if (videoStarvationRebuilds >= VIDEO_STARVATION_REBUILD_CAP) {
            // Rebuilds are not helping (camera/HW-level failure) — keep the
            // audio alive instead of churning sessions forever, but say so.
            videoStarvationLatched = true
            lastError = "Video track stopped reaching the relay (audio still live). " +
                "Automatic rebuilds did not recover it — press Stop, then Publish to retry."
            Log.e(TAG, "video starved ${videoStarvedMs}ms with audio fresh — rebuild cap " +
                "($VIDEO_STARVATION_REBUILD_CAP) reached; holding with audio only")
            return
        }
        videoStarvationRebuilds++
        Log.w(
            TAG,
            "video track starved ${videoStarvedMs}ms while audio kept flowing " +
                "(rebuild ${videoStarvationRebuilds}/$VIDEO_STARVATION_REBUILD_CAP) — rebuilding the session",
        )
        beginReconnect("Video track starved for ${videoStarvedMs / 1000}s while audio kept flowing")
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

    /**
     * @param immediateFirstAttempt AND-V43-004: a network-recovery rebuild fires
     *   off a VALIDATED network, so waiting the standard inter-retry delay before
     *   the FIRST attempt only pads the outage (실측: 고정 3s 지연이 10초 차단
     *   복구를 전 회차 SLA 초과로 밀었음). Failure paths (watchdog DEAD, session
     *   error) keep the delay: there the network state is unknown and immediate
     *   redials would hammer a dead relay.
     */
    private fun beginReconnect(reason: String, immediateFirstAttempt: Boolean = false) {
        if (isReconnecting) return
        // 2026-08-14 QA D-01 (P0): a reconnect run supersedes the PENDING
        // re-arm dial (it rebuilds the mic each generation itself), but the
        // attempt BUDGET and the dead-latch are per logical broadcast and MUST
        // survive reconnect churn. The old full clearAudioRearmState() here
        // was the runaway's reset vector: every republish-induced transport
        // death handed the permanently-failing mic a fresh 2-attempt budget
        // (실기기 gen 143→261, ~5s/gen, 매 세대 1/2→2/2 재시작, force-stop
        // 필요). Budgets now reset only at logical boundaries: publish(),
        // retryAudio(), stop(), giveUpReconnect().
        suspendAudioRearmForReconnect()
        // AND-V48-001 storm detection: count consecutive publishes that died
        // within seconds of coming up. The verified storm mode (process-scoped
        // native transport corruption) kills every new session at its first
        // keyframe ~2.5-4.4s after "up"; retrying cannot help and each cycle
        // deepens the native churn — detect it and stop instead.
        val now = System.currentTimeMillis()
        val lifetimeMs = if (lastPublishUpAtMs != 0L) now - lastPublishUpAtMs else -1L
        // Storm signal needs BOTH guards (review R1 v4.9 — specificity):
        // 1) network still VALIDATED at death — a rapidly flapping link also
        //    produces short lifetimes, but those deaths land after the OS
        //    dropped the validated network; counting them would tell a user
        //    with a flapping link to restart a healthy app. Read BEFORE
        //    teardown: the publisher-scoped listener is still alive here.
        // 2) the close reason carries the verified corruption signature
        //    (short frame / frame too large / invalid frame). A server-side
        //    transient that closes young sessions on a healthy link (relay
        //    deploy loop) is NOT a storm — plain reconnect handles it and the
        //    90s budget still bounds it.
        val networkUpAtDeath = networkAvailability?.isDown != true
        val corruptionSignature = STORM_REASON_SIGNATURE.containsMatchIn(reason)
        // Long-lived CLEAN deaths always feed the detector (they RESET the
        // streak); short-lived deaths count only with both guards.
        // Non-qualifying short deaths are neutral. 2026-08-14 QA D-01/D-05: a
        // signature death on a validated network counts toward the streak
        // REGARDLESS of lifetime — the corruption regime also manifests as
        // 12-15s-lived sessions, which the lifetime test alone kept resetting
        // (the storm never latched under an unbounded generation churn).
        // Decision logic = pure companion stormSignal (host-JVM pinned).
        val signal = stormSignal(lastPublishUpAtMs, now, networkUpAtDeath, corruptionSignature)
        val storm = stormDetector.recordSessionEnd(
            signal.countableUpAtMs,
            now,
            forceShortLived = signal.forceShortLived,
        )
        lastPublishUpAtMs = 0L
        if (storm) {
            abortReconnectForStorm(reason)
            return
        }
        Log.i(TAG, "beginReconnect: $reason (immediateFirst=$immediateFirstAttempt)")
        stabilityResetJob?.cancel()
        stabilityResetJob = null
        isReconnecting = true
        // Review R1 (v4.9): a publish that held >= RUN_CONTINUATION_MAX_LIFETIME_MS
        // makes this death a SEPARATE outage — fresh budget — even though the
        // 15s dwell had not cleared the run yet. Only short-lived cycles
        // (storm profile) share one budget, which is what makes the 90s
        // give-up reachable during a storm. Decision = pure companion
        // startsFreshRun (host-JVM pinned, Review R6).
        if (startsFreshRun(reconnectRunActive, lifetimeMs)) {
            reconnectRunActive = true
            reconnectAttempts = 0
            reconnectStartedAtMs = now
            reconnectPausedTotalMs = 0L
            // Review R1 (v4.16): a GENUINELY fresh run (separate outage) also
            // refreshes the audio re-arm budget — the per-broadcast budget
            // bounds a PERMANENT audio cause, and after hours of healthy audio
            // a consumed budget from an old hiccup must not turn the next
            // transient mic death into a permanent manual-retry banner. Storm
            // continuations take the else branch (runActive + lifetime<15s)
            // and keep the consumed budget, so the D-01 runaway fix holds.
            audioRearmAttempts = 0
            audioDead = false
        } else {
            // AND-V48-001: a transient success (< STABLE_RESET_MS up) does NOT
            // start a fresh run — the budget clock and attempt count carry
            // over so the 90s budget can actually exhaust during a storm.
            Log.i(
                TAG,
                "beginReconnect: continuing run (elapsed ${reconnectElapsedMs(now) / 1000}s of " +
                    "${RECONNECT_BUDGET_MS / 1000}s budget, attempts so far $reconnectAttempts)",
            )
        }
        reconnectAttempt = reconnectAttempts
        nextRetryDelayMs = if (immediateFirstAttempt) 0L else reconnectDelayMs
        teardownForReconnect()
        startReconnectNetworkWatch()
        scheduleReconnectRetry(reason)
    }

    /**
     * AND-V48-001: [STORM_CONSECUTIVE_THRESHOLD] consecutive publishes died
     * within [STORM_SESSION_LIFETIME_MS] of coming up. 실기기 확정: this state
     * is process-scoped native transport corruption — app Stop/Publish and a
     * full chain restart do NOT clear it; only an app process restart does.
     * Retrying is actively harmful, so stop the loop and surface the remedy.
     */
    private fun abortReconnectForStorm(reason: String) {
        Log.e(
            TAG,
            // 2026-08-16 QA D-06 (stale wording): the streak also counts
            // corruption-signature deaths FORCED short regardless of lifetime
            // (the 12-15s regime) — the old "died < 5000ms" phrasing made QA
            // report lifetimes that contradicted the latch.
            "reconnect storm detected (gen $publishGeneration): ${stormDetector.consecutiveShortLived} consecutive " +
                "storm-eligible publish deaths (< ${STORM_SESSION_LIFETIME_MS}ms after up, or a " +
                "corruption-signature death on a healthy network at any lifetime; last cause: $reason) — " +
                "stopping automatic reconnect; app restart required",
        )
        stormDetected = true
        cancelReconnect()
        stopBroadcastKeepAlive()
        stopPublishing(keepCameraPreview = true)
        // Review R1 (v4.9): offer BOTH remedies — a rare server-side condition
        // could mimic the signature, and there a plain re-Publish suffices;
        // only the verified native-corruption storm needs the restart.
        lastError = "Transport keeps failing right after publish (storm signature). " +
            "Press Publish to retry once; if it trips again, restart the app " +
            "(the verified storm state only clears with a fresh app process)."
    }

    private fun startReconnectNetworkWatch() {
        if (reconnectNetworkWatch != null) return
        val watch = NetworkAvailability(getApplication())
        watch.onChanged = { down ->
            reconnectNetworkIsDown = down
            if (!down) {
                lastNetworkWakeAtMs = System.currentTimeMillis()
                Log.i(TAG, "network validated during reconnect — waking the pending retry immediately")
                reconnectWake?.complete(Unit)
            } else {
                Log.i(TAG, "network lost during reconnect — holding retries until it returns")
            }
        }
        watch.start()
        reconnectNetworkIsDown = watch.isDown
        reconnectNetworkWatch = watch
    }

    private fun stopReconnectNetworkWatch() {
        reconnectNetworkWatch?.stop()
        reconnectNetworkWatch = null
        reconnectWake = null
        reconnectNetworkIsDown = null
        lastNetworkWakeAtMs = 0L
        lastAttemptStartedAtMs = 0L
    }

    private fun scheduleReconnectRetry(cause: String?) {
        val elapsedMs = reconnectElapsedMs(System.currentTimeMillis())
        // Review R2 (v4.8): budget verdict first — the TIME budget is the
        // governing bound and its give-up message must win at t >= budget
        // regardless of how fast attempts were burned.
        if (elapsedMs >= RECONNECT_BUDGET_MS) {
            giveUpReconnect(
                "Connection lost; automatic reconnect gave up after ${elapsedMs / 1000}s " +
                    "(budget ${RECONNECT_BUDGET_MS / 1000}s). Press Publish to try again."
            )
            return
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            giveUpReconnect(
                "Connection lost; automatic reconnect failed after " +
                    "$MAX_RECONNECT_ATTEMPTS attempts. Press Publish to try again."
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
            "reconnect attempt $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS scheduled " +
                "(delay ${nextRetryDelayMs}ms, elapsed ${elapsedMs}ms of ${RECONNECT_BUDGET_MS}ms budget, " +
                "cause: ${cause ?: "unknown"})",
        )
        lastError = if (cause.isNullOrEmpty()) {
            "Connection lost — reconnecting ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)…"
        } else {
            "Connection lost — reconnecting ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)… [$cause]"
        }
        val delayMs = nextRetryDelayMs
        // AND-V45-002: right after a reconnect begins, the network is usually
        // still coming up (Wi-Fi radio-on to usable is ~6-7s on the 실기기 AP)
        // and a flat 3s cadence overshoots the moment it becomes usable by up
        // to a full period. Retry on a 1s cadence inside the early window, then
        // fall back to the flat cadence.
        // AND-V47-002: a network wake re-arms the fast cadence from the wake
        // moment (elapsed alone had the window burn out DURING long outages).
        // The 90s budget and the attempt cap still bound the loop.
        val wakeRecent = lastNetworkWakeAtMs != 0L &&
            System.currentTimeMillis() - lastNetworkWakeAtMs < FAST_RETRY_WINDOW_MS
        nextRetryDelayMs = if (elapsedMs < FAST_RETRY_WINDOW_MS || wakeRecent) FAST_RECONNECT_DELAY_MS else reconnectDelayMs
        val attemptNumber = reconnectAttempt
        reconnectJob = viewModelScope.launch {
            // delay(0) would NOT suspend: on Main.immediate the body would run
            // INLINE before launch() returns, and a synchronous connect failure
            // could re-enter scheduleReconnectRetry and have its retry Job
            // clobbered by this assignment (orphaned, uncancellable retry).
            // yield() forces a dispatch boundary so reconnectJob is assigned
            // before startPublishing ever runs.
            if (delayMs > 0) {
                // AND-V47-002: the sleep is WAKEABLE — a validated-network
                // event completes the deferred and the attempt fires at once
                // instead of up to a full cadence period later.
                val wake = kotlinx.coroutines.CompletableDeferred<Unit>()
                reconnectWake = wake
                kotlinx.coroutines.withTimeoutOrNull(delayMs) { wake.await() }
                if (reconnectWake === wake) reconnectWake = null
                // Review R2 (v4.8): a wake may only shorten THIS sleep, never
                // the inter-attempt spacing — enforce a FAST_RECONNECT_DELAY_MS
                // floor since the previous attempt's start so the 95-cap /
                // 90s-budget invariant (attempts cost >= 1s each) holds even
                // under sustained network flapping. The residual wait is a
                // plain delay on purpose: it must not be wakeable again.
                val sinceLastAttempt = System.currentTimeMillis() - lastAttemptStartedAtMs
                val floorRemaining = FAST_RECONNECT_DELAY_MS - sinceLastAttempt
                if (lastAttemptStartedAtMs != 0L && floorRemaining > 0) {
                    delay(floorRemaining)
                }
            } else {
                yield()
            }
            // AND-V48-001: do not dial while the OS reports no validated
            // network — a blind attempt is a guaranteed failure that churns a
            // full native session create/destroy (실기기: 아웃리지당 gen +12,
            // 폭풍 진입의 누적 동력). OBS-V49-001: the same hold applies while
            // the app is invisible (system screen on top) — background dials
            // just time out and the budget clock PAUSES there instead of
            // burning (실기기 FOTA: 90s burned with zero usable attempts).
            // Hold until the watch/visibility wakes us or the (paused-aware)
            // budget expires; a genuinely dead network still gives up clean.
            // v4.11 (review R2): background dials are allowed only under the
            // camera/mic keep-alive FGS with NO screen source — that is the
            // one anchor that survives reconnect teardown AND whose republish
            // needs no background FGS start (backgroundDialCapable).
            while (reconnectNetworkIsDown == true || (!appVisible && !backgroundDialCapable())) {
                val heldElapsedMs = reconnectElapsedMs(System.currentTimeMillis())
                if (heldElapsedMs >= RECONNECT_BUDGET_MS) {
                    giveUpReconnect(
                        "Connection lost; no usable network within ${RECONNECT_BUDGET_MS / 1000}s. " +
                            "Press Publish to try again."
                    )
                    return@launch
                }
                Log.i(
                    TAG,
                    "reconnect attempt $attemptNumber/$MAX_RECONNECT_ATTEMPTS holding: " +
                        (if (!appVisible && !backgroundDialCapable()) "app backgrounded" else "network down") +
                        " (elapsed ${heldElapsedMs}ms of ${RECONNECT_BUDGET_MS}ms budget)",
                )
                val holdWake = kotlinx.coroutines.CompletableDeferred<Unit>()
                reconnectWake = holdWake
                kotlinx.coroutines.withTimeoutOrNull(2_000L) { holdWake.await() }
                if (reconnectWake === holdWake) reconnectWake = null
            }
            // v4.9: serialize the previous generation's native close before
            // dialing the next one — native destroy/create overlap across
            // generations is the churn pressure behind storm accumulation.
            // join() is always cancellable, so the outer timeout holds even
            // if the close itself wedges inside the native layer.
            pendingTeardown?.let { kotlinx.coroutines.withTimeoutOrNull(TEARDOWN_JOIN_TIMEOUT_MS) { it.join() } }
            lastAttemptStartedAtMs = System.currentTimeMillis()
            // AND-V47-002 계측: THE SLA anchor line — logged at the actual
            // connection start, never before a sleep (08-06 리포트가 sleep 포함
            // 구간을 SLA로 오측정한 원인 제거). 시나리오 §2-6 경로 A는 이
            // "connecting now" 라인→"publish gen up"을 잰다.
            Log.i(
                TAG,
                "reconnect attempt $attemptNumber/$MAX_RECONNECT_ATTEMPTS connecting now " +
                    "(slept up to ${delayMs}ms, cause: ${cause ?: "unknown"})",
            )
            startPublishing(owner, url)
        }
    }

    private fun giveUpReconnect(message: String) {
        stopReconnectNetworkWatch()
        stabilityResetJob?.cancel()
        stabilityResetJob = null
        // The broadcast is over; audio banners and budget go with it.
        clearAudioRearmState()
        Log.w(TAG, "reconnect gave up (gen $publishGeneration): $message")
        isReconnecting = false
        reconnectRunActive = false
        reconnectAttempt = 0
        reconnectPausedTotalMs = 0L
        stormDetector.reset()
        lastPublishUpAtMs = 0L
        lastError = message
        // Full cleanup returns the UI to idle so the Publish button is enabled again.
        stopBroadcastKeepAlive()
        stopPublishing(keepCameraPreview = true)
    }

    /**
     * Cancels the pending reconnect dial/watch WITHOUT resetting the run
     * bookkeeping (attempts, budget clock, storm streak, lastPublishUpAtMs).
     * 2026-08-14 QA D-01: live republishes route here so the bounds keep
     * accumulating across the republish↔death interleave; the full
     * [cancelReconnect] stays reserved for logical boundaries (user publish,
     * stop, storm abort).
     */
    private fun cancelReconnectScheduling() {
        stopReconnectNetworkWatch()
        stabilityResetJob?.cancel()
        stabilityResetJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        isReconnecting = false
    }

    private fun cancelReconnect() {
        stopReconnectNetworkWatch()
        stabilityResetJob?.cancel()
        stabilityResetJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        // v4.12 (실기기 재테스트, 리뷰 검증 확정): the connectJob comment's
        // contract — EVERY teardown path cancels the in-flight connect — was
        // violated exactly here, letting a retry's connect coroutine survive
        // into the next explicit publish (concurrent generations, orphan
        // sources). The cancelled coroutine's finally releases its bundle;
        // callers that continue into startPublishing bump the generation
        // before that finally can observe a stale ownership.
        connectJob?.cancel()
        connectJob = null
        isReconnecting = false
        reconnectRunActive = false
        reconnectAttempts = 0
        reconnectAttempt = 0
        reconnectStartedAtMs = 0L
        reconnectPausedTotalMs = 0L
        stormDetector.reset()
        lastPublishUpAtMs = 0L
    }

    private fun teardownForReconnect() {
        // 2026-08-16 QA D-05: epoch fence only (wire generation increments at
        // the next publish start).
        publishEpoch++
        connectJob?.cancel()
        connectJob = null
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

        val gen = activeGenResources
        activeGenResources = null

        publisher = null
        session = null
        publishedTracks = emptyList()
        trackStates.clear()
        publisherState = PublisherState.Idle
        sessionState = Session.State.Idle
        isPublishStalled = false
        isNetworkDown = false
        publishStatsText = null

        // v4.12 (DEFECT-01): bundle release — capture sources die first and
        // synchronously; the native close stays bounded so a wedged close
        // never stalls the retry loop past the join timeout in
        // scheduleReconnectRetry (and, unlike the old abandon, the orphan it
        // leaves behind has no live source feeding it).
        releaseGenResources(gen, "teardownForReconnect")?.let { pendingTeardown = it }

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
            existing.start(getApplication(), cameraOwner)
            return existing
        }

        stopMultiCamera()

        val capture = makeMultiCameraCapture(videoConfig)
        capture.setDisplayRotation(currentDisplayRotationDegrees())
        applyMultiCameraPreviewSurfaces(capture)
        multiCamera = capture
        try {
            capture.start(getApplication(), cameraOwner)
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
    private var lastDispatchedRotationDegrees = -1

    fun onDisplayRotationChanged() {
        val degrees = currentDisplayRotationDegrees()
        // Review R1 (v4.12, preventive): onDisplayChanged fires for every
        // display change (refresh rate, HDR), not just rotation — filter to
        // actual rotation changes so display-event bursts can neither churn
        // the renderer matrix nor keep re-arming the trailing debounce.
        if (degrees == lastDispatchedRotationDegrees) return
        lastDispatchedRotationDegrees = degrees
        camera?.setDisplayRotation(degrees)
        multiCamera?.setDisplayRotation(degrees)
        maybeAutoRotateRepublish()
    }

    private var rotationRepublishJob: Job? = null

    /**
     * v4.11 (OBS-V49-003, 요구 확정): republish automatically when the display
     * orientation CATEGORY (portrait↔landscape) no longer matches the live
     * encode. Uses the verified config-change path — same room, sequence
     * carry, player configResync rebuild (실측 재게시 0.3~0.5s + 리빌드) — so
     * the visible gap stays ~1s; a seamless mid-stream resolution swap is not
     * possible without codec renegotiation. 90↔270 flips (same category) stay
     * matrix-only via setDisplayRotation above and never republish.
     */
    private fun maybeAutoRotateRepublish() {
        if (!autoRotateBroadcast) return
        // Review R1: the display listener is process-level — while a
        // keep-alive FGS broadcast runs backgrounded, OTHER apps rotate the
        // display (and screen-lock forces portrait); republishing on those
        // events would churn the live session against the user's intent. The
        // renderer matrix (setDisplayRotation) keeps frames upright either
        // way; the encode-category check re-runs once on foreground return
        // (onAppVisibilityChanged) and on every Publishing edge.
        if (!appVisible) {
            rotationRepublishJob?.cancel()
            rotationRepublishJob = null
            return
        }
        if (publisherState != PublisherState.Publishing) {
            rotationRepublishJob?.cancel()
            rotationRepublishJob = null
            return
        }
        val owner = lastLifecycleOwner ?: return
        val url = lastRelayUrl ?: return
        val publishedPortrait = publishedVideoAspect?.let { it < 1f } ?: return
        if (isDisplayPortrait == publishedPortrait) {
            // Settled back onto the published orientation (rotation cascade
            // bounced) — drop any pending republish.
            rotationRepublishJob?.cancel()
            rotationRepublishJob = null
            return
        }
        // v4.12 (실기기 DEFECT-02): TRAILING debounce — every new rotation
        // event cancels and re-arms the timer, so the republish fires only
        // once the orientation has SETTLED for the debounce window. The old
        // "job active -> ignore" variant timed out from the FIRST event and
        // could republish into an orientation the user was still rotating
        // through. (A round trip slower than the window still republishes per
        // settled leg — that is by design; each republish must be harmless.)
        rotationRepublishJob?.cancel()
        rotationRepublishJob = viewModelScope.launch {
            delay(ROTATION_REPUBLISH_DEBOUNCE_MS)
            rotationRepublishJob = null
            if (!autoRotateBroadcast || !appVisible || publisherState != PublisherState.Publishing) return@launch
            val pubPortrait = publishedVideoAspect?.let { it < 1f } ?: return@launch
            if (isDisplayPortrait == pubPortrait) return@launch
            // Review R2: the swapped W×H may be unsupported even though the
            // current orientation encodes fine (HW AVC encoders commonly cap
            // height at 1080/1088, so landscape 1920x1080 flips to an
            // unsupported portrait 1080x1920). A republish would be rejected
            // by startPublishing's validation ON TOP of the live broadcast —
            // keep the current encode instead and say so.
            publishUnsupportedReason()?.let { reason ->
                Log.w(
                    TAG,
                    "auto-rotate republish skipped: new orientation config unsupported ($reason) — " +
                        "keeping the current encode orientation",
                )
                return@launch
            }
            Log.i(
                TAG,
                "auto-rotate: display orientation flipped (portrait=$isDisplayPortrait) — " +
                    "republishing with the new encode orientation",
            )
            // Same bookkeeping as a manual publish (fresh storm latch/run),
            // but flagged so a connect failure falls back to reconnect
            // instead of ending the broadcast (review R1).
            publishInternal(owner, url, liveRepublish = true)
        }
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
            // v4.12 (실기기 DEFECT-09·첫프레임 3.284s): with a fixed 2s GOP a
            // datagram gap froze the viewer up to 2s waiting for the next IDR
            // (관측 1.3~2.0s) and the cold first frame overshot the 3s gate.
            // 1s keyframes halve both worst cases; the bitrate rises with the
            // larger I-frame share so delta quality does not pay for it.
            keyframeIntervalSeconds = 1,
            bitrate = 2_000_000,
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

    // MARK: - Audio capture death / bounded auto re-arm (2026-08-12)

    /** Applies the debug fault-injection toggle to the LIVE mic immediately. */
    fun updateDebugForceMicFailure(enabled: Boolean) {
        debugForceMicFailure = enabled
        microphone?.debugForceReadFailures = enabled
        // The application point is the first grep-able step of the debug
        // death chain; liveMic tells a tester whether the flag hit a live
        // capture (give-up expected in ~2s) or only arms future mics.
        Log.i(TAG, "debug force-mic-failure ${if (enabled) "ON" else "OFF"} (liveMic=${microphone != null})")
    }

    private fun clearAudioRearmState() {
        audioRearmJob?.cancel()
        audioRearmJob = null
        postRecoveryAudioCheckJob?.cancel()
        postRecoveryAudioCheckJob = null
        audioRecovering = false
        audioDead = false
        audioRearmAttempts = 0
    }

    /**
     * 2026-08-14 QA D-01: yields the PENDING re-arm dial to a reconnect run
     * (which rebuilds the mic itself) while PRESERVING the per-broadcast
     * attempt budget and the dead-latch. The full [clearAudioRearmState] on
     * this path was the P0 runaway's budget-reset vector.
     */
    private fun suspendAudioRearmForReconnect() {
        audioRearmJob?.cancel()
        audioRearmJob = null
        postRecoveryAudioCheckJob?.cancel()
        postRecoveryAudioCheckJob = null
        // Review R1 (v4.16): the reconnect owns recovery from here — a stale
        // "recovering" flag would make the post-recovery readiness check bail
        // (it defers to the re-arm machinery) and strand a mic that never
        // reaches Active behind a button-less banner. The flag is re-asserted
        // by whichever recovery path actually dials next.
        audioRecovering = false
    }

    private var postRecoveryAudioCheckJob: Job? = null

    /**
     * 2026-08-14 QA D-05: one-shot readiness check for a reconnect-recovered
     * generation — video recovering while audio never comes up must trigger a
     * SINGLE bounded recovery, not a silent video-only broadcast (실기기:
     * 영상 936ms 회복·오디오 20초+ 미회복). Routed through the per-broadcast
     * re-arm budget, so it can never loop: at worst it consumes one attempt
     * and falls to the actionable exhausted banner.
     */
    private fun armPostRecoveryAudioCheck(epoch: Int, owner: LifecycleOwner, url: String) {
        postRecoveryAudioCheckJob?.cancel()
        postRecoveryAudioCheckJob = viewModelScope.launch {
            delay(AUDIO_READY_TIMEOUT_MS)
            postRecoveryAudioCheckJob = null
            if (epoch != publishEpoch || isReconnecting) return@launch
            if (!micEnabled || publisherState != PublisherState.Publishing) return@launch
            if (trackStates["mic"] == PublishedTrackState.Active) return@launch
            // The re-arm machinery already owns a pending/dead state — do not
            // double-dial on top of it. audioRecovering is deliberately NOT
            // part of this guard (Review R3): this check's own dial sets it,
            // and a mic wedged at Starting (never Active, never Stopped — the
            // exact D-05 mode this check exists for) never clears it, so
            // guarding on it stranded the 2nd budget attempt and the audioDead
            // latch behind a permanent "recovering" banner. audioRearmJob
            // alone fences the onAudioTrackDied machinery.
            if (audioRearmJob != null || audioDead) return@launch
            // Review R5 (D-02 parity): every terminal audioDead latch below
            // must ALSO release the capture. This check's whole reason to
            // exist is the mic wedged at Starting — a mode that never reaches
            // Stopped, so onAudioTrackDied (whose exhaustion branch carries
            // the D-02 microphone?.stop()) can never run and this function is
            // the LAST handler holding the still-recording AudioRecord. A
            // latch without the stop() held the OS mic session for the whole
            // banner (Voice Recorder blocked; residue rode into the next
            // Wi-Fi cut) — the exact D-02 P1 this delta fixes. Main-confined:
            // no suspension between the Active check above and these latches.
            if (audioRearmAttempts >= MAX_AUDIO_REARM_ATTEMPTS) {
                runCatching { microphone?.stop() }
                audioRecovering = false
                audioDead = true
                Log.w(TAG, "post-recovery audio not ready and budget exhausted — manual retry required")
                return@launch
            }
            // 2026-08-14 QA D-03 parity (Review R3): this republish re-derives
            // the video config exactly like its two siblings (retryAudio, the
            // auto re-arm) — a display rotation landing inside the 3s
            // post-recovery window would otherwise silently re-orient a
            // fixed-orientation broadcast the capability probe cannot protect.
            orientationMismatchReason()?.let { reason ->
                Log.w(TAG, "post-recovery audio recovery blocked: orientation mismatch ($reason) — manual retry offered")
                runCatching { microphone?.stop() }
                audioRecovering = false
                audioDead = true
                return@launch
            }
            publishUnsupportedReason()?.let { reason ->
                Log.w(TAG, "post-recovery audio recovery blocked: config unsupported ($reason) — manual retry offered")
                runCatching { microphone?.stop() }
                audioRecovering = false
                audioDead = true
                return@launch
            }
            audioRearmAttempts++
            audioRecovering = true
            Log.w(
                TAG,
                "post-recovery audio not ready within ${AUDIO_READY_TIMEOUT_MS}ms " +
                    "(mic=${trackStates["mic"]}) — bounded audio recovery " +
                    "$audioRearmAttempts/$MAX_AUDIO_REARM_ATTEMPTS",
            )
            publishInternal(owner, url, liveRepublish = true)
        }
    }

    /**
     * Test seam for [isDisplayPortrait] (2026-08-14 QA D-03): lets the
     * host-JVM suite drive the orientation-mismatch gate without Robolectric.
     */
    internal var displayPortraitProvider: () -> Boolean = { isDisplayPortrait }

    /**
     * 2026-08-14 QA D-03: non-null when the CURRENT display orientation
     * category no longer matches the live broadcast's frozen encode. An audio
     * retry/re-arm republish re-derives currentVideoConfig() from the display,
     * so proceeding under a mismatch silently RE-ORIENTS the video — on
     * devices whose encoder supports the swapped size (실기기 S24: portrait
     * 1080x1920 encodable) the capability probe alone cannot catch it.
     * Decision logic lives in the pure companion [orientationMismatchMessage]
     * so the host-JVM suite pins it without a ViewModel.
     */
    private fun orientationMismatchReason(): String? =
        orientationMismatchMessage(publishedVideoAspect, displayPortraitProvider())

    /**
     * Manual retry from the exhausted-budget banner: an explicit user action,
     * so the auto budget re-arms and one live republish runs immediately.
     */
    fun retryAudio(lifecycleOwner: LifecycleOwner) {
        if (isReconnecting) return
        val url = lastRelayUrl ?: return
        // 2026-08-14 QA D-03: an audio retry must NEVER re-orient the video.
        // Checked FIRST and atomically (Main-confined, no suspension between
        // this read and publishInternal): the early return leaves audioDead
        // and the banner intact, and the generation untouched.
        orientationMismatchReason()?.let { reason ->
            lastError = "Audio retry blocked: $reason"
            Log.w(
                TAG,
                "manual audio retry blocked: orientation mismatch " +
                    "(display portrait=${displayPortraitProvider()}, broadcast aspect=$publishedVideoAspect)",
            )
            return
        }
        // Same unsupported-config guard as the auto re-arm: proceeding would
        // early-return inside startPublishing and convert this actionable
        // exhausted/Retry banner into a permanently stranded button-less
        // "recovering" one. Keep the Retry affordance and say why instead.
        publishUnsupportedReason()?.let { reason ->
            lastError = "Audio retry blocked: $reason"
            Log.w(TAG, "manual audio retry blocked: $reason")
            return
        }
        audioRearmJob?.cancel()
        audioRearmJob = null
        audioRearmAttempts = 0
        audioDead = false
        audioRecovering = true
        Log.i(TAG, "manual audio retry — live republish")
        publishInternal(lifecycleOwner, url, liveRepublish = true)
    }

    /**
     * Death signal for the live broadcast's audio track. Triggered from the
     * mic track's STATE collector (not the TrackError event): a pre-start
     * init failure emits its event before the collectors subscribe (events
     * replay nothing), but the replayed Stopped STATE still lands — one
     * trigger point covers both death paths. Deliberate teardowns never get
     * here because every teardown path cancels the collectors first.
     *
     * Policy (operator, 2026-08-12): up to [MAX_AUDIO_REARM_ATTEMPTS] automatic
     * live republishes per broadcast with [AUDIO_REARM_DELAY_MS] backoff; then
     * a persistent banner with a manual retry. A silent broadcast is
     * effectively dead, and a republish blip costs ~0.7-2.3s (v4.12 실측).
     */
    private fun onAudioTrackDied(reason: String) {
        // Deliberate teardowns cannot get here at all — they cancel the
        // collectors first. The immediate guard is `publisher` presence only:
        // a publish-time init failure fires from the mic track's replayed
        // Stopped state, which lands (a) before this VM's own state collector
        // has processed Publishing (collector launch order is not a
        // contract), and (b) on a reconnect-RECOVERED generation, before line
        // "isReconnecting = false" runs after observePublisher — an
        // isReconnecting guard here would eat that death permanently (the
        // StateFlow never re-emits Stopped) and leave a silent video-only
        // broadcast with no banner. Run interference is owned by the delayed
        // job's strict re-check instead.
        if (publisher == null) return
        if (audioRearmJob != null || audioDead) return
        if (!micEnabled) return
        val owner = lastLifecycleOwner ?: return
        val url = lastRelayUrl ?: return
        if (audioRearmAttempts >= MAX_AUDIO_REARM_ATTEMPTS) {
            audioRecovering = false
            audioDead = true
            // 2026-08-14 QA D-02/D-05: release the dead capture NOW. The
            // exhausted generation used to keep its given-up AudioRecord
            // (still marked recording) alive behind the banner, holding the
            // OS mic session — Voice Recorder could not start and the residue
            // rode into the next Wi-Fi cut. The track is already Stopped, so
            // stopping the capture object changes nothing viewers see.
            runCatching { microphone?.stop() }
            Log.w(
                TAG,
                "audio re-arm budget exhausted ($audioRearmAttempts/$MAX_AUDIO_REARM_ATTEMPTS) — manual retry required ($reason)",
            )
            return
        }
        audioRearmAttempts++
        audioRecovering = true
        Log.i(
            TAG,
            "audio track died ($reason) — auto re-arm $audioRearmAttempts/$MAX_AUDIO_REARM_ATTEMPTS in ${AUDIO_REARM_DELAY_MS}ms",
        )
        audioRearmJob = viewModelScope.launch {
            delay(AUDIO_REARM_DELAY_MS)
            // Background hold — mirror of the sibling republish gates
            // (rotation bails on !appVisible, reconnect holds on
            // !backgroundDialCapable): a background republish cannot rebind
            // the camera or restart a screen FGS, so dialing there burns the
            // bounded budget where recovery is impossible. Hold the attempt
            // until dialing is legal again; the attempt count was already
            // consumed, the banner honestly stays "recovering". No network
            // gate is needed: an outage reaches beginReconnect through its
            // own machinery, which cancels this job.
            while (!appVisible && !backgroundDialCapable()) {
                delay(500L)
                if (isReconnecting || !micEnabled) break
            }
            audioRearmJob = null
            // Re-check the world after the backoff/hold — a user stop, a
            // reconnect run, or a source-toggle change may own the broadcast
            // now.
            if (isReconnecting || publisherState != PublisherState.Publishing || !micEnabled) {
                audioRecovering = false
                return@launch
            }
            // Fire-time GROUND TRUTH, not flags: republish only if the
            // CURRENT generation's mic track is actually dead. audioRecovering
            // alone is untrustworthy in both directions — a rotation republish
            // can revive audio (redundant blip if we fired anyway), or its
            // fresh mic can go Active (clearing the flag) and then die again
            // while THIS pending job dedups the new death at the entry guard;
            // trusting the cleared flag there abandoned a dead mic with no
            // banner and no recovery. All of this runs Main-confined, so the
            // state cannot move between this check and publishInternal.
            if (trackStates["mic"] != PublishedTrackState.Stopped) {
                audioRecovering = false
                return@launch
            }
            // Same guard as the rotation republish (its lines document the
            // real device case: landscape 1920x1080 flips to an unsupported
            // portrait 1080x1920): an unsupported-config republish would
            // early-return inside startPublishing WITHOUT touching the live
            // generation or any audio state, stranding a button-less
            // "recovering" banner forever (the dead mic's Stopped state never
            // re-emits). Fall to the actionable exhausted state instead — the
            // Retry button works once the orientation is supported again.
            // 2026-08-14 QA D-03 parity: the AUTO re-arm must not re-orient
            // the video either — under a mismatch fall to the actionable
            // exhausted state (Retry works once the orientation matches or
            // the user rotates back), mirroring the manual gate.
            orientationMismatchReason()?.let { reason ->
                Log.w(TAG, "audio re-arm blocked: orientation mismatch ($reason) — manual retry offered")
                audioRecovering = false
                audioDead = true
                return@launch
            }
            publishUnsupportedReason()?.let { reason ->
                Log.w(TAG, "audio re-arm blocked: republish config unsupported ($reason) — manual retry offered")
                audioRecovering = false
                audioDead = true
                return@launch
            }
            // Re-assert for banner honesty: a deduped cross-generation death
            // may have left the flag cleared even though the mic is dead.
            audioRecovering = true
            publishInternal(owner, url, liveRepublish = true)
        }
    }

    private fun observePublisher(pub: Publisher, tracks: List<PublishedTrack>) {
        publisherJobs += pub.state.onEach {
            publisherState = it
            // Review R1: onDisplayRotationChanged is edge-triggered and its
            // Publishing guard silently drops rotations that land during the
            // ~1s republish/reconnect gap — with no later event the encode
            // stays on the stale orientation until the NEXT physical
            // rotation. Re-evaluate on every Publishing edge so the category
            // check is level-correct (debounced; no-op when matched).
            if (it == PublisherState.Publishing) maybeAutoRotateRepublish()
        }.launchIn(viewModelScope)

        publisherJobs += pub.events.onEach { event ->
            when (event) {
                is PublisherEvent.TrackStarted -> {
                    trackStates[event.name] = PublishedTrackState.Active
                    // A live mic track is the ground truth: clear BOTH banner
                    // states, not just recovering — a republish that was not
                    // ours (rotation) can revive audio while audioDead is
                    // latched, and a persistent "viewers can't hear you" over
                    // live audio inverts the exact trust the banner exists
                    // for. The attempt budget deliberately stays consumed
                    // (per-broadcast policy).
                    if (event.name == "mic" && (audioRecovering || audioDead)) {
                        if (audioDead) Log.i(TAG, "audio revived by live republish — clearing dead banner")
                        else Log.i(TAG, "audio re-arm succeeded — mic track active")
                        audioRecovering = false
                        audioDead = false
                    }
                }
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
                // Audio-death trigger — see onAudioTrackDied for why the STATE
                // (not the TrackError event) is the single trigger point. The
                // event handler above still owns lastError/banner text.
                if (track.name == "mic" && state == PublishedTrackState.Stopped) {
                    onAudioTrackDied("mic track stopped")
                }
            }.launchIn(viewModelScope)
        }
    }
}
