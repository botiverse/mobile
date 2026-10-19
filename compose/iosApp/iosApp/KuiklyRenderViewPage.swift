import Foundation
import SwiftUI

struct KuiklyRenderViewPage: UIViewControllerRepresentable {
    var pageName: String
    var data: Dictionary<String, Any>
    //  typealiaUIViewControllerType = UINavigationController
    func makeUIViewController(context: Context) -> UINavigationController {
        // Kuikly or Compose Multiplatform by page name (SlockCmpRouter allowlist).
        let hrVC = SlockPageControllerFactory.controller(pageName: pageName, pageData: data)
        return UINavigationController.init(rootViewController: hrVC)
    }

    func updateUIViewController(_ uiViewController: UINavigationController, context: Context) {

    }

    func dealloc() {

    }

}
