import Foundation
import Network

/// task #191: records OS network-path changes (Wi-Fi ⇄ cellular, offline, expensive/constrained)
/// into raft-diagnostics.jsonl so a "connection feels flaky" report can be lined up against
/// network switches. Observation only — nothing reacts to these events.
final class SlockIosNetworkPathMonitor {
    static let shared = SlockIosNetworkPathMonitor()

    private let queue = DispatchQueue(label: "build.raft.network-path-monitor")
    private var monitor: NWPathMonitor?
    private var lastSignature: String?
    private var lastChangeUptime: TimeInterval?

    private init() {}

    func start() {
        queue.async { [weak self] in
            guard let self, self.monitor == nil else { return }
            let monitor = NWPathMonitor()
            monitor.pathUpdateHandler = { [weak self] path in
                self?.handle(path)
            }
            self.monitor = monitor
            monitor.start(queue: self.queue)
        }
    }

    // Runs on `queue` (the monitor's callback queue).
    private func handle(_ path: NWPath) {
        let signature = Self.signature(of: path)
        guard signature != lastSignature else { return }
        let now = ProcessInfo.processInfo.systemUptime
        let sinceLastMs = lastChangeUptime.map { String(Int(((now - $0) * 1000).rounded())) } ?? "none"
        let previous = lastSignature == nil ? "none" : "changed"
        lastSignature = signature
        lastChangeUptime = now
        SlockIosDiagnosticsRecordWithContext(
            path.status == .satisfied ? "info" : "warn",
            "SlockIosNetworkPath",
            "network_path_update \(signature) previous=\(previous) sinceLastChangeMs=\(sinceLastMs)",
            0,
            false,
            true
        )
    }

    static func signature(of path: NWPath) -> String {
        let status: String
        switch path.status {
        case .satisfied: status = "satisfied"
        case .unsatisfied: status = "unsatisfied"
        case .requiresConnection: status = "requires_connection"
        @unknown default: status = "unknown"
        }
        let interfaces =
            path.availableInterfaces.map { interface -> String in
                switch interface.type {
                case .wifi: return "wifi"
                case .cellular: return "cellular"
                case .wiredEthernet: return "wired"
                case .loopback: return "loopback"
                case .other: return "other"
                @unknown default: return "unknown"
                }
            }
        return "status=\(status) interfaces=\(interfaces.isEmpty ? "none" : interfaces.joined(separator: ",")) " +
            "expensive=\(path.isExpensive) constrained=\(path.isConstrained)"
    }
}
