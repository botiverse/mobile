package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.AGENT_LOUNGE_FEATURE_FLAG_KEY
import build.raft.app.data.AgentLoungeApi
import build.raft.app.data.AgentLoungeOpenResult
import build.raft.app.data.DataResult
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.agentLoungeEnabled
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftReadOnlyComposer
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.data.agentLoungeOpenFailureTraceId
import kotlinx.coroutines.launch

/**
 * Agent Lounge v0 UI pieces shared by Home, the conversation page and the agent profile (web
 * components/agent/LoungeAvatar.tsx, layout/AgentLoungeRow.tsx, features/channel/ChatPanel.tsx).
 * The rules live in data/AgentLounge.kt.
 */

/** Test tags (the web's `data-testid`s where there is one). */
internal object AgentLoungeTestTags {
    const val SidebarRow = "home-sidebar-lounge-row"
    const val SidebarUnread = "sidebar-lounge-unread"
    const val SidebarMention = "sidebar-lounge-mention-marker"
    const val AvatarBadge = "lounge-avatar-badge"
    const val Header = "lounge-header"
    const val AudienceNotice = "lounge-audience-notice"
    const val ReadOnlyComposer = "lounge-read-only-composer"
    const val ArchivedNotice = "lounge-archived-notice"
    const val UnarchiveAction = "lounge-unarchive-action"
    const val ProfileAction = "agent-profile-inline-lounge"
    const val DmHeaderToggle = "agent-dm-open-lounge"
}

/** "{Name}'s Lounge". */
@Composable
internal fun agentLoungeTitle(agentName: String): String = stringResource(Res.string.agent_lounge_title, agentName)

/** "{Name}'s Lounge" outside composition. */
internal fun agentLoungeTitleNow(agentName: String): String =
    Res.string.agent_lounge_title.format(agentName).resolve()

/** The server has Lounges on and this person is not a Guest; recomposes when the flag resolves. */
@Composable
internal fun rememberAgentLoungeEnabled(
    flags: SharedServerFeatureFlags?,
    serverId: String?,
    serverRole: String?,
): Boolean {
    val flag = rememberServerFeatureLatched(flags, serverId?.takeIf { it.isNotBlank() }?.let(::ServerId), AGENT_LOUNGE_FEATURE_FLAG_KEY)
    return agentLoungeEnabled(flag, serverRole)
}

/** The Lounge with [channelId] in [serverId], kept current as Lounges become known. */
@Composable
internal fun rememberAgentLounge(serverId: String?, channelId: String?): RaftAgentLounge? {
    val all by RaftAgentLounges.lounges.collectAsState()
    return remember(all, serverId, channelId) {
        RaftAgentLounges.lounge(serverId, channelId) ?: RaftAgentLounges.loungeAnyServer(channelId)
    }
}

/** The name of [lounge]'s agent: the member directory's display name when it knows the agent, else the Lounge's own. */
@Composable
internal fun RaftAgentLounge.rememberDisplayAgentName(memberDisplayName: String? = null): String =
    remember(this, memberDisplayName) { memberDisplayName?.takeIf { it.isNotBlank() } ?: displayAgentName }

/**
 * The Lounge's face: its agent's avatar with the Lounge mark (lucide `users` on `info-soft`, an
 * ink edge) in the bottom-right corner, wherever the Lounge itself is shown. Decorative: the row
 * or header around it carries the name ("{Name}'s Lounge").
 */
@Composable
internal fun RaftLoungeAvatar(
    agentId: String?,
    avatarWireValue: String?,
    label: String,
    cacheServerId: String?,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    // Web LOUNGE_BADGE_CLASS: 11px on the sidebar's 19px face, 14 on 35, 18 on 51; glyph about two thirds.
    val badgeSize = (avatarSize * 0.4f).coerceAtLeast(11.dp)
    val glyphSize = (badgeSize * 0.7f).coerceAtLeast(8.dp)
    Box(modifier = modifier.size(avatarSize).clearAndSetSemantics {}) {
        RaftAppAvatar(
            kind = RaftAvatarKind.Agent,
            avatarWireValue = avatarWireValue,
            label = label,
            avatarSize = avatarSize,
            cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, agentId),
            cacheServerId = cacheServerId,
            modifier = Modifier.align(Alignment.Center),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(badgeSize)
                    .background(colors.layerCanvas, RoundedCornerShape(3.dp))
                    .border(1.dp, colors.foreground, RoundedCornerShape(3.dp))
                    .background(colors.infoSoft, RoundedCornerShape(3.dp))
                    .testTag(AgentLoungeTestTags.AvatarBadge),
            contentAlignment = Alignment.Center,
        ) {
            RaftContentLucideIcon(RaftLucideIconName.Users, size = glyphSize)
        }
    }
}

