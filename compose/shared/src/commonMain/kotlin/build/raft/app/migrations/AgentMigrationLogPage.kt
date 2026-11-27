package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.data.AgentMigrationLogFilters
import build.raft.app.data.AgentMigrationLogLoad
import build.raft.app.data.AgentMigrationLogSource
import build.raft.app.data.NetworkAgentMigrationLogSource
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.app.thread.dispatch
import build.raft.app.thread.key
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The server's agent migration log (web `/migrations`, slock staging 383e8553641a
 * packages/web/src/components/agentMigration/log/MigrationLogPage.tsx): every move of an agent
 * between computers, for the server's owners and admins. Opens from an agent's migration section
 * (that agent selected) and from a computer's page (that computer selected).
 */

const val AGENT_MIGRATION_LOG_PAGE = "compose_agent_migration_log"
private const val AGENT_MIGRATION_LOG_AGENT = "agentId"
private const val AGENT_MIGRATION_LOG_COMPUTER = "computerId"

@Page(AGENT_MIGRATION_LOG_PAGE, supportInLocal = true)
internal class ComposeAgentMigrationLogPage : AuthenticatedProductPager(AuthenticatedProductRoute.AgentMigrationLog) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { AgentMigrationLogRoute() }
    }
}

/** Who sees the log's links (the server refuses anyone else): web `role === "owner" || role === "admin"`. */
internal fun canSeeAgentMigrationLog(serverRole: String?): Boolean =
    serverRole?.trim()?.lowercase().let { it == "owner" || it == "admin" }

/** The shell scope, and the agent or computer the log opens filtered by. */
internal fun agentMigrationLogPageData(storeScope: RaftShellStoreScope, agentId: String? = null, computerId: String? = null): JSONObject =
    settingsStandalonePageData(AGENT_MIGRATION_LOG_PAGE, storeScope).apply {
        agentId?.takeIf { it.isNotBlank() }?.let { put(AGENT_MIGRATION_LOG_AGENT, it) }
        computerId?.takeIf { it.isNotBlank() }?.let { put(AGENT_MIGRATION_LOG_COMPUTER, it) }
    }

internal fun agentMigrationLogFiltersFromPageData(params: JSONObject): AgentMigrationLogFilters =
    AgentMigrationLogFilters(
        agents = listOfNotNull(params.optString(AGENT_MIGRATION_LOG_AGENT).takeIf { it.isNotBlank() }),
        computers = listOfNotNull(params.optString(AGENT_MIGRATION_LOG_COMPUTER).takeIf { it.isNotBlank() }),
    )

internal fun Pager.openAgentMigrationLogPage(storeScope: RaftShellStoreScope, agentId: String? = null, computerId: String? = null) {
    openRaftPage(
        pageName = AGENT_MIGRATION_LOG_PAGE,
        pageData = agentMigrationLogPageData(storeScope, agentId, computerId),
        storeScopeOverride = storeScope,
    )
}

internal data class AgentMigrationLogState(
    val load: AgentMigrationLogLoad = AgentMigrationLogLoad.Loading,
    val filters: AgentMigrationLogFilters = AgentMigrationLogFilters(),
    val view: AgentMigrationLogView = AgentMigrationLogView.Chart,
    val query: String = "",
    /** The table's rows shown; a new filter or search starts again at one page. */
    val rowLimit: Int = AGENT_MIGRATION_LOG_PAGE_SIZE,
)

internal enum class AgentMigrationLogView { Chart, Table }

/** Web `PAGE_SIZE`: rows drawn at first and added by each "Show next". */
internal const val AGENT_MIGRATION_LOG_PAGE_SIZE = 100

internal sealed interface AgentMigrationLogIntent {
    data class Load(val serverId: String) : AgentMigrationLogIntent

    data object Retry : AgentMigrationLogIntent

    data class SetFilters(val filters: AgentMigrationLogFilters) : AgentMigrationLogIntent

    data class SetView(val view: AgentMigrationLogView) : AgentMigrationLogIntent

    data class Search(val query: String) : AgentMigrationLogIntent

    data object ShowMore : AgentMigrationLogIntent
}

