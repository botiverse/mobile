import StoreKit
import UIKit
import shared

/// The App Store rating prompt behind the shared rating prompt (`RaftRatingPrompt` decides when,
/// at most once per device). Kotlin calls `requestReview()` on the main thread; it asks StoreKit
/// over the foreground-active window scene and returns true, or returns false when no scene is
/// active (nothing is recorded then). Whether iOS shows anything is iOS's decision.
final class RaftIosStoreReview: NSObject, RaftStoreReviewRequester {
    /// At launch (RaftIosAppDelegate).
    static func install() {
        RaftStoreReviewHost.shared.requester = RaftIosStoreReview()
    }

    func requestReview() -> Bool {
        guard Thread.isMainThread else { return false }
        return MainActor.assumeIsolated {
            guard let scene = Self.activeWindowScene() else { return false }
            if #available(iOS 16.0, *) {
                AppStore.requestReview(in: scene)
            } else {
                SKStoreReviewController.requestReview(in: scene)
            }
            return true
        }
    }

    /// The foreground-active scene holding the key window, else any foreground-active scene.
    @MainActor
    private static func activeWindowScene() -> UIWindowScene? {
        let active = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }
        return active.first(where: { scene in scene.windows.contains(where: { $0.isKeyWindow }) }) ?? active.first
    }
}
