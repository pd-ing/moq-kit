import SwiftUI

struct PublisherConnectionControlsView: View {
    @Binding var relayURL: String
    @Binding var broadcastPath: String
    let canConnect: Bool
    let canStop: Bool
    let onConnect: () -> Void
    let onStop: () -> Void

    /// Soft warning when the URL parses but does not target the publisher port
    /// from the QA scenario (4445). Never blocks publishing — other relay setups
    /// are legitimate; the hint exists because build 15 auto-filled a 4443 URL
    /// that mismatched the scenario (DEFECT-IOS15-P1-06).
    private var relayPortWarning: String? {
        let trimmed = relayURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let url = URL(string: trimmed),
              let port = url.port,
              port != 4445 else { return nil }
        return "Publisher scenarios usually target port 4445 — this URL uses port \(port)."
    }

    var body: some View {
        VStack(spacing: 12) {
            TextField("http://<relay-ip>:4445/anon", text: $relayURL)
                .textFieldStyle(.roundedBorder)
                .autocapitalization(.none)
                .disableAutocorrection(true)

            if let relayPortWarning {
                Text(relayPortWarning)
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            TextField("Broadcast Path", text: $broadcastPath)
                .textFieldStyle(.roundedBorder)
                .autocapitalization(.none)
                .disableAutocorrection(true)

            HStack(spacing: 12) {
                Button("Publish") { onConnect() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canConnect)

                Button("Stop") { onStop() }
                    .buttonStyle(.bordered)
                    .disabled(!canStop)
            }
        }
    }
}
