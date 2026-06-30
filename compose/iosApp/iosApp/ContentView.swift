import SwiftUI
import shared

struct ContentView: View {

    var body: some View {
        KuiklyRenderViewPage(
            pageName: "compose_slock_app_shell",
            data: [
                "pageName": "compose_slock_app_shell",
                "slockNativeTabBarRenderer": "iosLiquidGlass"
            ]
        ).ignoresSafeArea()
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
