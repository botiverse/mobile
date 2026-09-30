import Foundation
import SwiftUI

/// One owner per SwiftUI window; no process-wide readiness or fallback scene.
final class SlockIosPermalinkSceneHost: ObservableObject {
    private let sceneId = UUID().uuidString
    private let center: NotificationCenter
    private var observers: [NSObjectProtocol] = []
    private let visibilitySink: (String, Bool, Int) -> Void
    private var visible = false
    private var foregroundEpoch = 0
    private var boundScope: String?
    private var retryTimer: Timer?
    private let activityIds = NSMapTable<NSUserActivity, NSString>(keyOptions: .weakMemory, valueOptions: .strongMemory)
    private lazy var intake = SlockIosPermalinkIntake(
        shellScopeId: sceneId,
        dispatch: { [weak self] data in
            self?.center.post(name: Notification.Name("slockExternalPermalinkOpen"), object: nil, userInfo: data)
        },
        result: { activation, reason in
            SlockIosDiagnosticsRecord("info", "SlockIosPermalink", "external_link_transport activationId=\(activation) status=\(reason)")
        }
    )

    init(center: NotificationCenter = .default,
         visibilitySink: @escaping (String, Bool, Int) -> Void = { _, _, _ in }) {
        self.center = center
        self.visibilitySink = visibilitySink
        for event in ["slockExternalPermalinkReady", "slockExternalPermalinkNotReady",
                      "slockExternalPermalinkAccepted", "slockExternalPermalinkResult"] {
            observers.append(center.addObserver(forName: Notification.Name(event), object: nil, queue: .main) {
                [weak self] notification in self?.receive(notification)
            })
        }
    }

    /// Called before constructing the Kuikly root, so a synchronous Ready event
    /// cannot precede the native receiver's registration/binding.
    func bindRoot(revision: String) -> String {
        let scope = sceneId + "/" + revision
        if boundScope != scope {
            if let previous = boundScope { visibilitySink(previous, false, foregroundEpoch) }
            boundScope = scope
            visibilitySink(scope, visible, foregroundEpoch)
        }
        intake.replaceReceiver(shellScopeId: scope)
        return scope
    }

    func setVisible(_ visible: Bool) {
        guard self.visible != visible else { return }
        self.visible = visible
        if visible { foregroundEpoch += 1 }
        if let scope = boundScope { visibilitySink(scope, visible, foregroundEpoch) }
        if visible { resume() }
    }

    func accept(_ activity: NSUserActivity) {
        guard activity.activityType == NSUserActivityTypeBrowsingWeb,
              let url = activity.webpageURL else { return }
        let activation: String
        if let existing = activityIds.object(forKey: activity) {
            activation = existing as String
        } else {
            activation = UUID().uuidString
            activityIds.setObject(activation as NSString, forKey: activity)
        }
        let accepted = intake.receive(url, activationId: activation)
        if !accepted {
            // Do not open the URL again: that would loop back into this handler.
            SlockIosDiagnosticsRecord("warn", "SlockIosPermalink", "external_link_transport activationId=\(activation) status=invalid_or_full")
        }
        updateRetryTimer()
    }

    func resume() {
        intake.retry()
        updateRetryTimer()
    }

    private func receive(_ notification: Notification) {
        guard let data = Self.eventData(notification),
              let scope = data["shellScopeId"] as? String,
              scope == intake.shellScopeId else { return }
        let generation = (data["authGeneration"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        switch notification.name.rawValue {
        case "slockExternalPermalinkReady", "slockExternalPermalinkNotReady":
            if notification.name.rawValue == "slockExternalPermalinkNotReady",
               data["reason"] as? String == "login_cancelled", generation == intake.authGeneration {
                intake.cancelUnboundAuthentication()
                updateRetryTimer()
                return
            }
            intake.setReady(scope: scope, authGeneration: generation,
                            isReady: notification.name.rawValue == "slockExternalPermalinkReady")
        case "slockExternalPermalinkAccepted":
            guard let activation = data["activationId"] as? String,
                  let rawStatus = data["status"] as? String,
                  let status = SlockIosPermalinkIntake.Acceptance(rawValue: rawStatus) else { return }
            intake.acknowledge(activationId: activation, scope: scope, authGeneration: generation, status: status)
        case "slockExternalPermalinkResult":
            guard generation == intake.authGeneration,
                  let activation = data["activationId"] as? String,
                  let status = data["status"] as? String,
                  ["opened", "rejected"].contains(status) else { return }
            let allowedReasons = ["server_unavailable", "conversation_unavailable", "activation_failed",
                                  "identity_missing", "auth_generation_stale"]
            let reason = (data["reason"] as? String).flatMap { allowedReasons.contains($0) ? $0 : nil } ?? "none"
            SlockIosDiagnosticsRecord("info", "SlockIosPermalink", "external_link_result activationId=\(activation) status=\(status) reason=\(reason)")
        default: return
        }
        updateRetryTimer()
    }

    private static func eventData(_ notification: Notification) -> [String: Any]? {
        guard let json = notification.userInfo?["stringify"] as? String,
              let bytes = json.data(using: .utf8),
              let data = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { return nil }
        return data
    }

    private func updateRetryTimer() {
        if intake.pending.isEmpty {
            retryTimer?.invalidate()
            retryTimer = nil
        } else if retryTimer == nil {
            retryTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.resume() }
        }
    }

    deinit {
        retryTimer?.invalidate()
        if let scope = boundScope { visibilitySink(scope, false, foregroundEpoch) }
        for observer in observers { center.removeObserver(observer) }
        intake.cancel(reason: "scene_destroyed")
    }
}


/// One active OAuth request owns cancellation. Scope is captured from its render
/// root, and only an admitted user cancellation may release that root's Unbound links.
final class SlockIosLoginCancellationRelay {
    private struct Request {
        let generation: String
        let mode: String
        let scope: String?
    }
    private var request: Request?
    private let center: NotificationCenter

    init(center: NotificationCenter = .default) { self.center = center }

    func begin(generation: String, mode: String, scope: String?) {
        request = Request(generation: generation, mode: mode, scope: scope)
    }

    func finish(generation: String?, error: String?, status: String, applyRoute: Bool) {
        guard let request, generation == request.generation else { return }
        self.request = nil
        guard applyRoute, request.mode == "login", status == "login_error",
              ["provider_cancelled", "access_denied", "user_cancelled"].contains(error ?? ""),
              let scope = request.scope, !scope.isEmpty else { return }
        let data = ["shellScopeId": scope, "reason": "login_cancelled"]
        guard let bytes = try? JSONSerialization.data(withJSONObject: data),
              let json = String(data: bytes, encoding: .utf8) else { return }
        center.post(name: Notification.Name("slockExternalPermalinkNotReady"),
                    object: nil, userInfo: ["stringify": json])
    }
}
