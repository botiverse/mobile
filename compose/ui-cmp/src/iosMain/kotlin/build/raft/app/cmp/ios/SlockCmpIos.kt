package build.raft.app.cmp.ios

import androidx.compose.ui.window.ComposeUIViewController
import build.raft.app.cmp.SlockCmpRoot
import build.raft.app.cmp.SlockCmpRouter
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.SlockCmpHost
import build.raft.app.cmp.host.SlockCmpNavigator
import build.raft.app.cmp.host.SlockCmpPageArgs
import build.raft.app.toSlockJsonString
import kotlinx.serialization.json.JsonObject
import platform.UIKit.UIViewController

/**
 * Implemented by the iOS app (Objective-C, `SlockCmpBridgeHost`): forwards one bridge call to an
 * `HRBridgeModule`, the same native implementation Kuikly pages use. [paramsJson] is the method's
 * param object; the host hands the result payload (JSON text, or nil) to [callback], possibly from
 * a background thread and, for streaming methods, more than once.
 */
interface SlockCmpIosBridge {
    fun call(method: String, paramsJson: String, callback: SlockCmpIosBridgeCallback?)
}

/** Result sink for [SlockCmpIosBridge.call]. Implemented in Kotlin; the host only invokes it. */
interface SlockCmpIosBridgeCallback {
    fun deliver(json: String?)
}

/**
 * Implemented by the iOS app (Swift, `SlockCmpPageNavigator`): page navigation through the app's
 * `UINavigationController`. [openPage] goes through the same page factory as Kuikly routing.
 */
interface SlockCmpIosNavigator {
    fun openPage(pageName: String, pageDataJson: String)

    fun closePage()
}

/** iOS entry points of the CMP layer, exported through the `shared` framework. */
object SlockCmpIos {
    /** Call once at app start, before any routing decision. */
    fun configure(debugBuild: Boolean) {
        SlockCmpRouter.configure(debugBuild = debugBuild)
    }

    /** True when [pageName] must be pushed as [makeViewController] instead of a Kuikly page. */
    fun rendersOnCmp(pageName: String): Boolean = SlockCmpRouter.rendersOnCmp(pageName)

    /**
     * A view controller rendering [pageName] on CMP with [pageDataJson] (the same pageData the
     * Kuikly page would get, as JSON text). Push it on the existing navigation controller.
     */
    fun makeViewController(
        pageName: String,
        pageDataJson: String,
        bridge: SlockCmpIosBridge,
        navigator: SlockCmpIosNavigator,
    ): UIViewController {
        val host =
            SlockCmpHost(
                capabilities =
                    BridgeWireHostCapabilities { method, paramsJson, callback ->
                        bridge.call(method, paramsJson, callback?.let(::BridgeCallback))
                    },
                navigator = IosNavigator(navigator),
            )
        val args = SlockCmpPageArgs.fromJsonText(pageName, pageDataJson)
        return ComposeUIViewController { SlockCmpRoot(args, host) }
    }
}

private class BridgeCallback(private val onResult: (String?) -> Unit) : SlockCmpIosBridgeCallback {
    override fun deliver(json: String?) = onResult(json)
}

private class IosNavigator(private val navigator: SlockCmpIosNavigator) : SlockCmpNavigator {
    override fun openPage(pageName: String, pageData: JsonObject) =
        navigator.openPage(pageName, pageData.toSlockJsonString())

    override fun closePage() = navigator.closePage()
}
