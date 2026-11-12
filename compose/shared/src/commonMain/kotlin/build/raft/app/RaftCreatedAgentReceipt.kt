package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.base.BridgeModule
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ChannelRepository
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.OpenedDirectMessageAuthority
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftDirectMessageOpens
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.SharedHomeStore
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where creating an agent from the Members page or a Computer leaves the person (web
 * `CreateAgentDialog` post-create landing, botiverse/slock #14482; cindyz #triage f4835750,
 * superseding task #399 / Hands afae9ebb's jump to the profile):
 *
 * - [SetupGuide]: an external agent opens its profile, whose setup guide is the next step.
 * - [CreatedToast]: anything else stays where it was, with a "Created <name>" toast whose View
 *   opens the agent's DM (its profile is one tap away in the DM header).
 *
 * The other create paths keep their own landing: a channel's add-members create joins the agent
 * there, an action card stays on the card.
 */
internal sealed interface CreatedAgentLanding {
    val agent: MemberProfile

    data class SetupGuide(override val agent: MemberProfile) : CreatedAgentLanding

    data class CreatedToast(override val agent: MemberProfile) : CreatedAgentLanding
}

/** Web `isExternalMode`: an external runtime, not a cloud agent (which is sent as external too). */
internal fun createdAgentLanding(submit: SharedCreateAgentDialogSubmit, agent: MemberProfile): CreatedAgentLanding =
    if (submit.external && submit.hostedProvider == null) {
        CreatedAgentLanding.SetupGuide(agent)
    } else {
        CreatedAgentLanding.CreatedToast(agent)
    }

/**
 * The created agent stamped with the server it was created on, so View can tell the session moved
 * to another server since (web `onServer()`); a blank [serverId] leaves it as it was.
 */
internal fun MemberProfile.inCreatingServer(serverId: String): MemberProfile =
    serverId.trim().takeIf { it.isNotBlank() }?.let { copy(serverId = ServerId(it)) } ?: this

/** `agent.create.createdToast.*` (web), resolved once in composition (equal while the language is). */
internal data class RaftCreatedAgentToastCopy(
    private val titleTemplate: String,
    val view: String,
    private val openFailedTemplate: String,
) {
    fun title(name: String): String = titleTemplate.replace("%1\$s", name)

    fun openFailed(name: String): String = openFailedTemplate.replace("%1\$s", name)
}

private const val CreatedAgentToastSentinel = "__RAFT_CREATED_AGENT_NAME__"

@Composable
internal fun raftCreatedAgentToastCopy(): RaftCreatedAgentToastCopy =
    RaftCreatedAgentToastCopy(
        titleTemplate =
            stringResource(Res.string.agent_create_created_toast_title, CreatedAgentToastSentinel)
                .replace(CreatedAgentToastSentinel, "%1\$s"),
        view = stringResource(Res.string.agent_create_created_toast_view),
        openFailedTemplate =
            stringResource(Res.string.agent_create_created_toast_open_failed, CreatedAgentToastSentinel)
                .replace(CreatedAgentToastSentinel, "%1\$s"),
    )

/**
 * Web `showCreatedAgentToast`: a success toast "Created <name>" with one action, View (8 s,
 * raft-ui's timeout for a toast with an action). Tapping View closes the toast and runs [onView].
 * Nothing else happens: no page opens until View is tapped.
 */
internal fun showCreatedAgentToast(
    host: RaftToastHostState,
    agent: MemberProfile,
    copy: RaftCreatedAgentToastCopy,
    onView: (MemberProfile) -> Unit,
): Long =
    host.show(
        title = copy.title(agent.displayName),
        intent = RaftToastIntent.Success,
        actionLabel = copy.view,
        onAction = { onView(agent) },
    )

/**
 * The DM with [member] for the caller to open (the profile's Message): Home's DM at once, else one
 * `POST /channels/dm` per server + peer; null while an earlier tap is still opening it.
 */
internal suspend fun openMemberDirectMessage(
    member: MemberProfile,
    authStore: RaftAuthSessionStore,
    channelRepository: ChannelRepository,
    sharedHomeStore: SharedHomeStore?,
    directMessageOpens: RaftDirectMessageOpens = RaftDirectMessageOpens.shared,
): DataResult<OpenDirectMessageResult>? {
    val session = authStore.readSession()
    val authority =
        session?.let {
            val serverId = session.currentServerId ?: return@let null
            OpenedDirectMessageAuthority(
                serverId = ServerId(serverId),
                accountId = MemberId(session.user.id)
            )
        }
    val result =
        when (member.kind) {
            MemberKind.Agent, MemberKind.Human ->
                directMessageOpens.open(
                    channelRepository = channelRepository,
                    serverId = authority?.serverId,
                    peerKind = member.kind,
                    peerId = member.id,
                    homeSnapshot = authority?.let { sharedHomeStore?.snapshotFor(it.serverId) }
                ) ?: return null
            MemberKind.System -> DataResult.Empty(EmptyReason.NoMembers)
            MemberKind.External -> DataResult.Empty(EmptyReason.NoMembers)
        }
    if (result is DataResult.Content && authority != null) {
        sharedHomeStore?.supplementOpenedDirectMessage(
            authority = authority,
            conversation = result.data.conversation
        )
    }
    return result
}

/**
 * Runs View's DM request: the process, not the page, so a request a tap started finishes even
 * if the page's composition goes first (its result is then dropped, see [RaftCreatedAgentReceipt]).
 */
private val raftCreatedAgentViewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * Lands a created agent ([CreatedAgentLanding]): [openSetupGuide] for an external agent, else the
 * "Created <name>" toast on [toastHost] whose View fetches the DM ([openDirectMessage]) in [scope]
 * and opens it ([showDirectMessage]). Without a toast host (no window) the title alone goes to
 * [fallbackToast].
 *
 * The toast lives on the window, the DM opens through the page that created the agent. So when
 * that page leaves ([dispose]) its toast closes with it (View is never offered when nothing can
 * open the DM), and a View already under way opens nothing once the page has gone or the
 * session moved to another server (web `viewCreatedAgent`'s `onServer()`): the person went on.
 */
internal class RaftCreatedAgentReceipt(
    private val scope: CoroutineScope,
    private val toastHost: RaftToastHostState?,
    private val copy: RaftCreatedAgentToastCopy,
    private val openSetupGuide: (MemberProfile) -> Unit,
    private val openDirectMessage: suspend (MemberProfile) -> DataResult<OpenDirectMessageResult>?,
    private val showDirectMessage: (DataResult<OpenDirectMessageResult>, MemberProfile) -> Unit,
    private val fallbackToast: (String) -> Unit,
    /** The session's current server id; null when unknown (no session read). */
    private val currentServerId: suspend () -> String? = { null },
    private val log: (String) -> Unit = {},
) {
    private val disposed = atomic(false)
    private val shownToastIds = atomic(emptySet<Long>())

    fun land(landing: CreatedAgentLanding) {
        val agent = landing.agent
        if (disposed.value) {
            log("create_agent_landing_skip reason=page_gone memberId=${agent.id.value}")
            return
        }
        when (landing) {
            is CreatedAgentLanding.SetupGuide -> {
                log("create_agent_landing kind=setup_guide memberId=${agent.id.value}")
                openSetupGuide(agent)
            }
            is CreatedAgentLanding.CreatedToast -> {
                log("create_agent_landing kind=created_toast memberId=${agent.id.value}")
                val host = toastHost
                if (host == null) {
                    fallbackToast(copy.title(agent.displayName))
                } else {
                    val id = showCreatedAgentToast(host, agent, copy, ::view)
                    shownToastIds.update { it + id }
                }
            }
        }
    }

    /** View on the toast: the agent's DM, as the profile's Message opens it. */
    fun view(agent: MemberProfile) {
        if (disposed.value) {
            log("create_agent_toast_view_skip reason=page_gone memberId=${agent.id.value}")
            return
        }
        log("create_agent_toast_view memberId=${agent.id.value}")
        scope.launch {
            val agentServerId = agent.serverId?.value?.trim()?.takeIf { it.isNotBlank() }
            if (agentServerId != null) {
                val current = currentServerId()?.trim()
                if (current != agentServerId) {
                    log("create_agent_toast_view_skip reason=server_changed memberId=${agent.id.value}")
                    return@launch
                }
            }
            val result = openDirectMessage(agent) ?: return@launch
            if (disposed.value) {
                log("create_agent_toast_view_skip reason=page_gone_during_open memberId=${agent.id.value}")
                return@launch
            }
            showDirectMessage(result, agent)
        }
    }

    /** The page that created the agent left: its toast closes, and a View under way opens nothing. */
    fun dispose() {
        if (disposed.getAndSet(true)) return
        val host = toastHost ?: return
        shownToastIds.getAndSet(emptySet()).forEach(host::dismiss)
    }
}

/**
 * [RaftCreatedAgentReceipt] for one composition: built once per toast host, with the latest
 * callbacks, and [RaftCreatedAgentReceipt.dispose]d when the composition leaves.
 */
@Composable
internal fun rememberCreatedAgentReceipt(
    toastHost: RaftToastHostState?,
    copy: RaftCreatedAgentToastCopy,
    openSetupGuide: (MemberProfile) -> Unit,
    openDirectMessage: suspend (MemberProfile) -> DataResult<OpenDirectMessageResult>?,
    showDirectMessage: (DataResult<OpenDirectMessageResult>, MemberProfile) -> Unit,
    fallbackToast: (String) -> Unit,
    currentServerId: suspend () -> String?,
    log: (String) -> Unit = {},
    scope: CoroutineScope = raftCreatedAgentViewScope,
): RaftCreatedAgentReceipt {
    val latestOpenSetupGuide by rememberUpdatedState(openSetupGuide)
    val latestOpenDirectMessage by rememberUpdatedState(openDirectMessage)
    val latestShowDirectMessage by rememberUpdatedState(showDirectMessage)
    val latestFallbackToast by rememberUpdatedState(fallbackToast)
    val latestCurrentServerId by rememberUpdatedState(currentServerId)
    val latestLog by rememberUpdatedState(log)
    val receipt =
        remember(toastHost, scope, copy) {
            RaftCreatedAgentReceipt(
                scope = scope,
                toastHost = toastHost,
                copy = copy,
                openSetupGuide = { latestOpenSetupGuide(it) },
                openDirectMessage = { latestOpenDirectMessage(it) },
                showDirectMessage = { result, agent -> latestShowDirectMessage(result, agent) },
                fallbackToast = { latestFallbackToast(it) },
                currentServerId = { latestCurrentServerId() },
                log = { latestLog(it) },
            )
        }
    DisposableEffect(receipt) { onDispose { receipt.dispose() } }
    return receipt
}

/**
 * Opens a View's DM result like the profile's Message ([openMembersDirectMessageResult]): in the
 * app shell's Chat when [openInShell] takes it, else a thread page from [pager]; "Couldn't open
 * your conversation with <name>" ([toast]) when there is nothing to open, and an error toast with
 * the failed request's trace id ([errorToast]) for a server error or refusal.
 */
internal fun DataResult<OpenDirectMessageResult>.openCreatedAgentDirectMessage(
    agent: MemberProfile,
    pager: Pager,
    storeScope: RaftShellStoreScope,
    openInShell: ((ThreadLaunchConversationIdentity) -> Boolean)?,
    timeContext: RaftTimeContext?,
    copy: RaftCreatedAgentToastCopy,
    errorTemplate: String,
    permissionMessage: String,
    errorStrings: ErrorPresentationStrings,
    toast: (String) -> Unit,
    errorToast: (message: String, traceId: String?) -> Unit,
) = openMembersDirectMessageResult(
    pager = pager,
    storeScope = storeScope,
    openInShell = openInShell,
    member = agent,
    timeContext = timeContext,
    unavailableMessage = copy.openFailed(agent.displayName),
    errorTemplate = errorTemplate,
    permissionMessage = permissionMessage,
    errorStrings = errorStrings,
    onUnavailableAction = toast,
    onErrorAction = errorToast,
)

/**
 * [RaftCreatedAgentReceipt] for a page: the window's toast host, the DM opened like the profile's
 * Message ([openCreatedAgentDirectMessage]; [openInShell] opens it in the app shell's Chat when it
 * can).
 */
@Composable
internal fun rememberRaftCreatedAgentReceipt(
    pager: Pager,
    bridge: BridgeModule,
    storeScope: RaftShellStoreScope,
    openSetupGuide: (MemberProfile) -> Unit,
    openInShell: ((ThreadLaunchConversationIdentity) -> Boolean)? = null,
    timeContext: RaftTimeContext? = null,
): RaftCreatedAgentReceipt {
    val toastHost = LocalRaftToastHostState.current
    val copy = raftCreatedAgentToastCopy()
    val errorStrings = localizedErrorPresentationStrings()
    val errorTemplate =
        stringResource(Res.string.member_error_open_direct_message_detail, CreatedAgentToastSentinel)
            .replace(CreatedAgentToastSentinel, "%1\$s")
    val permissionMessage = stringResource(Res.string.member_error_open_direct_message_permission)
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val sharedHomeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val channelRepository =
        remember(networkClient, authStore) {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { message -> bridge.log("created_agent_channel_repository $message") }
            )
        }
    return rememberCreatedAgentReceipt(
        toastHost = toastHost,
        copy = copy,
        openSetupGuide = openSetupGuide,
        openDirectMessage = { agent -> openMemberDirectMessage(agent, authStore, channelRepository, sharedHomeStore) },
        showDirectMessage = { result, agent ->
            result.openCreatedAgentDirectMessage(
                agent = agent,
                pager = pager,
                storeScope = storeScope,
                openInShell = openInShell,
                timeContext = timeContext,
                copy = copy,
                errorTemplate = errorTemplate,
                permissionMessage = permissionMessage,
                errorStrings = errorStrings,
                toast = { bridge.toast(it) },
                errorToast = { message, traceId -> bridge.errorToast(message, traceId) },
            )
        },
        fallbackToast = { bridge.toast(it) },
        currentServerId = { authStore.readSession()?.currentServerId },
        log = { bridge.log(it) },
    )
}