/** Agent Lounge v0: who is here, always on top of the conversation (web `lounge-audience-notice`). */
@Composable
internal fun AgentLoungeAudienceNotice(modifier: Modifier = Modifier) {
    RaftBanner(
        description = stringResource(Res.string.agent_lounge_audience_notice),
        status = RaftBannerStatus.Info,
        modifier = modifier.fillMaxWidth().testTag(AgentLoungeTestTags.AudienceNotice),
    )
}

/** A Lounge this person may only read (`canPost: false`): the read-only composer, no Join. */
@Composable
internal fun AgentLoungeReadOnlyComposer(modifier: Modifier = Modifier) {
    RaftReadOnlyComposer(
        text = stringResource(Res.string.thread_composer_chat_unavailable),
        modifier = modifier.testTag(AgentLoungeTestTags.ReadOnlyComposer),
    )
}

/**
 * An archived Lounge (web ChatPanel archived banner + `LoungeUnarchiveAction`): "This channel is
 * archived." and, only for a viewer whose server role may archive channels, Unarchive. A Lounge
 * has no settings sheet, so the action unarchives in place.
 */
@Composable
internal fun AgentLoungeArchivedNotice(
    canUnarchive: Boolean,
    unarchiving: Boolean,
    onUnarchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ThreadArchivedChannelNotice(
        canUnarchive = canUnarchive,
        unarchiving = unarchiving,
        onUnarchive = onUnarchive,
        modifier = modifier,
        noticeTag = AgentLoungeTestTags.ArchivedNotice,
        unarchiveTag = AgentLoungeTestTags.UnarchiveAction,
    )
}

/**
 * Open an agent's Lounge (POST /agents/:id/lounge makes it the first time) and remember it. Its
 * realtime room is joined by the conversation page it opens, which holds the room lease for as
 * long as it shows (web `joinRealtimeChannel` after `openLounge`); joining here as well would
 * leave the room joined when the navigation does not happen. The caller navigates on success and
 * shows the openFailed toast (with the trace id) on any failure.
 */
internal suspend fun openAgentLounge(
    networkClient: RaftNetworkClient,
    serverId: String,
    agentId: String,
    agentName: String?,
): DataResult<AgentLoungeOpenResult> {
    val result = AgentLoungeApi(networkClient).openLounge(ServerId(serverId), agentId)
    if (result is DataResult.Content) {
        RaftAgentLounges.rememberRow(serverId, result.data.channel, canPost = result.data.canPost, agentName = agentName)
    }
    return result
}

/** The conversation identity a Lounge opens as: a plain channel conversation. */
internal fun AgentLoungeOpenResult.conversationIdentity(): ThreadLaunchConversationIdentity =
    channel.toThreadLaunchConversationIdentity()

