package build.raft.app.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.cmp.ProvideRaftPageCover
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftPageCoverSignal
import kotlinx.serialization.json.JsonObject

/** The native host's navigator in the assistant panel tests: records the pages pushed through it. */
internal class ThreadAssistantPanelTestNavigator(private val panelOpen: () -> Boolean) : RaftCmpNavigator {
    /** Each push, with whether the panel was still open when the platform pushed. */
    val pushes = mutableListOf<Pair<String, Boolean>>()

    override fun openPage(pageName: String, pageData: JsonObject) {
        pushes += pageName to panelOpen()
    }

    override fun closePage() = Unit
}

/**
 * A conversation page as RaftCmpRoot provides it: its host over [navigator] (the bridge goes
 * nowhere) behind the page cover navigator ([ProvideRaftPageCover]), its pager, the theme.
 */
@Composable
internal fun ThreadAssistantPanelTestPage(navigator: RaftCmpNavigator, content: @Composable () -> Unit) {
    val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
    val host =
        remember(navigator) {
            val transport = HostBridgeTransport { _, _, _ -> }
            RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        }
    val signal = remember { RaftPageCoverSignal() }
    ProvideRaftPageCover(host, signal) {
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { content() }
        }
    }
}
