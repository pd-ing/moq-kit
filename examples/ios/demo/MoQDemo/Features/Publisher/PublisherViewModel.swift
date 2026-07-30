import AVFoundation
import MoQKit
import Network
import SwiftUI
import os

@MainActor
final class PublisherViewModel: ObservableObject {
    // MARK: - Published State

    @Published var sessionState: SessionState = .idle
    @Published var publisherState: PublisherState = .idle
    @Published var isPreviewRunning = false
    @Published var cameraEnabled = true
    @Published var screenEnabled = false
    @Published var micEnabled = true
    @Published var screenAudioEnabled = false
    @Published var replayKitAppGroupIdentifier = "group.com.swmansion.moqdemo"
    @Published var replayKitExtensionBundleIdentifier = "com.swmansion.moqdemo.broadcastupload"
    @Published var replayKitPrepared = false
    @Published var cameraSourceMode: CameraSourceMode = .singleCamera
    @Published var cameraPosition: CameraPosition = .front
    @Published var multiCameraMainPreviewPosition: CameraPosition = .back
    @Published var videoCodec: VideoCodec = PublisherViewModel.defaultVideoCodec()
    @Published var videoResolution: VideoResolution = .hd
    @Published var videoFrameRate: VideoFrameRate = .fps30
    @Published var audioCodec: MoQKit.AudioCodec = PublisherViewModel.defaultAudioCodec()
    @Published var audioSampleRate: AudioSampleRate = .khz48
    @Published var trackStates: [String: PublishedTrackState] = [:]
    @Published var lastError: String?

    // MARK: - Camera Preview

    private var cameraCapture: CameraCapture?

    var previewSession: AVCaptureSession? {
        cameraCapture?.captureSession
    }

    var multiCameraPreviewSession: AVCaptureMultiCamSession? {
        multiCamera?.captureSession
    }

    // MARK: - Capture Sources

    private var camera: CameraCapture?
    private var multiCamera: MultiCameraCapture?
    private var microphone: MicrophoneCapture?

    // MARK: - Computed Properties

    var canPublish: Bool {
        switch sessionState {
        case .idle, .error, .closed:
            break
        default:
            return false
        }
        if case .publishing = publisherState { return false }
        return (cameraEnabled || screenEnabled || micEnabled || screenAudioEnabled)
            && publishUnsupportedReason(videoConfig: currentVideoConfig(), audioConfig: currentAudioConfig()) == nil
    }

    var hasReplayKitTracks: Bool {
        screenEnabled || screenAudioEnabled
    }

    var hasLocalTracks: Bool {
        cameraEnabled || micEnabled
    }

    var canStop: Bool {
        // A publish intent is alive while publishing or while a reconnect loop is
        // retrying in the background — the user must always be able to cancel it.
        if publishIntent != nil { return true }
        if case .publishing = publisherState { return true }
        if sessionState == .connecting || sessionState == .connected { return true }
        if replayKitPrepared { return true }
        return false
    }

    var supportedVideoCodecs: [VideoCodec] {
        VideoEncoderConfig.supportedCodecs()
    }

    var supportedAudioCodecs: [MoQKit.AudioCodec] {
        AudioEncoderConfig.supportedCodecs()
    }

    var stateLabel: String {
        switch sessionState {
        case .idle: return "idle"
        case .connecting: return "connecting..."
        case .connected: return "connected"
        case .error(let error): return "error: \(error.localizedDescription)"
        case .closed: return "closed"
        }
    }

    var stateColor: Color {
        switch sessionState {
        case .idle: return .gray
        case .connecting: return .orange
        case .connected: return .blue
        case .error: return .red
        case .closed: return .gray
        }
    }

    /// True while frames were recently written to the relay. The "publishing"
    /// indicator is driven by this real media flow, not by the mere existence of a
    /// session/publisher object — those can outlive the media path across a handoff.
    private var isFlowingFrames: Bool {
        guard let lastPublishedFrameAt else { return false }
        return Date().timeIntervalSince(lastPublishedFrameAt) < 5
    }

    /// True while *video* frames were recently written to the relay. Tracked
    /// separately from ``isFlowingFrames``: the mic track must not keep the
    /// indicator green while the video path is wedged (build-15 defect — camera
    /// preview and track state looked healthy, relay had stopped receiving video).
    private var isFlowingVideoFrames: Bool {
        guard let lastPublishedVideoFrameAt else { return false }
        return Date().timeIntervalSince(lastPublishedVideoFrameAt) < 5
    }

    var publisherStateLabel: String {
        switch publisherState {
        case .idle: return "idle"
        case .publishing:
            // A freshly (re)built publisher is optimistic (no frame seen yet); a
            // previously-flowing publisher that goes quiet for 5 s is stalled.
            // With a camera track on air, video flow is the authoritative signal.
            if cameraEnabled {
                if lastPublishedVideoFrameAt != nil && !isFlowingVideoFrames {
                    return "publishing (stalled)"
                }
            } else if lastPublishedFrameAt != nil && !isFlowingFrames {
                return "publishing (stalled)"
            }
            return "publishing"
        case .stopped: return "stopped"
        case .error(let msg): return "error: \(msg)"
        }
    }

    var publisherStateColor: Color {
        switch publisherState {
        case .idle: return .gray
        case .publishing:
            if cameraEnabled {
                return lastPublishedVideoFrameAt == nil || isFlowingVideoFrames ? .green : .orange
            }
            return lastPublishedFrameAt == nil || isFlowingFrames ? .green : .orange
        case .stopped: return .orange
        case .error: return .red
        }
    }

    // MARK: - Private State

    private var session: Session?
    private var publisher: Publisher?
    private var stateObserverTask: Task<Void, Never>?
    private var publisherStateTask: Task<Void, Never>?
    private var publisherEventsTask: Task<Void, Never>?
    @Published var publishedTracks: [PublishedTrack] = []

    // MARK: - Reconnect State

    /// Shared logger for reconnect/handoff diagnostics.
    private static let logger = Logger(subsystem: "viewing", category: "PublisherModel")

    /// The broadcast the user wants on air. Set by ``publish(url:path:)``, cleared only by
    /// ``stop()`` — reconnect attempts read it to rebuild the session.
    private var publishIntent: (url: String, path: String)?
    /// Single-flight reconnect loop. Non-nil while a reconnect is scheduled or running.
    private var reconnectTask: Task<Void, Never>?
    /// Watches for Wi-Fi/cellular path changes so a stalled handoff is rebuilt
    /// immediately instead of waiting for the QUIC session to time out.
    private var pathMonitor: NWPathMonitor?
    private let pathMonitorQueue = DispatchQueue(label: "moqdemo.pathmonitor")
    /// Interface signature of the last seen path; nil until the monitor's first update.
    private var lastPathSignature: String?
    /// Last time a reconnect was scheduled, used to throttle path-change flapping.
    private var lastReconnectAt: Date = .distantPast