internal class AgentMigrationLogViewModel(
    private val source: AgentMigrationLogSource,
    initialFilters: AgentMigrationLogFilters,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AgentMigrationLogState(filters = initialFilters))
    val state: StateFlow<AgentMigrationLogState> = mutableState.asStateFlow()

    private var serverId: String? = null
    private var loading: Job? = null

    fun dispatch(intent: AgentMigrationLogIntent) {
        when (intent) {
            is AgentMigrationLogIntent.Load -> if (intent.serverId != serverId) load(intent.serverId)
            AgentMigrationLogIntent.Retry -> serverId?.let(::load)
            is AgentMigrationLogIntent.SetFilters ->
                mutableState.update { it.copy(filters = intent.filters, rowLimit = AGENT_MIGRATION_LOG_PAGE_SIZE) }
            is AgentMigrationLogIntent.SetView -> mutableState.update { it.copy(view = intent.view) }
            is AgentMigrationLogIntent.Search ->
                mutableState.update { it.copy(query = intent.query, rowLimit = AGENT_MIGRATION_LOG_PAGE_SIZE) }
            AgentMigrationLogIntent.ShowMore -> mutableState.update { it.copy(rowLimit = it.rowLimit + AGENT_MIGRATION_LOG_PAGE_SIZE) }
        }
    }

    private fun load(id: String) {
        serverId = id
        loading?.cancel()
        mutableState.update { it.copy(load = AgentMigrationLogLoad.Loading) }
        loading =
            viewModelScope.launch {
                val result = source.load(id)
                mutableState.update { it.copy(load = result) }
            }
    }
}

@Composable
private fun AgentMigrationLogRoute() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val storeScope =
        remember(pager.pageData.params.toString(), pager.pagerId) {
            pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
        }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    // The server it opened from; the log of another one is another page.
    val serverId by produceState(storeScope.initialServerId, authStore) {
        if (value == null) value = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()?.currentServerId?.takeIf { it.isNotBlank() }
    }
    val viewModel: AgentMigrationLogViewModel =
        viewModel(key = "agent-migration-log-${pager.pagerId}") {
            AgentMigrationLogViewModel(
                source = NetworkAgentMigrationLogSource(sharedStores.networkClient(storeScope)),
                initialFilters = agentMigrationLogFiltersFromPageData(pager.pageData.params),
            )
        }
    val computersStore = remember(sharedStores, storeScope) { sharedStores.computersStore(storeScope) }
    LaunchedEffect(serverId) {
        val id = serverId ?: return@LaunchedEffect
        viewModel.dispatch(AgentMigrationLogIntent.Load(id))
        computersStore.dispatch(SharedComputersIntent.EnsureComputerPicker(id))
        sharedStores.serverMembersStore.refreshMembers(ServerId(id))
    }
    val state by viewModel.state.collectAsState()
    val machines by
        remember(computersStore, serverId) {
            computersStore.migrationLogMachinesFlow(serverId)
        }.collectAsState(emptyList())
    val members by
        remember(sharedStores, serverId) {
            serverId?.let { sharedStores.serverMembersStore.serverMembersEntryFlow(ServerId(it)) } ?: flowOf(SharedServerMembersEntry(serverId = ""))
        }.collectAsState(SharedServerMembersEntry(serverId = serverId.orEmpty()))
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val avatarResolver = rememberRaftMemberAvatarResolver(serverId, sharedStores.serverMembersStore)
    val copied = stringResource(Res.string.agent_migration_log_copied)
    val openMember: (String, MemberKind) -> Unit = { id, kind ->
        val pageData =
            RaftMembersPayload(instanceId = "agent-migration-log")
                .toMemberDetailPageData(MemberDetailIdentity(MemberId(id), kind, serverId?.let(::ServerId)))
        if (pageData == null) {
            bridge.log("agent_migration_log_open_member_skip reason=invalid_member")
        } else {
            pager.openRaftPage(
                pageName = MEMBER_DETAIL_PAGE,
                pageData = pageData,
                storeScopeOverride = storeScope,
                onMissingScope = { bridge.log("agent_migration_log_open_member_skip reason=missing_shell_scope") },
            )
        }
    }
    AgentMigrationLogScreen(
        state = state,
        machines = machines,
        agents =
            remember(members.membersById) {
                members.membersById.values
                    .filter { it.kind == MemberKind.Agent }
                    .associate { it.id.value to AgentMigrationLogAgent(it.id.value, it.displayName.ifBlank { it.handle }, it.membersComputerId?.takeIf(String::isNotBlank)) }
            },
        wide = LocalRaftSettingsWideDetail.current || windowWidth.value >= DualPaneMinimumWidthDp,
        onIntent = viewModel::dispatch,
        onOpenAgent = { openMember(it, MemberKind.Agent) },
        onOpenPerson = { openMember(it, MemberKind.Human) },
        onCopy = {
            bridge.copyToPasteboard(it)
            bridge.toast(copied)
        },
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
        avatar = { id, size ->
            RaftMemberAvatar(
                identity = RaftMemberAvatarIdentity(MemberKind.Agent, id, serverId),
                label = "",
                avatarSize = size,
                resolver = avatarResolver,
            )
        },
        bottomContentPadding = raftBottomSafeAreaPadding(),
    )
}
