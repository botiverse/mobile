import Foundation
import SwiftUI
import UIKit

struct KuiklyRenderViewPage: UIViewControllerRepresentable {
    var pageName: String
    var data: Dictionary<String, Any>
    // task #419: in visual-capture mode, hide the simulator's system status bar
    // and home indicator so the screenshot matches the android/react baseline
    // (iOS can only hide these from the app side). Only the fixture-launch path
    // sets this; normal launches are unaffected.
    var hidesSystemChrome: Bool = false
    //  typealiaUIViewControllerType = UINavigationController
    func makeUIViewController(context: Context) -> UINavigationController {
        let hrVC = KuiklyRenderViewController(pageName: pageName, pageData: data)
        if hidesSystemChrome {
            return VisualCaptureNavigationController(rootViewController: hrVC)
        }
        return UINavigationController.init(rootViewController: hrVC)
    }

    func updateUIViewController(_ uiViewController: UINavigationController, context: Context) {

    }

    func dealloc() {

    }

}

// task #419: hides the status bar + home indicator regardless of the hosted
// child view controller, so visual-capture screenshots have no system chrome
// (clock/wifi/battery/home indicator) overlaying the fixture. Returning nil from
// childFor* stops the nav controller deferring the decision to its top child.
final class VisualCaptureNavigationController: UINavigationController {
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var childForStatusBarHidden: UIViewController? { nil }
    override var childForHomeIndicatorAutoHidden: UIViewController? { nil }
}