    /// Monotonic id of the current connection generation. Bumped on every rebuild
    /// (initial publish, every reconnect attempt) and on ``stop()``. Callbacks from a
    /// previous generation's session/publisher/observer tasks check it and are dropped
    /// instead of overwriting the new generation's state — a stale `.error` or
    /// `.stopped` event arriving after a rebuild must never touch fresh state.
    private var connectionGeneration: UInt64 = 0
    /// The initial-publish connect Task of `publish(url:path:)`. Tracked so
    /// `stop()` (and a superseding `publish`) can cancel it — an untracked
    /// attempt could otherwise fail after a same-relay re-publish and clobber
    /// the newer generation's state.
    private var initialPublishTask: Task<Void, Never>?
    /// Per-track state observer tasks of the current generation; cancelled on
    /// rebuild/stop so they cannot linger on a dead publisher's streams.
    private var trackStateTasks: [Task<Void, Never>] = []

    // MARK: - Camera Stall Watchdog State

    /// One-shot watchdog ensuring the camera track leaves `starting` after a rebuild.
    private var cameraWatchdogTask: Task<Void, Never>?
    /// Consecutive watchdog-triggered camera rebuilds without the camera reaching
    /// `.active`; bounds automatic recovery so a genuinely wedged camera does not
    /// rebuild forever. Reset when a camera track starts, and on publish/stop.
    private var cameraWatchdogRecoveries = 0

    // MARK: - Emission Watchdog State

    /// Periodic watchdog ensuring video frames keep reaching the relay while a
    /// broadcast is on air. The camera watchdog only proves a track left
    /// `starting` (first keyframe encoded); it says nothing about sustained
    /// network emission — on build 15 the camera preview and track state looked
    /// healthy while the relay had stopped receiving video (DEFECT-IOS15-P0-03).
    private var emissionWatchdogTask: Task<Void, Never>?
    /// Consecutive emission-stall reconnects without video flow resuming; bounds
    /// automatic recovery like ``cameraWatchdogRecoveries``. Reset when a video
    /// frame is written to the relay, and on publish/stop.
    private var emissionWatchdogRecoveries = 0
    /// When the current generation's first video track reported `.active`. Lets
    /// the emission watchdog flag "track active but zero frames ever written"
    /// without racing the first keyframe/relay write.
    private var videoTrackActiveAt: Date?
    /// Consecutive audio emission-stall reconnects without audio flow resuming.
    /// Independent of the video counter — the 40-min soak showed audio can
    /// flatline while video keeps flowing (mic "active", 38 s of zero audio
    /// datagrams).
    private var emissionWatchdogAudioRecoveries = 0
    /// When the current generation's first audio track reported `.active`.
    private var audioTrackActiveAt: Date?
    /// Audio tracks whose relay writes have been quiet past the warn threshold.
    /// Drives the per-track "(stalled)" indicator so the mic row cannot read a
    /// bare "active" (capture liveness) while its datagrams are flatlining.
    @Published private(set) var stalledAudioTracks: Set<String> = []

    // MARK: - Publish Flow State

    /// Last time any track successfully wrote a frame to the relay. Drives the UI's
    /// publishing indicator from real media flow rather than session/publisher
    /// object existence (which can outlive the media path across a handoff).
    @Published private(set) var lastPublishedFrameAt: Date?
    /// Throttles per-frame publish notifications from encoder threads to ~1/s.
    private let framePublishedGate = FramePublishedGate()
    /// Last time a *video* track successfully wrote a frame to the relay. Kept
    /// separate from ``lastPublishedFrameAt`` so an audio track cannot mask a
    /// wedged video path; drives the stalled indicator and the emission watchdog.
    @Published private(set) var lastPublishedVideoFrameAt: Date?
    /// Separate ~1/s gate for video-track notifications — sharing one gate with a
    /// chatty mic track could starve video freshness updates for seconds at a time.
    private let videoFramePublishedGate = FramePublishedGate()
    /// Last time an *audio* track successfully wrote a frame to the relay. Kept
    /// separate from the video timestamp for the symmetric reason: a healthy
    /// video path must not mask flatlining audio datagrams.
    @Published private(set) var lastPublishedAudioFrameAt: Date?
    /// Separate ~1/s gate for audio-track notifications.
    private let audioFramePublishedGate = FramePublishedGate()

    // MARK: - Camera Preview Lifecycle

    func startPreview() {
        guard cameraEnabled else { return }

        switch cameraSourceMode {
        case .singleCamera:
            startSingleCameraPreview()
        case .multiCamera:
            startMultiCameraPreview()
        }
    }

    private func startSingleCameraPreview() {
        stopMultiCameraPreview()
        guard cameraCapture == nil else {
            isPreviewRunning = true
            return
        }

        let cam = CameraCapture(camera: Camera(position: cameraPosition))
        cameraCapture = cam
        isPreviewRunning = true

        Task {
            do {
                try await cam.start()
            } catch {
                lastError = "Camera preview failed: \(error.localizedDescription)"
                cameraCapture = nil
                isPreviewRunning = false
            }
        }
    }

    private func startMultiCameraPreview(videoConfig: VideoEncoderConfig? = nil) {
        guard MultiCameraCapture.isSupported else {
            lastError = "Multi-camera capture is not supported on this device"
            cameraSourceMode = .singleCamera
            startSingleCameraPreview()
            return
        }

        stopSingleCameraPreview()

        let videoConfig = videoConfig ?? currentVideoConfig()
        if let existing = multiCamera {
            guard !isMultiCamera(existing, configuredFor: videoConfig) else {
                isPreviewRunning = true
                return
            }
            existing.stop()
            multiCamera = nil
        }

        let multi = makeMultiCameraCapture(videoConfig: videoConfig)
        multiCamera = multi
        isPreviewRunning = false

        Task {
            do {
                try await multi.start()
                if self.multiCamera === multi {
                    self.isPreviewRunning = true
                    self.lastError = nil
                }
            } catch {
                if self.multiCamera === multi {
                    self.lastError = "Multi-camera preview failed: \(error.localizedDescription)"
                    self.multiCamera = nil
                    self.isPreviewRunning = false
                }
            }
        }
    }

