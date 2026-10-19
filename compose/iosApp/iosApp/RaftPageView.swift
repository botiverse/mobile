import Foundation
import SwiftUI

/// SwiftUI root of a page stack: a `UINavigationController` whose root is the page for
/// [pageName] (a `RaftPageViewController` hosting the CMP page, see `SlockPageControllerFactory`).
/// Further pages are pushed on this navigation controller.
struct RaftPageView: UIViewControllerRepresentable {
    var pageName: String
    var data: [String: Any]

    func makeUIViewController(context: Context) -> UINavigationController {
        let page = SlockPageControllerFactory.controller(pageName: pageName, pageData: data)
        return UINavigationController(rootViewController: page)
    }

    func updateUIViewController(_ uiViewController: UINavigationController, context: Context) {}
}
