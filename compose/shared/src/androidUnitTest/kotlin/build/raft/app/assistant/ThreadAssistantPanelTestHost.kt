package build.raft.app.assistant

import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import kotlinx.serialization.json.JsonObject

/** The native host's navigator in the assistant panel tests: records the pages opened through it. */
internal class ThreadAssistantPanelTestNavigator : RaftCmpNavigator {
    val opened = mutableListOf<String>()

    override fun openPage(pageName: String, pageData: JsonObject) {
        opened += pageName
    }

    override fun closePage() = Unit
}

/** A page's host (as RaftCmpRoot provides it) over [navigator]; the bridge goes nowhere. */
internal fun threadAssistantPanelTestHost(navigator: RaftCmpNavigator = ThreadAssistantPanelTestNavigator()): RaftCmpHost {
    val transport = HostBridgeTransport { _, _, _ -> }
    return RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
}