    func stopPreview() {
        stopSingleCameraPreview()
        stopMultiCameraPreview()
        isPreviewRunning = false
    }

    private func stopSingleCameraPreview() {
        cameraCapture?.stop()
        cameraCapture = nil
    }

    private func stopMultiCameraPreview() {
        multiCamera?.stop()
        multiCamera = nil
    }

    func flipCamera() {
        guard cameraSourceMode == .singleCamera else { return }
        let newPosition: CameraPosition = cameraPosition == .front ? .back : .front
        cameraPosition = newPosition

        if let cameraCapture {
            do {
                try cameraCapture.switch(to: Camera(position: newPosition))
            } catch {
                lastError = "Camera switch failed: \(error.localizedDescription)"
            }
        }
    }

    func swapMultiCameraPreview() {
        multiCameraMainPreviewPosition = multiCameraMainPreviewPosition == .front ? .back : .front
    }

    func handleCameraEnabledChanged() {
        if cameraEnabled {
            handleCameraSourceChanged()
        } else {
            stopPreview()
        }
    }

    func handleCameraSourceChanged() {
        guard cameraEnabled else { return }

        switch cameraSourceMode {
        case .singleCamera:
            startSingleCameraPreview()
        case .multiCamera:
            if !MultiCameraCapture.isSupported {
                cameraSourceMode = .singleCamera
                lastError = "Multi-camera capture is not supported on this device"
                startSingleCameraPreview()
                return
            }
            startMultiCameraPreview()
        }
    }

    // MARK: - Publish Lifecycle

    static func configurePlaybackAudioSession() {
        let audioSession = AVAudioSession.sharedInstance()
        try? audioSession.setCategory(.playback, mode: .moviePlayback, options: [])
        try? audioSession.setActive(true)
    }

    private func configurePublishingAudioSession() {
        let audioSession = AVAudioSession.sharedInstance()
        try? audioSession.setCategory(
            .playAndRecord,
            mode: .videoRecording,
            options: [.defaultToSpeaker, .allowBluetoothHFP]
        )
        try? audioSession.setActive(true)
    }

    func prepareReplayKitDescriptor(url: String, path: String) {
        do {
            guard !replayKitAppGroupIdentifier.isEmpty else {
                throw ReplayKitBroadcastError.invalidAppGroup("App Group is empty")
            }
            let descriptor = ReplayKitBroadcastDescriptor(
                relayURL: url,
                broadcastPath: path + "/screenshare"
            )
            let store = ReplayKitBroadcastDescriptorStore(
                appGroupIdentifier: replayKitAppGroupIdentifier
            )
            try store.save(descriptor)
            lastError = nil
            replayKitPrepared = true
        } catch {
            print(error)
            lastError = "ReplayKit config failed: \(error.localizedDescription)"
            replayKitPrepared = false
        }
    }

    func publish(url: String, path: String) {
        configurePublishingAudioSession()
        if micEnabled { startAudioSessionDiagnostics() }

        lastError = nil
        publishedTracks = []
        trackStates = [:]
        lastPublishedFrameAt = nil
        lastPublishedVideoFrameAt = nil
        lastPublishedAudioFrameAt = nil
        videoTrackActiveAt = nil
        audioTrackActiveAt = nil
        stalledAudioTracks = []
        cameraWatchdogRecoveries = 0
        emissionWatchdogRecoveries = 0
        emissionWatchdogAudioRecoveries = 0

        let videoEncoderConfig = currentVideoConfig()
        let audioEncoderConfig = currentAudioConfig()
        if let unsupportedReason = publishUnsupportedReason(
            videoConfig: videoEncoderConfig,
            audioConfig: audioEncoderConfig
        ) {
            lastError = unsupportedReason
            publisherState = .error(unsupportedReason)
            return
        }

        if hasReplayKitTracks {
            prepareReplayKitDescriptor(url: url, path: path)
            if lastError != nil { return }
        }

        guard hasLocalTracks else {
            publisherState = .publishing
            return
        }

        reconnectTask?.cancel()
        reconnectTask = nil
        publishIntent = (url, path)
        startPathMonitor()

        initialPublishTask?.cancel()
        initialPublishTask = Task {
            // connectAndPublish claims the next generation as its very first
            // statement, and there is no suspension between reading it here and
            // that bump (same main actor, direct call), so this is exactly this
            // attempt's generation.
            let attemptGeneration = self.connectionGeneration &+ 1
            do {
                try await self.connectAndPublish(url: url, path: path)
            } catch is CancellationError {
                // Stopped (or superseded) while connecting — stop() already reset the UI.
            } catch {
                // Initial publish failures surface immediately; automatic recovery
                // only applies to a session that was already established. If a newer
                // publish or reconnect superseded this attempt — bumping the
                // generation past ours, INCLUDING a re-publish to the same
                // url/path, which an intent equality check cannot distinguish —
                // leave the newer generation's state alone.
                guard self.connectionGeneration == attemptGeneration else {
                    return
                }
                self.lastError = error.localizedDescription
                self.publisherState = .error(error.localizedDescription)
                self.publishIntent = nil
                self.stopPathMonitor()
                self.cleanupCaptureSources()
            }
        }
    }

