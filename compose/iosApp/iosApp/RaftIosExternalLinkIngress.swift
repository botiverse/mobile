import Foundation

/// Platform identity only. The shared coordinator owns URL parsing, auth and routing.
struct RaftIosExternalLinkOrigin: Equatable {
    let sceneId: String
    let shellScopeId: String
}

struct RaftIosExternalLinkOffer {
    let rawURL: String
    let activationId: String
    let origin: RaftIosExternalLinkOrigin
}

/// Adapts UIKit activity delivery without retaining a pending navigation queue.
/// Re-delivery of the same activity to the same owner keeps its activation id;
/// a fresh user activation or a different window has a distinct identity.
final class RaftIosExternalLinkIngress {
    private final class Delivery: NSObject {
        let rawURL: String
        let origin: RaftIosExternalLinkOrigin
        let activationId: String

        init(rawURL: String, origin: RaftIosExternalLinkOrigin, activationId: String) {
            self.rawURL = rawURL
            self.origin = origin
            self.activationId = activationId
        }
    }

    private let deliveries = NSMapTable<NSUserActivity, NSMutableArray>(
        keyOptions: [.weakMemory, .objectPointerPersonality], valueOptions: .strongMemory
    )
    private let makeActivationId: () -> String
    private let offer: (RaftIosExternalLinkOffer) -> Void
    private let disconnect: (String) -> Void

    init(
        makeActivationId: @escaping () -> String = { UUID().uuidString.lowercased() },
        offer: @escaping (RaftIosExternalLinkOffer) -> Void,
        disconnect: @escaping (String) -> Void
    ) {
        self.makeActivationId = makeActivationId
        self.offer = offer
        self.disconnect = disconnect
    }

    @discardableResult
    func receive(_ activity: NSUserActivity, origin: RaftIosExternalLinkOrigin) -> Bool {
        precondition(Thread.isMainThread)
        guard activity.activityType == NSUserActivityTypeBrowsingWeb,
              let url = activity.webpageURL,
              url.scheme?.lowercased() == "https",
              !origin.sceneId.isEmpty, !origin.shellScopeId.isEmpty else {
            return false
        }
        // Do not normalize/decode the URL here. Strict host/path/query validation
        // is shared with Android and runs before any destination is opened.
        let rawURL = url.absoluteString
        let previous = deliveries.object(forKey: activity) ?? NSMutableArray()
        let delivery = previous.compactMap { $0 as? Delivery }.first {
            $0.rawURL == rawURL && $0.origin == origin
        } ?? Delivery(rawURL: rawURL, origin: origin, activationId: makeActivationId())
        if !previous.contains(delivery) { previous.add(delivery) }
        deliveries.setObject(previous, forKey: activity)
        offer(RaftIosExternalLinkOffer(
            rawURL: rawURL, activationId: delivery.activationId, origin: origin
        ))
        return true
    }

    func sceneDisconnected(_ sceneId: String) {
        precondition(Thread.isMainThread)
        guard !sceneId.isEmpty else { return }
        for activity in deliveries.keyEnumerator().allObjects.compactMap({ $0 as? NSUserActivity }) {
            guard let values = deliveries.object(forKey: activity) else { continue }
            let retained = values.compactMap { $0 as? Delivery }.filter { $0.origin.sceneId != sceneId }
            if retained.isEmpty {
                deliveries.removeObject(forKey: activity)
            } else {
                deliveries.setObject(NSMutableArray(array: retained), forKey: activity)
            }
        }
        disconnect(sceneId)
    }
}
