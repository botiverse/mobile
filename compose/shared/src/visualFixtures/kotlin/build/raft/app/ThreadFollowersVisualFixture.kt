package build.raft.app

import androidx.compose.runtime.*
import build.raft.app.base.BasePager
import build.raft.app.data.*
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.*
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.core.annotations.Page
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

/** Real header, popover, toast and store; only the repository uses deterministic fixture data. */
@Page("compose_thread_followers_visual", supportInLocal = true)
internal class ThreadFollowersVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val elegant = pageData.params.optBoolean("elegant", false)
        val single = pageData.params.optBoolean("single", false)
        setContent {
            SlockThemeProvider(if (elegant) elegantTheme else brutalTheme) {
                FollowersVisualContent(single)
            }
        }
    }
}

@Composable
private fun FollowersVisualContent(single: Boolean) {
    val previousLocale = remember { StringDesc.localeType }
    StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
    DisposableEffect(Unit) { onDispose { StringDesc.localeType = previousLocale } }
    val scope = rememberCoroutineScope()
    val store = remember { SharedThreadAgentFollowersStore(FollowersVisualRepository(single), scope) }
    DisposableEffect(store) { onDispose { store.close() } }
    LaunchedEffect(store) { store.dispatch(ThreadAgentFollowersIntent.Refresh) }
    val state by store.state.collectAsState()
    ThreadFollowersOverlayHost {
        Column(Modifier.fillMaxSize().background(LocalSlockTheme.current.surface)) {
            ThreadPageHeader(
                payload = KuiklyThreadScreenPayload(
                    instanceId = "followers-visual", title = "消息列 — #general", status = "content",
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread, selectedTab = "chat",
                    showModeTabs = false, showSettingsAction = false, showMembersAction = false,
                    showOpenInChannelAction = true,
                ),
                agentFollowersHeader = { ThreadAgentFollowersHeader(state, store::dispatch) },
                onBack = {}, onOpenInChannel = {}, onOpenMemberProfile = {},
                onOpenSearch = {}, onShowMembers = {}, onShowSettings = {},
            )
        }
    }
}

private class FollowersVisualRepository(single: Boolean) : ThreadAgentFollowersRepository {
    private val all = listOf(
        ThreadAgentFollower("1", "Grace", status = "online", serverId = "local", isCurrentServer = true, canRemove = true),
        ThreadAgentFollower("2", "Josh", status = "offline", serverId = "local", isCurrentServer = true, canRemove = true),
        ThreadAgentFollower("3", "Agent", status = "online", serverId = "peer", serverName = "Peer Server", isCurrentServer = false, canRemove = false),
    )
    private var agents = if (single) all.take(1) else all
    override suspend fun load() = DataResult.Content(ThreadAgentFollowerRoster("fixture", true, agents))
    override suspend fun remove(agentId: String): DataResult<ThreadAgentFollowerRemoval> {
        agents = agents.filterNot { it.id == agentId }
        return DataResult.Content(ThreadAgentFollowerRemoval(true, true, "fixture-token-$agentId"))
    }
    override suspend fun restore(agentId: String, undoToken: String): DataResult<Boolean> {
        agents = (agents + all.single { it.id == agentId }).distinctBy { it.id }
        return DataResult.Content(true)
    }
}