    /// Creates a fresh session and publisher and starts publishing all enabled tracks.
    ///
    /// Capture sources that are already running (camera preview, microphone) are reused
    /// and re-bound to the new publisher — only the network-facing objects are rebuilt.
    /// Used both for the initial publish and for every reconnect attempt.
    private func connectAndPublish(url: String, path: String) async throws {
        // Bump the generation first: every callback wired up below carries this id
        // and drops itself once a newer rebuild (or stop()) supersedes it.
        connectionGeneration &+= 1
        let generation = connectionGeneration

        let videoEncoderConfig = currentVideoConfig()
        let audioEncoderConfig = currentAudioConfig()

        // Tear down the previous attempt's network-facing objects before rebuilding.
        // Reconnects replace the session and publisher; leaving the old publisher
        // alive leaks its encoders (codec resources are finite — a few handoffs
        // exhaust them and new tracks then hang in `starting`, seen on-device as
        // the mic track never leaving `starting`) and strands observer tasks on
        // streams that never finish. stop() also rewrites each source's onFrame,
        // which is safe here because the new publisher rebinds them below.
        let oldStateObserverTask = stateObserverTask
        stateObserverTask = nil
        oldStateObserverTask?.cancel()

        let oldPublisherStateTask = publisherStateTask
        publisherStateTask = nil
        oldPublisherStateTask?.cancel()

        let oldPublisherEventsTask = publisherEventsTask
        publisherEventsTask = nil
        oldPublisherEventsTask?.cancel()

        let oldTrackStateTasks = trackStateTasks
        trackStateTasks = []
        for task in oldTrackStateTasks { task.cancel() }

        cameraWatchdogTask?.cancel()
        cameraWatchdogTask = nil
        emissionWatchdogTask?.cancel()
        emissionWatchdogTask = nil

        let oldPublisher = publisher
        publisher = nil
        let oldSession = session
        session = nil
        lastPublishedFrameAt = nil
        lastPublishedVideoFrameAt = nil
        lastPublishedAudioFrameAt = nil
        videoTrackActiveAt = nil
        audioTrackActiveAt = nil
        stalledAudioTracks = []
        framePublishedGate.reset()
        videoFramePublishedGate.reset()
        audioFramePublishedGate.reset()
        oldPublisher?.stop()
        if let oldSession {
            await oldSession.close()
            Self.logger.info("generation \(generation): closed old session \(String(describing: ObjectIdentifier(oldSession)))")
        }

        let s = Session(url: url)
        session = s
        Self.logger.info("generation \(generation): new session \(String(describing: ObjectIdentifier(s))) url=\(url) path=\(path)")

        stateObserverTask = Task { [weak self] in
            guard let self else { return }
            for await state in s.state {
                // Ignore callbacks from a superseded generation: the old session may
                // still deliver a final .error/.closed after the rebuild started.
                guard self.connectionGeneration == generation else { return }
                self.sessionState = state
                if case .error = state {
                    self.handleSessionFailure(generation: generation)
                }
            }
        }

        var createdPub: Publisher?
        do {
            try await s.connect()

            // The URL proved reachable — persist it as the pre-filled default the
            // next time a demo screen opens (no hardcoded LAN address in QA builds).
            MoQDemoRelayURLs.saveLastConnectedSharedRelayURL(url)

            // Bail out if the user stopped (or restarted) publishing while connecting.
            try Task.checkCancellation()
            guard self.publishIntent?.url == url, self.publishIntent?.path == path else {
                throw CancellationError()
            }

            let pub = try Publisher()
            createdPub = pub
            self.publisher = pub

            self.publishedTracks = []
            self.trackStates = [:]

            if self.cameraEnabled {
                switch self.cameraSourceMode {
                case .singleCamera:
                    // Reuse the preview CameraCapture, or create one if preview wasn't started
                    let cam: CameraCapture
                    if let existing = self.cameraCapture {
                        cam = existing
                        // Mirror the mic fix: a reused capture may have stopped
                        // delivering (interruption, consumer detach) while the object
                        // was kept for the preview. start() is idempotent and trusts
                        // AVCaptureSession.isRunning rather than a cached flag.
                        try await cam.start()
                    } else {
                        cam = CameraCapture(camera: Camera(position: self.cameraPosition))
                        self.cameraCapture = cam
                        try await cam.start()
                    }
                    self.camera = cam
                    Self.logger.info(
                        "generation \(generation): camera capture running=\(cam.captureSession.isRunning)")

                    let track = pub.addVideoTrack(name: "camera", source: cam, config: videoEncoderConfig)
                    self.publishedTracks.append(track)
                    self.trackStates["camera"] = .idle

                case .multiCamera:
                    let multi = try await self.runningMultiCameraCapture(
                        videoConfig: videoEncoderConfig
                    )

                    let frontTrack = pub.addVideoTrack(
                        name: "front-camera",
                        source: multi.frontSource,
                        config: videoEncoderConfig
                    )
                    self.publishedTracks.append(frontTrack)
                    self.trackStates["front-camera"] = .idle

                    let backTrack = pub.addVideoTrack(
                        name: "back-camera",
                        source: multi.backSource,
                        config: videoEncoderConfig
                    )
                    self.publishedTracks.append(backTrack)
                    self.trackStates["back-camera"] = .idle
                }
            }

            if self.micEnabled {
                let mic: MicrophoneCapture
                if let existing = self.microphone {
                    mic = existing
                } else {
                    mic = MicrophoneCapture()
                    self.microphone = mic
                }
                // Reused or not, make sure capture is really running: a consumer
                // detach or an interruption (lock screen, call) may have stopped
                // delivery while the source object was kept for reuse.
                try await mic.start()

                let track = pub.addAudioTrack(name: "mic", source: mic, config: audioEncoderConfig)
                self.publishedTracks.append(track)
                self.trackStates["mic"] = .idle
            }

            // Liveness hook: fires on encoder threads for every frame written to
            // the relay. Gate to ~1/s and guard by generation before touching
            // main-actor state, so a superseded publisher cannot fake freshness.
            // Track names are fixed for this generation's publisher, so capture
            // them here — the hook runs off the main actor.
            let gate = self.framePublishedGate
            let videoGate = self.videoFramePublishedGate
            let audioGate = self.audioFramePublishedGate
            let videoTrackNames = Set(self.cameraTrackNames)
            let audioTrackNames = Set(self.audioTrackNames)
            pub.onFramePublished = { [weak self] trackName in
                let isVideo = videoTrackNames.contains(trackName)
                let isAudio = audioTrackNames.contains(trackName)
                // Video and audio each get their own gate so one track cannot
                // starve the other's freshness updates behind a shared ~1/s gate.
                let shouldNotify = isVideo ? videoGate.shouldNotify()
                    : isAudio ? audioGate.shouldNotify()
                    : gate.shouldNotify()
                guard shouldNotify else { return }
                Task { @MainActor in
                    guard let self, self.connectionGeneration == generation else { return }
                    let now = Date()
                    self.lastPublishedFrameAt = now
                    if isVideo {
                        if self.lastPublishedVideoFrameAt == nil {
                            Self.logger.info(
                                "generation \(generation): first video frame written to relay (track '\(trackName)')")
                        }
                        if self.emissionWatchdogRecoveries > 0 {
                            Self.logger.info(
                                "emission watchdog: video flow restored after \(self.emissionWatchdogRecoveries) recovery attempt(s)")
                        }
                        self.lastPublishedVideoFrameAt = now
                        self.emissionWatchdogRecoveries = 0
                    }
                    if isAudio {
                        if self.lastPublishedAudioFrameAt == nil {
                            Self.logger.info(
                                "generation \(generation): first audio frame written to relay (track '\(trackName)')")
                        }
                        if self.emissionWatchdogAudioRecoveries > 0 {
                            Self.logger.info(
                                "emission watchdog: audio flow restored after \(self.emissionWatchdogAudioRecoveries) recovery attempt(s)")
                        }
                        self.lastPublishedAudioFrameAt = now
                        self.emissionWatchdogAudioRecoveries = 0
                        if !self.stalledAudioTracks.isEmpty {
                            self.stalledAudioTracks = []
                        }
                    }
                }
            }

            try await s.publish(path: path, publisher: pub)
            try await pub.start()

            self.observePublisher(pub, generation: generation)
            self.armCameraWatchdog(generation: generation)
            self.armEmissionWatchdog(generation: generation)
        } catch {
            // Drop this attempt's network objects, but keep capture sources running —
            // a reconnect attempt (or the preview) reuses them. Guard by identity so a
            // newer attempt's objects are never torn down by this stale failure.
            // Closing the session finishes its state stream, which lets the observer
            // task complete instead of lingering on a dead session.
            if let createdPub, self.publisher === createdPub { self.publisher = nil }
            if self.session === s { self.session = nil }
            await s.close()
            throw error
        }
    }

