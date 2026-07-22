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

    var publisherStateLabel: String {
        switch publisherState {
        case .idle: return "idle"
        case .publishing: return "publishing"
        case .stopped: return "stopped"
        case .error(let msg): return "error: \(msg)"
        }
    }

    var publisherStateColor: Color {
        switch publisherState {
        case .idle: return .gray
        case .publishing: return .green
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

        lastError = nil
        publishedTracks = []
        trackStates = [:]

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

        Task {
            do {
                try await self.connectAndPublish(url: url, path: path)
            } catch is CancellationError {
                // Stopped (or superseded) while connecting — stop() already reset the UI.
            } catch {
                // Initial publish failures surface immediately; automatic recovery
                // only applies to a session that was already established. If a newer
                // publish superseded this one, leave its state alone.
                guard self.publishIntent?.url == url, self.publishIntent?.path == path else {
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

        let oldPublisher = publisher
        publisher = nil
        let oldSession = session
        session = nil
        oldPublisher?.stop()
        if let oldSession {
            await oldSession.close()
        }

        let s = Session(url: url)
        session = s

        stateObserverTask = Task { [weak self] in
            guard let self else { return }
            for await state in s.state {
                self.sessionState = state
                if case .error = state {
                    self.handleSessionFailure()
                }
            }
        }

        var createdPub: Publisher?
        do {
            try await s.connect()

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
                    } else {
                        cam = CameraCapture(camera: Camera(position: self.cameraPosition))
                        self.cameraCapture = cam
                        try await cam.start()
                    }
                    self.camera = cam

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

            try await s.publish(path: path, publisher: pub)
            try await pub.start()

            self.observePublisher(pub)
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
    private func handleSessionFailure() {
        guard publishIntent != nil else { return }
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
                    return
                } catch is CancellationError {
                    return
                } catch {
                    self.lastError = "reconnect attempt \(attempt) failed: \(error.localizedDescription)"
                }
            }
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
        let logger = Logger(subsystem: "viewing", category: "PublisherModel")

        // Clear the publish intent first so in-flight reconnects and session-state
        // callbacks stand down.
        publishIntent = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        stopPathMonitor()

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

    private func observePublisher(_ pub: Publisher) {
        publisherStateTask = Task {
            for await state in pub.state {
                self.publisherState = state
            }
        }

        publisherEventsTask = Task {
            for await event in pub.events {
                switch event {
                case .trackStarted(let name):
                    self.trackStates[name] = .active
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
            Task {
                for await state in track.state {
                    self.trackStates[name] = state
                }
            }
        }
    }

}