/** Opens agents' Lounges from a page: single flight per agent, the openFailed toast on any failure. */
internal class AgentLoungeOpener(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val networkClient: RaftNetworkClient,
    private val openFailedMessage: () -> String,
    private val onError: (message: String, traceId: String?) -> Unit,
) {
    private val pending = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Agent ids whose Lounge is opening (the button's loading state). */
    val opening: kotlinx.coroutines.flow.StateFlow<Set<String>> = pending

    fun open(serverId: String?, agentId: String, agentName: String?, onOpened: (AgentLoungeOpenResult) -> Unit) {
        val cleanServerId = serverId?.takeIf { it.isNotBlank() }
        if (cleanServerId == null) {
            onError(openFailedMessage(), null)
            return
        }
        if (agentId in pending.value) return
        pending.value = pending.value + agentId
        scope.launch {
            val result =
                try {
                    openAgentLounge(networkClient, cleanServerId, agentId, agentName)
                } finally {
                    pending.value = pending.value - agentId
                }
            when (result) {
                is DataResult.Content -> onOpened(result.data)
                else -> onError(openFailedMessage(), result.agentLoungeOpenFailureTraceId())
            }
        }
    }
}

@Composable
internal fun rememberAgentLoungeOpener(
    bridge: build.raft.app.base.BridgeModule,
    storeScope: build.raft.app.data.RaftShellStoreScope,
): AgentLoungeOpener {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val openFailed = stringResource(Res.string.agent_lounge_open_failed)
    val latestOpenFailed by androidx.compose.runtime.rememberUpdatedState(openFailed)
    return remember(bridge, storeScope, scope) {
        val stores = build.raft.app.data.RaftSharedStoreRegistry.get(bridge)
        AgentLoungeOpener(
            scope = scope,
            networkClient = stores.networkClient(storeScope),
            openFailedMessage = { latestOpenFailed },
            onError = { message, traceId -> bridge.errorToast(message, traceId) },
        )
    }
}

/**
 * What a conversation page needs to draw itself as (or inside) an agent's Lounge, and the agent
 * DM header's Lounge toggle. Built by the route ([rememberThreadPageLounge]); null pieces are off.
 */
internal class ThreadPageLounge(
    /** The Lounge this conversation is (a channel page) or is in (a reply thread); null elsewhere. */
    val lounge: RaftAgentLounge?,
    /** The Lounge's agent's avatar, for its face. */
    val agentAvatarWireValue: String?,
    val cacheServerId: String?,
    /** Unarchive is offered only to a viewer whose server role may archive channels. */
    val canUnarchive: Boolean,
    val unarchiving: Boolean,
    val onUnarchive: () -> Unit,
    /** The Lounge face's tap: the agent's profile. */
    val onOpenAgentProfile: (() -> Unit)?,
    /** An agent DM's header: open that agent's Lounge; null hides the toggle. */
    val onOpenDmAgentLounge: (() -> Unit)?,
    val dmLoungeOpening: Boolean,
    /**
     * An archived (non-Lounge) channel's banner Unarchive, or its reply thread's. Built here, beside
     * the Lounge's, because the route's page lambda is at the JVM's capture limit.
     */
    val archivedChannel: ThreadPageArchivedChannel? = null,
) {
    val agentName: String
        get() = lounge?.displayAgentName.orEmpty()
}

/** The channel id a conversation page shows: its own (channel) or its parent's (reply thread). */
internal fun RaftThreadScreenPayload.agentLoungeLookupChannelId(): String? =
    when (surfaceKind) {
        RaftThreadSurfaceKind.Channel ->
            (streamChannelId?.takeIf { it.isNotBlank() } ?: sourceThreadId?.takeIf { it.isNotBlank() })
        RaftThreadSurfaceKind.ReplyThread -> parentChannelId?.takeIf { it.isNotBlank() }
        else -> null
    }

/**
 * A Lounge page's Mute row (web ChannelOverflowMenu for a Lounge, slock #13875): only once joined
 * ([joinedHint]: the page's own row says so) and its row is loaded; a stored mute keeps Unmute.
 */
internal fun RaftAgentLounge.agentLoungeActivityMuteState(joinedHint: Boolean = false): ThreadActivityMuteUiState? {
    val preference = activityNotificationPreference ?: return null
    val joinedHere = joined || joinedHint
    if (!preference.activityMuted && !(joinedHere && preference.activityMuteSupported)) return null
    return ThreadActivityMuteUiState(
        channelId = channelId.value,
        muted = preference.activityMuted,
        supported = preference.activityMuteSupported,
        muteFromSeq = preference.muteFromSeq,
    )
}

/** The agent of an agent DM page, or null. */
internal fun RaftThreadScreenPayload.agentLoungeDmAgentId(): String? =
    dmPeerIdentity
        ?.takeIf { surfaceKind == RaftThreadSurfaceKind.DirectMessage && it.kind == MemberKind.Agent }
        ?.memberId
        ?.value
        ?.takeIf { it.isNotBlank() }

@Composable
internal fun rememberThreadPageLounge(
    payload: RaftThreadScreenPayload,
    bridge: build.raft.app.base.BridgeModule,
    storeScope: build.raft.app.data.RaftShellStoreScope,
    flags: SharedServerFeatureFlags?,
    serverId: String?,
    serverRole: String?,
    memberAvatarResolver: RaftMemberAvatarResolver,
    /** Agents that are someone's personal assistant here (no Lounge for them). */
    personalAssistantAgentIds: Set<String>,
    onOpenConversation: (ThreadLaunchConversationIdentity) -> Unit,
    onOpenAgentProfile: (agentId: String) -> Unit,
): ThreadPageLounge {
    val lounge = rememberAgentLounge(serverId, payload.agentLoungeLookupChannelId())
    val avatar = rememberRaftMemberAvatarWireValue(memberAvatarResolver, lounge?.agentId ?: payload.agentLoungeDmAgentId())
    val enabled = rememberAgentLoungeEnabled(flags, serverId, serverRole)
    val opener = rememberAgentLoungeOpener(bridge, storeScope)
    val opening by opener.opening.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var unarchiving by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val errorStrings = localizedErrorPresentationStrings()
    val dmAgentId = payload.agentLoungeDmAgentId()
    val dmAgentName = payload.title.trim().removePrefix("@")
    val roleMayArchive = build.raft.app.model.serverPermissionsForRole(serverRole).canManageSettings
    var channelUnarchiving by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val archivedChannel =
        if (lounge == null && threadFooterShowsArchived(payload)) {
            ThreadPageArchivedChannel(
                canUnarchive = threadArchivedChannelCanUnarchive(payload, roleMayArchive),
                unarchiving = channelUnarchiving,
                onUnarchive = { onUnarchived ->
                    // A reply thread's Unarchive restores its parent channel.
                    val target = payload.agentLoungeLookupChannelId()
                    val cleanServerId = serverId?.takeIf { it.isNotBlank() }
                    if (target != null && cleanServerId != null && !channelUnarchiving) {
                        channelUnarchiving = true
                        scope.launch {
                            val stores = build.raft.app.data.RaftSharedStoreRegistry.get(bridge)
                            // The web's channelStore.unarchiveChannel: POST channels/:id/unarchive.
                            val result =
                                try {
                                    AgentLoungeApi(stores.networkClient(storeScope))
                                        .unarchive(ServerId(cleanServerId), build.raft.app.model.ChannelId(target))
                                } finally {
                                    channelUnarchiving = false
                                }
                            when (result) {
                                is DataResult.Content -> {
                                    bridge.logDebug("thread_channel_unarchived channelId=$target")
                                    // Back in the sidebar, and this page reloads with its composer.
                                    stores.sharedHomeStore(storeScope)?.dispatch(build.raft.app.data.SharedHomeIntent.Refresh)
                                    onUnarchived()
                                }
                                is DataResult.Error ->
                                    bridge.errorToast(result.error.userFacingMessage(errorStrings), result.error.traceId)
                                is DataResult.NoPermission ->
                                    bridge.errorToast(result.blocker.userFacingMessage(errorStrings), result.blocker.traceId)
                                else -> Unit
                            }
                        }
                    }
                },
            )
        } else {
            null
        }
    return ThreadPageLounge(
        archivedChannel = archivedChannel,
        lounge = lounge,
        agentAvatarWireValue = avatar,
        cacheServerId = serverId,
        canUnarchive = build.raft.app.model.serverPermissionsForRole(serverRole).canManageSettings,
        unarchiving = unarchiving,
        onUnarchive = {
            val target = lounge
            val cleanServerId = serverId?.takeIf { it.isNotBlank() }
            if (target != null && cleanServerId != null && !unarchiving) {
                unarchiving = true
                scope.launch {
                    val stores = build.raft.app.data.RaftSharedStoreRegistry.get(bridge)
                    val result =
                        try {
                            AgentLoungeApi(stores.networkClient(storeScope)).unarchive(ServerId(cleanServerId), target.channelId)
                        } finally {
                            unarchiving = false
                        }
                    // The server's own reason, as the web's LoungeUnarchiveAction shows it.
                    when (result) {
                        is DataResult.Error -> bridge.errorToast(result.error.userFacingMessage(errorStrings), result.error.traceId)
                        is DataResult.NoPermission ->
                            bridge.errorToast(result.blocker.userFacingMessage(errorStrings), result.blocker.traceId)
                        else -> Unit
                    }
                }
            }
        },
        onOpenAgentProfile = lounge?.let { { onOpenAgentProfile(it.agentId) } },
        onOpenDmAgentLounge =
            dmAgentId
                ?.takeIf { enabled && it !in personalAssistantAgentIds }
                ?.let { agentId ->
                    {
                        opener.open(serverId, agentId, dmAgentName.ifBlank { null }) { opened ->
                            onOpenConversation(opened.conversationIdentity())
                        }
                    }
                },
        dmLoungeOpening = dmAgentId != null && dmAgentId in opening,
    )
}

/**
 * A Lounge's page: no Join step (the first post joins), and posting follows what the Lounge says
 * ([RaftAgentLounge.postable]: not archived, not refused by the open answer). Unchanged elsewhere.
 */
internal fun RaftThreadScreenPayload.withAgentLoungeAccess(
    lounge: RaftAgentLounge?,
    accessResolution: ThreadAccessResolution = ThreadAccessResolution.Resolved,
): RaftThreadScreenPayload {
    if (lounge == null || surfaceKind != RaftThreadSurfaceKind.Channel) return this
    // The store closed writes for a terminal access state (NoPermission / Error): a Lounge never
    // reopens them.
    if (
        accessResolution == ThreadAccessResolution.NoPermission ||
        accessResolution == ThreadAccessResolution.Error ||
        errorCode != null
    ) {
        return this
    }
    val postable = lounge.postable
    if (joined && canSend == postable && composer.enabled == postable) return this
    return copy(joined = true, canSend = postable, composer = composer.copy(enabled = postable))
}

/** "{Name}'s Lounge" while the composer belongs to a Lounge (its placeholder names the Lounge); null elsewhere. */
internal val LocalAgentLoungeComposerName = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

/**
 * The conversation identity of the Lounge with [channelId] when this client knows it (opened,
 * loaded, or carried on an agent DM row of [directMessages]); null for anything else.
 */
internal fun agentLoungeConversationIdentityOrNull(
    serverId: String?,
    channelId: String,
    directMessages: List<HomeDirectMessageRow>,
): ThreadLaunchConversationIdentity? {
    val known = RaftAgentLounges.lounge(serverId, channelId)
    val carriedBy = directMessages.firstOrNull { it.lounge?.channelId?.value == channelId }
    val agentId = known?.agentId ?: carriedBy?.presenceMemberId?.value ?: return null
    return build.raft.app.data.HomeChannelRow(
        threadId = build.raft.app.model.ThreadId(channelId),
        channelId = build.raft.app.model.ChannelId(channelId),
        channelType = "channel",
        name = known?.channelName.orEmpty(),
        loungeAgentId = agentId,
    ).toThreadLaunchConversationIdentity()
}

/**
 * "{Name}'s Lounge" for a place known only by its channel id (an Activity / search / draft row, a
 * permalink), or null when that channel is not a Lounge this client knows. Use it where the
 * place would otherwise read `#<agent>-lounge`.
 */
internal fun agentLoungeLabelOrNull(channelId: String?): String? =
    RaftAgentLounges.loungeAnyServer(channelId)?.let { agentLoungeTitleNow(it.displayAgentName) }

/** What a Lounge's page draws in place of its composer, if anything. */
internal enum class AgentLoungeFooterKind { Archived, ReadOnly }

/**
 * [conversationLounge]: the Lounge this page is; [pageLounge]: it, or the Lounge a reply thread is
 * in. An archived Lounge page shows the archived banner; a Lounge (or a thread in it) you may not
 * post in shows the read-only composer. Null: the ordinary composer.
 */
internal fun agentLoungeFooterKind(conversationLounge: RaftAgentLounge?, pageLounge: RaftAgentLounge?): AgentLoungeFooterKind? =
    when {
        conversationLounge?.archived == true -> AgentLoungeFooterKind.Archived
        pageLounge != null && !pageLounge.postable -> AgentLoungeFooterKind.ReadOnly
        else -> null
    }

/** The Join footer: an unjoined channel, never a Lounge (its first post joins). */
internal fun threadFooterShowsJoin(joined: Boolean, conversationLounge: RaftAgentLounge?): Boolean =
    !joined && conversationLounge == null

@Composable
internal fun AgentLoungeFooter(kind: AgentLoungeFooterKind?, inputs: ThreadPageLounge?, modifier: Modifier = Modifier) {
    when (kind) {
        AgentLoungeFooterKind.Archived ->
            AgentLoungeArchivedNotice(
                canUnarchive = inputs?.canUnarchive == true,
                unarchiving = inputs?.unarchiving == true,
                onUnarchive = { inputs?.onUnarchive?.invoke() },
                modifier = modifier,
            )
        AgentLoungeFooterKind.ReadOnly -> AgentLoungeReadOnlyComposer(modifier = modifier)
        null -> Unit
    }
}