    // MARK: - Reconnect

    /// Maximum number of reconnect attempts before giving up.
    private static let maxReconnectAttempts = 8
    /// Backoff between attempts; the last value repeats. First attempt fires ~0.5 s
    /// after the drop so a Wi-Fi/cellular handoff recovers inside the 5 s gate budget.
    private static let reconnectDelaysNs: [UInt64] = [
        500_000_000, 1_000_000_000, 2_000_000_000, 4_000_000_000,
    ]

    /// Called when the session reports an irrecoverable error while a broadcast is
    /// supposed to be on air (e.g. the QUIC session dies on a network transition).
    ///
    /// `generation` must be the id of the session that failed; a stale session's
    /// final `.error` arriving after a rebuild is dropped here.
    private func handleSessionFailure(generation: UInt64) {
        guard connectionGeneration == generation, publishIntent != nil else { return }
        Self.logger.warning("generation \(generation): session failed while on air — scheduling reconnect")
        scheduleReconnect()
    }

    /// Called when the set of available network interfaces changes (Wi-Fi on/off).
    ///
    /// The relay does not reliably follow QUIC connection migration onto a new path
    /// (observed: cellular→Wi-Fi kills the broadcast), so the session is rebuilt on
    /// the new path instead of waiting for the old one to time out.
    private func handleNetworkPathChange() {
        guard publishIntent != nil, sessionState == .connected else { return }
        // Throttle flapping interfaces: at most one rebuild every 5 s.
        guard Date().timeIntervalSince(lastReconnectAt) > 5 else { return }
        scheduleReconnect()
    }

    /// Starts the single-flight reconnect loop if one is not already running.
    private func scheduleReconnect() {
        guard reconnectTask == nil, publishIntent != nil else { return }
        lastReconnectAt = Date()
        publisherState = .idle

        reconnectTask = Task { [weak self] in
            guard let self else { return }
            defer { self.reconnectTask = nil }

            var attempt = 0
            while !Task.isCancelled, let intent = self.publishIntent {
                attempt += 1
                if attempt > Self.maxReconnectAttempts {
                    self.lastError = "reconnect failed after \(Self.maxReconnectAttempts) attempts"
                    self.publisherState = .error(self.lastError!)
                    self.publishIntent = nil
                    self.stopPathMonitor()
                    self.cleanupCaptureSources()
                    return
                }

                let delayIndex = min(attempt - 1, Self.reconnectDelaysNs.count - 1)
                try? await Task.sleep(nanoseconds: Self.reconnectDelaysNs[delayIndex])
                if Task.isCancelled { return }
                guard self.publishIntent != nil else { return }

                do {
                    try await self.connectAndPublish(url: intent.url, path: intent.path)
                    Self.logger.info("reconnect succeeded on attempt \(attempt)")
                    return
                } catch is CancellationError {
                    return
                } catch {
                    Self.logger.warning("reconnect attempt \(attempt) failed: \(error.localizedDescription)")
                    self.lastError = "reconnect attempt \(attempt) failed: \(error.localizedDescription)"
                }
            }
        }
    }

    // MARK: - Camera Stall Watchdog

    /// Camera track names for the current source mode.
    private var cameraTrackNames: [String] {
        switch cameraSourceMode {
        case .singleCamera: return ["camera"]
        case .multiCamera: return ["front-camera", "back-camera"]
        }
    }

    /// Audio track names for the current source configuration. Only the local
    /// mic publishes through this session — screen audio goes through the
    /// ReplayKit extension process.
    private var audioTrackNames: [String] {
        micEnabled ? ["mic"] : []
    }

    /// Maximum consecutive watchdog-triggered camera rebuilds before giving up, so a
    /// genuinely unavailable camera does not cause an endless rebuild loop.
    private static let maxCameraWatchdogRecoveries = 3

