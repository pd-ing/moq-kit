import SwiftUI
import MoQKit
import os

/// UserDefaults-backed store for relay URLs that connected successfully.
///
/// The `UserDefaults` instance is injectable so save/restore round-trips can be
/// unit-tested against a private suite (e.g. `UserDefaults(suiteName:)` +
/// `removePersistentDomain(forName:)`) without touching `.standard`.
struct RelayURLStore {
    private let defaults: UserDefaults
    private let key = "moqdemo.lastConnectedSharedRelayURL"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The last shared relay URL a session connected to successfully. Nil when
    /// nothing was ever saved — first launch, a build that predates persistence
    /// (the build 15 → 16 update path), or a reinstall that wiped defaults.
    func loadSharedRelayURL() -> String? {
        defaults.string(forKey: key)
    }

    func saveSharedRelayURL(_ url: String) {
        defaults.set(url, forKey: key)
    }
}

struct MoQDemoRelayURLs {
    let boyDemoURL: String
    let sharedRelayURL: String

    private static let logger = Logger(subsystem: "viewing", category: "RelayURLs")

    static let defaults = MoQDemoRelayURLs(
        boyDemoURL: "https://cdn.moq.dev/demo",
        // No hardcoded LAN address: a stale auto-filled IP (build 15 shipped
        // `http://192.168.92.95:4443/anon`) points QA at the wrong relay — and
        // the wrong port. Fall back to the last URL that connected successfully;
        // empty until then, so the field shows its placeholder instead.
        sharedRelayURL: lastConnectedSharedRelayURL ?? ""
    )

    /// The last shared relay URL a session connected to successfully, persisted
    /// across launches. Read on every access so a screen opened later in the
    /// same launch also picks up URLs saved after app start.
    static var lastConnectedSharedRelayURL: String? {
        let url = RelayURLStore().loadSharedRelayURL()
        if url == nil {
            logger.debug("no saved shared relay URL — URL field will show its placeholder")
        }
        return url
    }

    /// Persists a shared relay URL after a session connected to it successfully;
    /// it becomes the pre-filled value the next time a demo screen opens.
    static func saveLastConnectedSharedRelayURL(_ url: String) {
        RelayURLStore().saveSharedRelayURL(url)
        logger.info("saved last connected shared relay URL: \(url)")
    }
}

@main
struct MoQDemoApp: App {
    private let relayURLs = MoQDemoRelayURLs.defaults

    init() {
        PublisherViewModel.configurePlaybackAudioSession()
        KitLogger.setNativeLogLevel("info")
    }

    var body: some Scene {
        WindowGroup {
            ContentView(relayURLs: relayURLs)
        }
    }
}