    /// Arms a one-shot watchdog after every successful (re)connect: if a camera track
    /// has not reached `.active` within 5 s of being re-created, the capture/encoder
    /// stack is assumed wedged — observed on-device (~1/20) as the camera track stuck
    /// in `starting` after a cellular→Wi-Fi handoff while the mic recovered. The
    /// watchdog only watches camera track state, independent of the mic path.
    private func armCameraWatchdog(generation: UInt64) {
        cameraWatchdogTask?.cancel()
        cameraWatchdogTask = nil
        guard cameraEnabled, hasLocalTracks, publishIntent != nil else { return }

        let trackedNames = cameraTrackNames
        cameraWatchdogTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled, let self else { return }
            // A newer rebuild (or stop) supersedes this watchdog; a reconnect loop
            // already in flight re-arms its own watchdog when it succeeds.
            guard self.connectionGeneration == generation,
                  self.publishIntent != nil,
                  self.reconnectTask == nil else { return }
            let stuck = trackedNames.filter { self.trackStates[$0] != .active }
            guard !stuck.isEmpty else { return }
            Self.logger.error(
                "camera watchdog: track(s) \(stuck.joined(separator: ",")) not active 5 s after generation \(generation) — rebuilding camera capture")
            self.recoverStuckCamera(generation: generation)
        }
    }

    /// Rebuilds the publish path with a freshly created camera capture, dropping the
    /// possibly wedged AVCaptureSession/encoder pair instead of re-binding it. The
    /// mic capture is left running and is only re-bound by the reconnect, so audio
    /// keeps flowing; the rebuild runs through the single-flight reconnect loop.
    private func recoverStuckCamera(generation: UInt64) {
        guard connectionGeneration == generation, publishIntent != nil, reconnectTask == nil else {
            return
        }
        guard cameraWatchdogRecoveries < Self.maxCameraWatchdogRecoveries else {
            Self.logger.error(
                "camera watchdog: giving up after \(Self.maxCameraWatchdogRecoveries) recoveries")
            lastError = "Camera track stalled after the network handoff and automatic recovery gave up"
            return
        }
        cameraWatchdogRecoveries += 1

        switch cameraSourceMode {
        case .singleCamera:
            cameraCapture?.stop()
            cameraCapture = nil
            camera = nil
        case .multiCamera:
            multiCamera?.stop()
            multiCamera = nil
            camera = nil
            isPreviewRunning = false
        }
        scheduleReconnect()
    }

    // MARK: - Emission Watchdog

    /// No video frame written to the relay for this long while a video track is
    /// on air means the publish path is wedged — even when capture, preview, and
    /// track state all look healthy (build-15 defect: camera active locally,
    /// `receivedVideo` stopped at the relay).
    private static let emissionStallThresholdSeconds: TimeInterval = 5
    /// How often the emission watchdog wakes to check video freshness.
    private static let emissionWatchdogIntervalNs: UInt64 = 2_000_000_000
    /// Maximum consecutive emission-stall reconnects before giving up, mirroring
    /// ``maxCameraWatchdogRecoveries``.
    private static let maxEmissionWatchdogRecoveries = 3
    /// No audio frame written to the relay for this long ⇒ warn: mark the mic
    /// track stalled in the UI and log, but keep watching. Below the video
    /// threshold — audio frames are tiny and continuous, so even a short hole
    /// is suspicious.
    private static let audioStallWarnThresholdSeconds: TimeInterval = 4
    /// No audio frame written to the relay for this long ⇒ rebuild the session.
    private static let audioStallThresholdSeconds: TimeInterval = 8
    /// Maximum consecutive audio-stall reconnects before giving up. Independent
    /// of the video limit — the two paths fail independently.
    private static let maxEmissionWatchdogAudioRecoveries = 3

    /// Arms the periodic emission watchdog after every successful (re)connect,
    /// alongside the one-shot camera watchdog. It fires when a video track is
    /// `.active` yet no video frame has been written to the relay for
    /// ``emissionStallThresholdSeconds`` — either because emission stopped
    /// mid-broadcast or because the track announced `.active` but never wrote a
    /// single frame. Audio tracks get an independent check with their own
    /// thresholds (warn at ``audioStallWarnThresholdSeconds``, recover at
    /// ``audioStallThresholdSeconds``) because audio can flatline while video
    /// keeps flowing. Recovery runs through the same single-flight reconnect
    /// loop as a dropped session.
    private func armEmissionWatchdog(generation: UInt64) {
        emissionWatchdogTask?.cancel()
        emissionWatchdogTask = nil
        guard hasLocalTracks, publishIntent != nil else { return }

        let trackedNames = cameraTrackNames
        let trackedAudioNames = audioTrackNames
        emissionWatchdogTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.emissionWatchdogIntervalNs)
                guard !Task.isCancelled, let self else { return }
                // A newer rebuild (or stop) supersedes this watchdog; a reconnect
                // loop already in flight re-arms its own watchdog when it succeeds.
                guard self.connectionGeneration == generation,
                      self.publishIntent != nil,
                      self.reconnectTask == nil else { return }
                let now = Date()

                // --- Video: tracks still in `starting` are the camera watchdog's
                // job, so only watch tracks that reached `.active`.
                if trackedNames.contains(where: { self.trackStates[$0] == .active }) {
                    let stalled: Bool
                    if let lastVideoFrameAt = self.lastPublishedVideoFrameAt {
                        // Emission flowed before, then went quiet.
                        stalled = now.timeIntervalSince(lastVideoFrameAt)
                            > Self.emissionStallThresholdSeconds
                    } else if let activeAt = self.videoTrackActiveAt {
                        // `.active` means the media producer exists; the first
                        // keyframe/write gets the same grace period before flagging.
                        stalled = now.timeIntervalSince(activeAt)
                            > Self.emissionStallThresholdSeconds
                    } else {
                        stalled = false
                    }
                    if stalled {
                        Self.logger.error(
                            "emission watchdog: no video frame written to relay for >\(Self.emissionStallThresholdSeconds)s (generation \(generation), last video frame at \(String(describing: self.lastPublishedVideoFrameAt)), last any-track frame at \(String(describing: self.lastPublishedFrameAt))) — recovering")
                        self.recoverStalledEmission(generation: generation)
                        return
                    }
                }

                // --- Audio: independent thresholds and recovery counter. The mic
                // can flatline while video keeps flowing (40-min soak: mic row
                // "active", zero audio datagrams for 38 s).
                guard !trackedAudioNames.isEmpty,
                      trackedAudioNames.contains(where: { self.trackStates[$0] == .active }) else {
                    continue
                }
                let audioSilence: TimeInterval?
                if let lastAudioFrameAt = self.lastPublishedAudioFrameAt {
                    audioSilence = now.timeIntervalSince(lastAudioFrameAt)
                } else if let activeAt = self.audioTrackActiveAt {
                    // Same "active but never wrote" grace as the video path.
                    audioSilence = now.timeIntervalSince(activeAt)
                } else {
                    audioSilence = nil
                }
                guard let silence = audioSilence else { continue }

                if silence > Self.audioStallThresholdSeconds {
                    Self.logger.error(
                        "emission watchdog: no audio frame written to relay for >\(Self.audioStallThresholdSeconds)s (generation \(generation), last audio frame at \(String(describing: self.lastPublishedAudioFrameAt)), last video frame at \(String(describing: self.lastPublishedVideoFrameAt))) — recovering")
                    self.recoverStalledAudioEmission(generation: generation)
                    return
                }
                if silence > Self.audioStallWarnThresholdSeconds, self.stalledAudioTracks.isEmpty {
                    self.stalledAudioTracks = Set(trackedAudioNames)
                    Self.logger.warning(
                        "emission watchdog: no audio frame written to relay for >\(Self.audioStallWarnThresholdSeconds)s (generation \(generation)) — mic marked stalled, watching for recovery")
                }
            }
        }
    }

    /// Recovers a wedged publish path by rebuilding the session (and with it the
    /// encoders and relay-facing producers) through the single-flight reconnect
    /// loop. Unlike ``recoverStuckCamera`` the capture stack is left alone: in
    /// this failure mode the camera preview is healthy and only network emission
    /// stopped, so re-binding the running sources is enough.
    private func recoverStalledEmission(generation: UInt64) {
        guard connectionGeneration == generation, publishIntent != nil, reconnectTask == nil else {
            return
        }
        guard emissionWatchdogRecoveries < Self.maxEmissionWatchdogRecoveries else {
            Self.logger.error(
                "emission watchdog: giving up after \(Self.maxEmissionWatchdogRecoveries) recoveries")
            lastError = "Video frames stopped reaching the relay and automatic recovery gave up"
            return
        }
        emissionWatchdogRecoveries += 1
        Self.logger.error(
            "emission watchdog: reconnecting session (recovery \(self.emissionWatchdogRecoveries)/\(Self.maxEmissionWatchdogRecoveries))")
        scheduleReconnect()
    }

    /// Audio counterpart of ``recoverStalledEmission``: same single-flight session
    /// rebuild, independent counter/limit. The reconnect re-runs `mic.start()`,
    /// which reactivates capture after an AVAudioSession interruption — the prime
    /// suspect when audio flatlines while capture and UI look healthy.
    private func recoverStalledAudioEmission(generation: UInt64) {
        guard connectionGeneration == generation, publishIntent != nil, reconnectTask == nil else {
            return
        }
        guard emissionWatchdogAudioRecoveries < Self.maxEmissionWatchdogAudioRecoveries else {
            Self.logger.error(
                "emission watchdog: giving up on audio after \(Self.maxEmissionWatchdogAudioRecoveries) recoveries")
            lastError = "Audio frames stopped reaching the relay and automatic recovery gave up"
            return
        }
        emissionWatchdogAudioRecoveries += 1
        Self.logger.error(
            "emission watchdog: reconnecting session for audio stall (recovery \(self.emissionWatchdogAudioRecoveries)/\(Self.maxEmissionWatchdogAudioRecoveries))")
        scheduleReconnect()
    }

    // MARK: - Audio Session Diagnostics

    /// Notification tokens for AVAudioSession interruption/route-change logging.
    private var audioSessionObservers: [NSObjectProtocol] = []

    /// Logs AVAudioSession interruptions and route changes while publishing with
    /// the mic. Both are prime suspects when audio relay writes flatline while
    /// capture and UI still look healthy (40-min soak incident), and neither is
    /// otherwise visible in the demo's logs.
    private func startAudioSessionDiagnostics() {
        guard audioSessionObservers.isEmpty else { return }
        let center = NotificationCenter.default
        // The closures only log (Logger is thread-safe), so they can run on
        // whatever thread posts the notification — no main-actor hop needed.
        audioSessionObservers.append(
            center.addObserver(
                forName: AVAudioSession.interruptionNotification, object: nil, queue: nil
            ) { note in
                let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                    .flatMap { AVAudioSession.InterruptionType(rawValue: $0) }
                let reason = (note.userInfo?[AVAudioSessionInterruptionReasonKey] as? UInt)
                    .flatMap { AVAudioSession.InterruptionReason(rawValue: $0) }
                Self.logger.warning(
                    "AVAudioSession interruption: type=\(String(describing: type)) reason=\(String(describing: reason))")
            }
        )
        audioSessionObservers.append(
            center.addObserver(
                forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil
            ) { note in
                let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                    .flatMap { AVAudioSession.RouteChangeReason(rawValue: $0) }
                Self.logger.warning(
                    "AVAudioSession route change: reason=\(String(describing: reason))")
            }
        )
    }

    private func stopAudioSessionDiagnostics() {
        for observer in audioSessionObservers {
            NotificationCenter.default.removeObserver(observer)
        }
        audioSessionObservers = []
    }

    deinit {
        for observer in audioSessionObservers {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    // MARK: - Network Path Monitoring

    private func startPathMonitor() {
        stopPathMonitor()
        lastPathSignature = nil

        let monitor = NWPathMonitor()
        pathMonitor = monitor
        monitor.pathUpdateHandler = { [weak self] path in
            let signature = Self.pathSignature(for: path)
            Task { @MainActor in
                guard let self else { return }
                // The first update only establishes the baseline — it is not a change.
                guard let last = self.lastPathSignature else {
                    self.lastPathSignature = signature
                    return
                }
                guard signature != last else { return }
                Self.logger.info("network path changed: \(last) -> \(signature)")
                self.lastPathSignature = signature
                self.handleNetworkPathChange()
            }
        }
        monitor.start(queue: pathMonitorQueue)
    }

    private func stopPathMonitor() {
        pathMonitor?.cancel()
        pathMonitor = nil
        lastPathSignature = nil
    }

    private nonisolated static func pathSignature(for path: NWPath) -> String {
        path.availableInterfaces
            .map { "\($0.type)" }
            .sorted()
            .joined(separator: ",")
    }

    func stop() {
        let logger = Self.logger

        // Clear the publish intent first so in-flight reconnects and session-state
        // callbacks stand down.
        publishIntent = nil
        initialPublishTask?.cancel()
        initialPublishTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        stopPathMonitor()

        // Invalidate every outstanding generation-guarded callback.
        connectionGeneration &+= 1
        cameraWatchdogTask?.cancel()
        cameraWatchdogTask = nil
        cameraWatchdogRecoveries = 0
        emissionWatchdogTask?.cancel()
        emissionWatchdogTask = nil
        emissionWatchdogRecoveries = 0
        emissionWatchdogAudioRecoveries = 0
        stopAudioSessionDiagnostics()
        for task in trackStateTasks { task.cancel() }
        trackStateTasks = []
        lastPublishedFrameAt = nil
        lastPublishedVideoFrameAt = nil
        lastPublishedAudioFrameAt = nil
        videoTrackActiveAt = nil
        audioTrackActiveAt = nil
        stalledAudioTracks = []
        framePublishedGate.reset()
        videoFramePublishedGate.reset()
        audioFramePublishedGate.reset()

        logger.info("cancelling tasks")
        publisherStateTask?.cancel()
        publisherStateTask = nil
        publisherEventsTask?.cancel()
        publisherEventsTask = nil
        stateObserverTask?.cancel()
        stateObserverTask = nil
        logger.info("tasks cancelled")

        // Capture references before clearing — the detached task needs them.
        let pub = publisher
        publisher = nil
        session = nil
        publishedTracks = []
        trackStates = [:]
        publisherState = .idle
        sessionState = .idle

        // Stop publisher and close session off the main thread.
        // publisher.stop() flushes encoders synchronously — with @MainActor
        // removed from Publisher, this now actually runs off-main.
        Task.detached {
            logger.info("stopping publisher")
            pub?.stop()
            logger.info("publisher stopped")
            logger.info("closing session")
            // await sess?.close()
            logger.info("session closed")
        }

        logger.info("cleaning up capture sources")
        cleanupCaptureSources()
        logger.info("capture sources cleaned up")
        Self.configurePlaybackAudioSession()

        do {
            let store = ReplayKitBroadcastDescriptorStore(
                appGroupIdentifier: replayKitAppGroupIdentifier
            )
            try store.clear()
            replayKitPrepared = false
        } catch {
            lastError = "ReplayKit cleanup failed: \(error.localizedDescription)"
        }
    }

    // MARK: - Private

    private func cleanupCaptureSources() {
        // Don't stop the camera — it's shared with preview via cameraCapture
        camera = nil
        if cameraEnabled && cameraSourceMode == .multiCamera && multiCamera != nil {
            isPreviewRunning = true
        } else {
            multiCamera?.stop()
            multiCamera = nil
            if cameraSourceMode == .multiCamera {
                isPreviewRunning = false
            }
        }
        microphone?.stop()
        microphone = nil
    }

    private func runningMultiCameraCapture(
        videoConfig: VideoEncoderConfig
    ) async throws -> MultiCameraCapture {
        if let existing = multiCamera {
            if isMultiCamera(existing, configuredFor: videoConfig) {
                try await existing.start()
                isPreviewRunning = true
                return existing
            }

            existing.stop()
            multiCamera = nil
            isPreviewRunning = false
        }

        let multi = makeMultiCameraCapture(videoConfig: videoConfig)
        multiCamera = multi

        do {
            try await multi.start()
            isPreviewRunning = true
            return multi
        } catch {
            if multiCamera === multi {
                multiCamera = nil
                isPreviewRunning = false
            }
            throw error
        }
    }

    private func makeMultiCameraCapture(videoConfig: VideoEncoderConfig) -> MultiCameraCapture {
        MultiCameraCapture(
            front: Camera(
                position: .front,
                width: videoConfig.width,
                height: videoConfig.height
            ),
            back: Camera(
                position: .back,
                width: videoConfig.width,
                height: videoConfig.height
            ),
            maxFrameRate: videoConfig.maxFrameRate
        )
    }

    private func isMultiCamera(
        _ multi: MultiCameraCapture,
        configuredFor videoConfig: VideoEncoderConfig
    ) -> Bool {
        multi.front.width == videoConfig.width
            && multi.front.height == videoConfig.height
            && multi.back.width == videoConfig.width
            && multi.back.height == videoConfig.height
            && multi.maxFrameRate == videoConfig.maxFrameRate
    }

    private func currentVideoConfig() -> VideoEncoderConfig {
        VideoEncoderConfig(
            codec: videoCodec,
            width: videoResolution.width,
            height: videoResolution.height,
            maxFrameRate: videoFrameRate.value
        )
    }

    private func currentAudioConfig() -> AudioEncoderConfig {
        AudioEncoderConfig(
            codec: audioCodec,
            sampleRate: audioSampleRate.value
        )
    }

    private func publishUnsupportedReason(
        videoConfig: VideoEncoderConfig,
        audioConfig: AudioEncoderConfig
    ) -> String? {
        if cameraEnabled && cameraSourceMode == .multiCamera && !MultiCameraCapture.isSupported {
            return "Multi-camera capture is not supported on this device"
        }
        if (cameraEnabled || screenEnabled), let reason = videoConfig.unsupportedReason {
            return reason
        }
        if (micEnabled || screenAudioEnabled), let reason = audioConfig.unsupportedReason {
            return reason
        }
        return nil
    }

    private static func defaultVideoCodec() -> VideoCodec {
        let supported = VideoEncoderConfig.supportedCodecs()
        if supported.contains(.h265) { return .h265 }
        return supported.first ?? .h264
    }

    private static func defaultAudioCodec() -> MoQKit.AudioCodec {
        let supported = AudioEncoderConfig.supportedCodecs()
        if supported.contains(.opus) { return .opus }
        return supported.first ?? .aac
    }

    private func observePublisher(_ pub: Publisher, generation: UInt64) {
        publisherStateTask = Task { [weak self] in
            guard let self else { return }
            for await state in pub.state {
                guard self.connectionGeneration == generation else { return }
                self.publisherState = state
            }
        }

        publisherEventsTask = Task { [weak self] in
            guard let self else { return }
            for await event in pub.events {
                guard self.connectionGeneration == generation else { return }
                switch event {
                case .trackStarted(let name):
                    Self.logger.info(
                        "generation \(generation): track '\(name)' starting -> active (first frame published)")
                    self.trackStates[name] = .active
                    self.lastPublishedFrameAt = Date()
                    // The new session has announced and is publishing frames, so a
                    // reconnect failure banner from the previous generation is stale
                    // — clear it (the retry counter itself is loop-local and was
                    // already reset when the loop exited on success).
                    self.lastError = nil
                    if self.cameraTrackNames.contains(name) {
                        self.cameraWatchdogRecoveries = 0
                        // Grace-period anchor for the emission watchdog's
                        // "active but zero frames ever written" check.
                        if self.videoTrackActiveAt == nil {
                            self.videoTrackActiveAt = Date()
                        }
                    }
                    if self.audioTrackNames.contains(name), self.audioTrackActiveAt == nil {
                        self.audioTrackActiveAt = Date()
                    }
                case .trackStopped(let name):
                    self.trackStates[name] = .stopped
                case .error(let name, let msg):
                    self.trackStates[name] = .stopped
                    self.lastError = "\(name): \(msg)"
                }
            }
        }

        // Observe individual track states
        for track in publishedTracks {
            let name = track.name
            let task = Task { [weak self] in
                guard let self else { return }
                for await state in track.state {
                    guard self.connectionGeneration == generation else { return }
                    Self.logger.debug(
                        "generation \(generation): track '\(name)' state -> \(String(describing: state))")
                    self.trackStates[name] = state
                }
            }
            trackStateTasks.append(task)
        }
    }

}

/// Thread-safe ~1 Hz gate coalescing per-frame publish notifications that arrive
/// on encoder output threads, so at most one main-actor hop per second updates
/// ``PublisherViewModel/lastPublishedFrameAt``.
private final class FramePublishedGate: @unchecked Sendable {
    private let lock = NSLock()
    private var lastNotifiedAt: TimeInterval = 0

    func shouldNotify(now: TimeInterval = Date().timeIntervalSince1970) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard now - lastNotifiedAt >= 1 else { return false }
        lastNotifiedAt = now
        return true
    }

    func reset() {
        lock.lock()
        lastNotifiedAt = 0
        lock.unlock()
    }
}
