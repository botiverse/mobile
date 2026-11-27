package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key as composeKey
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import build.raft.app.base.BridgeModule
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.CHANNEL_ANNOUNCEMENT_FEATURE_FLAG_KEY
import build.raft.app.data.ChannelAnnouncementIntent
import build.raft.app.data.ChannelAnnouncementKey
import build.raft.app.data.ChannelAnnouncementMutationStatus
import build.raft.app.data.ChannelAnnouncementPickerIntent
import build.raft.app.data.ChannelAnnouncementPickerState
import build.raft.app.data.ChannelAnnouncementPickerStore
import build.raft.app.data.ChannelAnnouncementSlice
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.NetworkRaftConversationArtifactsApi
import build.raft.app.data.RaftChannelAnnouncement
import build.raft.app.data.RaftConversationHeaderAction
import build.raft.app.data.RaftConversationHeaderKind
import build.raft.app.data.RaftConversationHeaderSurface
import build.raft.app.data.RaftFreshnessEvent
import build.raft.app.data.RaftFreshnessGate
import build.raft.app.data.RaftFreshnessSignal
import build.raft.app.data.RaftFreshnessTrigger
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedChannelAnnouncementStore
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.answersFor
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.channelTypeSupportsAnnouncement
import build.raft.app.data.raftForegroundGateNowMillis
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.app.settings.standaloneSettingsWebBaseUrl
import build.raft.app.shell.raftWindowWide
import build.raft.app.shell.rememberRaftWebPageOpener
import build.raft.app.thread.dispatch
import build.raft.app.thread.key
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/*
 * Channel announcements wired to a page: the flag, the shared store's slice, what the page tells the
 * store (it shows a channel, it is in front again, the socket is back; the store decides what reads,
 * SharedChannelAnnouncementStore.kt), opening the Artifact in the in-app viewer and the details'
 * set / replace / remove.
 */

/**
 * The channel or DM whose announcement shows here, or null: the flag off for the server (or not
 * yet evaluated), no channel, or a conversation that can't have one (a reply thread, a task).
 */
@Composable
internal fun rememberChannelAnnouncementKey(
    flags: SharedServerFeatureFlags?,
    serverId: String?,
    channelId: String?,
    channelType: String?,
): ChannelAnnouncementKey? {
    val cleanServerId = serverId?.takeIf { it.isNotBlank() }
    val enabled = rememberServerFeatureLatched(flags, cleanServerId?.let(::ServerId), CHANNEL_ANNOUNCEMENT_FEATURE_FLAG_KEY)
    val cleanChannelId = channelId?.takeIf { it.isNotBlank() }
    return remember(enabled, cleanServerId, cleanChannelId, channelType) {
        if (!enabled || cleanServerId == null || cleanChannelId == null || !channelTypeSupportsAnnouncement(channelType)) {
            null
        } else {
            ChannelAnnouncementKey(cleanServerId, cleanChannelId)
        }
    }
}

/**
 * The conversation's wire type for pinned-content gating (botiverse/slock 05ad55edb):
 * - DM (`surfaceKind == DirectMessage`): `"dm"` (covers both human DMs and agent DMs).
 * - Channel (`surfaceKind == Channel`, not a task conversation): [channelKind] (`"channel"`,
 *   `"private"`, `"joint"`).
 * - Reply threads and task conversations: null (never have pinned content).
 */
internal fun threadAnnouncementChannelType(
    surfaceKind: String,
    channelKind: String?,
    isTaskConversation: Boolean,
): String? =
    when {
        isTaskConversation -> null
        surfaceKind == RaftThreadSurfaceKind.DirectMessage -> "dm"
        surfaceKind == RaftThreadSurfaceKind.Channel -> channelKind
        else -> null
    }

/**
 * Who may set, replace or remove pinned content (web ChatPanel `canEditAnnouncement`,
 * ChannelOverflowMenu `canEdit={!channel?.archivedAt}`, botiverse/slock 05ad55edb):
 * - In a DM (`channelType == "dm"`): either participant while the DM is not archived (no
 *   `editChannelMetadata` needed).
 * - In a channel (`"channel"`, `"private"`, `"joint"`): `editChannelMetadata` and not archived.
 * - Unsupported types (threads, tasks, null): false.
 */
internal fun canEditChannelAnnouncement(
    channelType: String?,
    snapshot: HomeSnapshot?,
    serverId: String,
    channelId: String,
    channelArchivedFallback: Boolean = false,
    viewerAuthority: build.raft.app.model.ChannelViewerAuthorityView? = null,
): Boolean {
    if (!channelTypeSupportsAnnouncement(channelType)) return false
    val matchingSnapshot = snapshot?.takeIf { it.server.id.value == serverId }
    if (channelType == "dm") {
        val dmArchived =
            matchingSnapshot?.directMessages?.firstOrNull { it.channelId.value == channelId }?.archived
                ?: channelArchivedFallback
        return !dmArchived
    }
    val channel = matchingSnapshot?.channels?.firstOrNull { it.channelId.value == channelId }
    val archived = channel?.archived ?: channelArchivedFallback
    if (archived) return false
    if (viewerAuthority != null) {
        return build.raft.app.data.authorityAllows(viewerAuthority) { it.editChannelMetadata }
    }
    return channel?.channelCapabilities?.editChannelMetadata == true
}

/**
 * [key]'s announcement while it shows here. The page tells the store that it shows the channel
 * (the open read), that it is in front again ([active] false, then true: the app back from the
 * background, or a page over it closed) and that the socket of its server is back (kept until the
 * page is in front, as the web holds its reconnect reload while hidden). The store decides what
 * reads, and reads on the realtime events itself (botiverse/slock #16289).
 */
@Composable
internal fun rememberChannelAnnouncementSlice(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    key: ChannelAnnouncementKey?,
    active: Boolean,
): ChannelAnnouncementSlice {
    val store = remember(sharedStores, storeScope) { sharedStores.channelAnnouncementStore(storeScope) }
    DisposableEffect(store, key) {
        if (key == null) return@DisposableEffect onDispose {}
        store.dispatch(ChannelAnnouncementIntent.Open(key))
        onDispose { store.dispatch(ChannelAnnouncementIntent.Close(key)) }
    }
    // In front again after it was not; the first time it shows is the open.
    val front = remember(store, key) { ChannelAnnouncementFront() }
    DisposableEffect(store, key, active) {
        if (key != null && !active) {
            front.leftSinceShown = true
        } else if (key != null && front.leftSinceShown) {
            front.leftSinceShown = false
            store.dispatch(ChannelAnnouncementIntent.Visible(key))
        }
        onDispose {}
    }
    ChannelAnnouncementReconnectEffect(store, key, active)
    if (key == null) return ChannelAnnouncementSlice.Unread
    val slice by remember(store, key) { store.slice(key) }.collectAsState(store.current(key))
    return slice
}

/** Whether the page has been out of front since it last showed the channel; nothing draws from it. */
private class ChannelAnnouncementFront {
    var leftSinceShown = false
}

/**
 * The socket of [key]'s server back (a new connection, RaftFreshnessSignal): the store reads [key] again
 * (rule 12), after its jitter when the socket came back by itself after a drop the app did not cause
 * ([RaftFreshnessEvent.afterDrop]). One that came while the page was not in front reads when it is, at
 * once, as the web runs its held reconnect reload when the page shows again. Keeps the full
 * [RaftFreshnessEvent], not only its trigger.
 */
@Composable
private fun ChannelAnnouncementReconnectEffect(
    store: SharedChannelAnnouncementStore,
    key: ChannelAnnouncementKey?,
    active: Boolean,
) {
    val serverId = key?.serverId
    val coroutineScope = rememberCoroutineScope()
    val gate =
        remember(serverId) {
            RaftFreshnessGate(
                foreground = false,
                nowMillis = ::raftForegroundGateNowMillis,
                trackOutcome = true,
            )
        }
    val latestKey by rememberUpdatedState(key)
    val latestActive by rememberUpdatedState(active)
    fun dispatchReconnect(target: ChannelAnnouncementKey, afterDrop: Boolean) {
        store.dispatch(
            ChannelAnnouncementIntent.Reconnected(
                key = target,
                afterDrop = afterDrop,
                onCompleted = { succeeded ->
                    coroutineScope.launch {
                        val followUp = gate.onCatchUpCompleted(succeeded = succeeded, visible = latestActive)
                        if (followUp != null) {
                            val current = latestKey
                            if (current != null && current.serverId == serverId) {
                                dispatchReconnect(current, afterDrop = false)
                            } else {
                                gate.onCatchUpCompleted(succeeded = false, visible = false)
                            }
                        }
                    }
                },
            )
        )
    }
    LaunchedEffect(gate, store) {
        if (serverId == null) return@LaunchedEffect
        // Collected on the composition's (main) dispatcher: the gate is confined to it.
        RaftFreshnessSignal.events.filter { it.trigger == RaftFreshnessTrigger.Reconnect }.collect { event ->
            val shown = latestKey ?: return@collect
            if (shown.serverId != serverId) return@collect
            gate.onEvent(event, serverId, latestActive)?.let {
                dispatchReconnect(shown, afterDrop = event.afterDrop)
            }
        }
    }
    LaunchedEffect(gate, store, active) {
        val shown = latestKey ?: return@LaunchedEffect
        if (shown.serverId != serverId) return@LaunchedEffect
        gate.onVisibilityChanged(active, serverId)?.let { dispatchReconnect(shown, afterDrop = false) }
    }
}

/**
 * The in-app viewer shows [artifactId] of [serverId] until it leaves it (closed, or moved to another
 * Artifact): then a channel shown whose announcement it is reads at once, as seen now (web: the
 * viewer's read of the latest version invalidates the Artifact's summaries, #16289 row 7). Only a
 * store a page has made: no page shows a channel otherwise.
 */
@Composable
internal fun ChannelAnnouncementViewedEffect(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: String?,
    artifactId: String,
) {
    DisposableEffect(sharedStores, storeScope, serverId, artifactId) {
        onDispose {
            if (serverId != null) {
                sharedStores.existingChannelAnnouncementStore(storeScope)?.dispatch(ChannelAnnouncementIntent.ArtifactViewed(serverId, artifactId))
            }
        }
    }
}

/** Opens an announcement in the in-app Artifact viewer (its latest version), the browser only where no page can open. */
@Composable
internal fun rememberChannelAnnouncementOpener(webBaseUrl: String): (RaftChannelAnnouncement) -> Unit {
    val openArtifactWebPage = rememberRaftWebPageOpener()
    val opener = rememberRaftArtifactOpener { openArtifactWebPage(artifactWebUrl(webBaseUrl, it)) }
    return remember(opener) { { announcement -> opener.open(ArtifactLinkTarget(announcement.artifactId), null, "channel_announcement") } }
}

/** The Developer environment's web URL (AGENTS.md "Environment Web Link URL Rule"), as the Artifact viewer reads it. */
internal fun channelAnnouncementWebBaseUrl(sharedStores: RaftSharedStoreProvider): String =
    sharedSettingsResolvedWebBaseUrl(standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore))

/**
 * Web `channelRowHasAnnouncement` (slock #17116): resolves the Home snapshot's announcement hint
 * for [channelId], returning `null` only when the row is absent from [snapshot] so callers can
 * fall back to the conversation directory without letting a stale directory override an
 * authoritative `false` on the live snapshot.
 */
internal fun homeSnapshotHasAnnouncementHintOrNull(
    snapshot: HomeSnapshot?,
    serverId: String,
    channelId: String,
    channelType: String?,
): Boolean? {
    if (!channelTypeSupportsAnnouncement(channelType)) return false
    val matching = snapshot?.takeIf { it.server.id.value == serverId } ?: return null
    if (channelType == "dm") {
        val dm = matching.directMessages.firstOrNull { it.channelId.value == channelId } ?: return null
        return dm.hasAnnouncement
    }
    val row = matching.channels.firstOrNull { it.channelId.value == channelId } ?: return null
    return row.loungeAgentId.isNullOrBlank() && row.hasAnnouncement
}

internal fun homeSnapshotHasAnnouncementHint(
    snapshot: HomeSnapshot?,
    serverId: String,
    channelId: String,
    channelType: String?,
): Boolean = homeSnapshotHasAnnouncementHintOrNull(snapshot, serverId, channelId, channelType) == true

/** Web `useChannelAnnouncementPending` (slock #17116): reserve 40dp while unread, hinted, and not failed. */
internal fun shouldReserveChannelAnnouncementStrip(
    key: ChannelAnnouncementKey?,
    slice: ChannelAnnouncementSlice,
    hasAnnouncementHint: Boolean,
): Boolean = key != null && hasAnnouncementHint && !slice.read && !slice.failed

/**
 * The conversation's strip, its 40dp reserved placeholder while loading a hinted announcement, or
 * nothing: the flag off, a thread or task, no announcement, or failed. Copy link copies the
 * Artifact's web link; Pinned content settings ([onOpenSettings]) only for who may change it.
 */
@Composable
internal fun ThreadChannelAnnouncementStrip(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    bridge: BridgeModule,
    key: ChannelAnnouncementKey?,
    active: Boolean,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    conversation: RaftConversationHeaderKind = RaftConversationHeaderKind.Channel,
    hasAnnouncementHint: Boolean = false,
) {
    val slice = rememberChannelAnnouncementSlice(sharedStores, storeScope, key, active)
    val announcement = slice.announcement
    if (announcement == null) {
        if (shouldReserveChannelAnnouncementStrip(key, slice, hasAnnouncementHint)) {
            composeKey(key) {
                ChannelAnnouncementStripPlaceholder(modifier = modifier)
            }
        }
        return
    }
    val webBaseUrl = remember(sharedStores) { channelAnnouncementWebBaseUrl(sharedStores) }
    val open = rememberChannelAnnouncementOpener(webBaseUrl)
    val copiedMessage = stringResource(Res.string.channel_announcement_strip_link_copied)
    val compact = !raftWindowWide()
    ChannelAnnouncementStrip(
        announcement = announcement,
        authorName = channelAnnouncementAuthorName(announcement.updatedBy.name),
        onOpen = {
            RaftProductEvents.conversationHeaderActionSelected(
                action = RaftConversationHeaderAction.OpenAnnouncement,
                surface = RaftConversationHeaderSurface.Announcement,
                conversation = conversation,
                compact = compact,
            )
            open(announcement)
        },
        onCopyLink = {
            RaftProductEvents.conversationHeaderActionSelected(
                action = RaftConversationHeaderAction.CopyLink,
                surface = RaftConversationHeaderSurface.Announcement,
                conversation = conversation,
                compact = compact,
            )
            bridge.copyToPasteboard(artifactWebUrl(webBaseUrl, ArtifactLinkTarget(announcement.artifactId)))
            bridge.toast(copiedMessage)
        },
        onOpenSettings =
            onOpenSettings?.let { openSettings ->
                {
                    RaftProductEvents.conversationHeaderActionSelected(
                        action = RaftConversationHeaderAction.OpenAnnouncementSettings,
                        surface = RaftConversationHeaderSurface.Announcement,
                        conversation = conversation,
                        compact = compact,
                    )
                    openSettings()
                }
            },
        modifier = modifier,
        onOpenMenu = {
            RaftProductEvents.conversationHeaderActionSelected(
                action = RaftConversationHeaderAction.OpenMenu,
                surface = RaftConversationHeaderSurface.Announcement,
                conversation = conversation,
                compact = compact,
            )
        },
    )
}

/**
 * The conversation page's strip as a slot, or null where none can show (the flag off, a reply
 * thread or task conversation). Kept out of the route composable, which is at its size limit.
 */
@Composable
internal fun rememberThreadChannelAnnouncementStripSlot(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    bridge: BridgeModule,
    serverId: String?,
    channelId: String?,
    channelType: String?,
    active: Boolean,
    channelArchived: Boolean = false,
): (@Composable (onOpenSettings: () -> Unit) -> Unit)? {
    val key = rememberChannelAnnouncementKey(sharedStores.serverFeatureFlags, serverId, channelId, channelType) ?: return null
    val homeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val homeState by remember(homeStore) { homeStore?.state ?: kotlinx.coroutines.flow.MutableStateFlow(null) }.collectAsState()
    val snapshot = (homeState?.content as? SharedHomeContentState.Content)?.snapshot
    val observeChannelAuthority = channelType != "dm"
    val viewerAuthority by
        remember(sharedStores, key, observeChannelAuthority) {
            if (observeChannelAuthority) {
                sharedStores.channelAuthorityStore.observe(key.serverId, key.channelId)
            } else {
                kotlinx.coroutines.flow.MutableStateFlow<build.raft.app.model.ChannelViewerAuthorityView>(
                    build.raft.app.model.ChannelViewerAuthorityView.Loading
                )
            }
        }.collectAsState(
            if (observeChannelAuthority) {
                sharedStores.channelAuthorityStore.viewOf(key.serverId, key.channelId)
            } else {
                build.raft.app.model.ChannelViewerAuthorityView.Loading
            }
        )
    LaunchedEffect(sharedStores, key, observeChannelAuthority, viewerAuthority) {
        if (observeChannelAuthority && viewerAuthority is build.raft.app.model.ChannelViewerAuthorityView.Loading) {
            sharedStores.channelAuthorityStore.requestChannelAuthority(key.serverId, key.channelId)
        }
    }
    val canEdit =
        canEditChannelAnnouncement(
            channelType = channelType,
            snapshot = snapshot,
            serverId = key.serverId,
            channelId = key.channelId,
            channelArchivedFallback = channelArchived,
            viewerAuthority = viewerAuthority.takeIf { observeChannelAuthority },
        )
    val conversation = if (channelType == "dm") RaftConversationHeaderKind.Dm else RaftConversationHeaderKind.Channel
    val directoryFlow =
        remember(sharedStores, storeScope, key.serverId) {
            sharedStores.conversationIdentityProvider(storeScope)
                ?.observeServer(build.raft.app.model.ServerId(key.serverId))
                ?: kotlinx.coroutines.flow.flowOf(SharedConversationDirectoryState())
        }
    val directoryState by directoryFlow.collectAsState(SharedConversationDirectoryState())
    val hasAnnouncementHint =
        homeSnapshotHasAnnouncementHintOrNull(snapshot, key.serverId, key.channelId, channelType)
            ?: (
                channelTypeSupportsAnnouncement(channelType) &&
                    directoryState.resolve(key.channelId)?.takeIf {
                        it.serverId == key.serverId && it.loungeAgentId.isNullOrBlank()
                    }?.hasAnnouncement == true
                )
    val latestActive by rememberUpdatedState(active)
    val latestCanEdit by rememberUpdatedState(canEdit)
    val latestConversation by rememberUpdatedState(conversation)
    val latestHasAnnouncementHint by rememberUpdatedState(hasAnnouncementHint)
    return remember(sharedStores, storeScope, bridge, key) {
        { onOpenSettings ->
            ThreadChannelAnnouncementStrip(
                sharedStores = sharedStores,
                storeScope = storeScope,
                bridge = bridge,
                key = key,
                active = latestActive,
                onOpenSettings = if (latestCanEdit) onOpenSettings else null,
                conversation = latestConversation,
                hasAnnouncementHint = latestHasAnnouncementHint,
            )
        }
    }
}

/** Which dialog of the details' Announcement row is open (web `Step`). */
private sealed interface ChannelAnnouncementStep {
    data object Idle : ChannelAnnouncementStep

    data class Pick(val replacing: Boolean) : ChannelAnnouncementStep

    data class ConfirmReplace(val artifactId: String, val title: String) : ChannelAnnouncementStep

    data object ConfirmRemove : ChannelAnnouncementStep
}

/**
 * Channel or DM details › Pinned content, wired: the row, the picker, the confirmations, and the
 * change through the shared store (a refusal stays in its dialog with the server's sentence and
 * the trace id; success closes it).
 */
@Composable
internal fun ChannelSettingsAnnouncementSection(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    key: ChannelAnnouncementKey?,
    channelName: String,
    canEdit: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    isDm: Boolean = false,
    onOpened: (() -> Unit)? = null,
) {
    val slice = rememberChannelAnnouncementSlice(sharedStores, storeScope, key, active)
    if (key == null) return
    val store: SharedChannelAnnouncementStore = remember(sharedStores, storeScope) { sharedStores.channelAnnouncementStore(storeScope) }
    val mutation by remember(store, key) { store.mutation(key) }.collectAsState(null)
    var step by remember(key) { mutableStateOf<ChannelAnnouncementStep>(ChannelAnnouncementStep.Idle) }
    val webBaseUrl = remember(sharedStores) { channelAnnouncementWebBaseUrl(sharedStores) }
    val open = rememberChannelAnnouncementOpener(webBaseUrl)
    val name = if (isDm) channelName else channelName.removePrefix("#")

    // A change that went through closes its dialog; a refusal stays in it.
    val finished = mutation
    LaunchedEffect(finished?.id, finished?.status) {
        if (finished != null && finished.status == ChannelAnnouncementMutationStatus.Succeeded) {
            step = ChannelAnnouncementStep.Idle
            store.dispatch(ChannelAnnouncementIntent.ConsumeMutation(key, finished.id))
        }
    }
    val close: () -> Unit = {
        finished?.takeIf { it.status != ChannelAnnouncementMutationStatus.Running }?.let {
            store.dispatch(ChannelAnnouncementIntent.ConsumeMutation(key, it.id))
        }
        step = ChannelAnnouncementStep.Idle
    }
    val running = finished?.status == ChannelAnnouncementMutationStatus.Running
    val error = (finished?.status as? ChannelAnnouncementMutationStatus.Failed)?.error
    // Web `useChannelAnnouncementKind`: resolve the Artifact kind from object summaries;
    // falls back to Markdown (document icon) until the batched summary arrives.
    val artifactId = slice.announcement?.artifactId
    val artifactVersion = slice.announcement?.version
    val artifactTargets = remember(artifactId) { artifactId?.let { listOf(ArtifactLinkTarget(it)) }.orEmpty() }
    val summaryStore = remember(sharedStores, artifactTargets.isNotEmpty()) {
        if (artifactTargets.isEmpty()) null else sharedStores.artifactSummaryStore
    }
    LaunchedEffect(summaryStore, key.serverId, artifactTargets, artifactVersion) {
        if (summaryStore != null && artifactTargets.isNotEmpty()) {
            summaryStore.request(key.serverId, artifactTargets)
        }
    }
    val initialSummaryAnswers = remember(summaryStore, key.serverId, artifactTargets) {
        if (summaryStore == null || artifactTargets.isEmpty()) {
            emptyMap<String, ArtifactSummaryAnswer>()
        } else {
            summaryStore.state.value.answersFor(key.serverId, artifactTargets)
        }
    }
    val summaryAnswers by remember(summaryStore, key.serverId, artifactTargets) {
        if (summaryStore == null || artifactTargets.isEmpty()) {
            flowOf(emptyMap<String, ArtifactSummaryAnswer>())
        } else {
            summaryStore.answersFlow(key.serverId, artifactTargets)
        }
    }.collectAsState(initialSummaryAnswers)
    val artifactKind =
        artifactTargets.firstOrNull()?.let { target ->
            (summaryAnswers[target.key] as? ArtifactSummaryAnswer.Ok)?.summary?.kind
        } ?: ArtifactKind.Markdown

    val unavailable = slice.unavailable != null
    ChannelAnnouncementDetailsRow(
        slice = slice,
        canEdit = canEdit,
        onOpen = {
            slice.announcement?.let { announcement ->
                open(announcement)
                onOpened?.invoke()
            }
        },
        onSet = { if (!unavailable) step = ChannelAnnouncementStep.Pick(replacing = false) },
        onReplace = { step = ChannelAnnouncementStep.Pick(replacing = true) },
        onRemove = { step = ChannelAnnouncementStep.ConfirmRemove },
        modifier = modifier,
        artifactKind = artifactKind,
    )

    when (val current = step) {
        ChannelAnnouncementStep.Idle -> Unit
        is ChannelAnnouncementStep.Pick ->
            if (!unavailable) {
                ChannelAnnouncementPickerRoute(
                    sharedStores = sharedStores,
                    storeScope = storeScope,
                    key = key,
                    channelName = name,
                    currentId = slice.announcement?.artifactId,
                    replacing = current.replacing,
                    isDm = isDm,
                    running = running,
                    error = error.takeIf { !current.replacing },
                    onConfirm = { choice ->
                        if (current.replacing) {
                            step = ChannelAnnouncementStep.ConfirmReplace(choice.artifactId, choice.title)
                        } else {
                            store.dispatch(ChannelAnnouncementIntent.Change(key, choice.artifactId))
                        }
                    },
                    onDismiss = close,
                )
            }
        is ChannelAnnouncementStep.ConfirmReplace ->
            ChannelAnnouncementConfirmDialog(
                title = stringResource(Res.string.channel_announcement_replace_title),
                body =
                    channelAnnouncementReplaceBody(
                        nextTitle = current.title,
                        currentTitle = slice.announcement?.title.orEmpty(),
                        channelName = name,
                        isDm = isDm,
                    ),
                confirmLabel = stringResource(Res.string.channel_announcement_details_replace),
                confirmTag = ChannelAnnouncementTestTags.ReplaceConfirm,
                running = running,
                error = error,
                onConfirm = { store.dispatch(ChannelAnnouncementIntent.Change(key, current.artifactId)) },
                onDismiss = close,
            )
        ChannelAnnouncementStep.ConfirmRemove ->
            ChannelAnnouncementConfirmDialog(
                title = stringResource(Res.string.channel_announcement_remove_title),
                body =
                    channelAnnouncementRemoveBody(
                        currentTitle = slice.announcement?.title.orEmpty(),
                        channelName = name,
                        isDm = isDm,
                    ),
                confirmLabel = stringResource(Res.string.channel_announcement_details_remove),
                confirmTag = ChannelAnnouncementTestTags.RemoveConfirm,
                running = running,
                error = error,
                onConfirm = { store.dispatch(ChannelAnnouncementIntent.Change(key, null)) },
                onDismiss = close,
            )
    }
}

/**
 * DM details › Pinned content (web `ChannelOverflowMenu` `isDmChannel`, botiverse/slock 05ad55edb):
 * shown above Your preferences when `channel_announcement_v0` is on; either participant may pin
 * while the DM is not archived. Never shown for an Agent Lounge.
 */
@Composable
internal fun DirectMessageDetailsAnnouncementSection(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: String?,
    channelId: String?,
    peerName: String,
    archived: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onOpened: (() -> Unit)? = null,
) {
    val key = rememberChannelAnnouncementKey(sharedStores.serverFeatureFlags, serverId, channelId, "dm") ?: return
    ChannelSettingsAnnouncementSection(
        sharedStores = sharedStores,
        storeScope = storeScope,
        key = key,
        channelName = peerName,
        canEdit = !archived,
        active = active,
        modifier = modifier,
        isDm = true,
        onOpened = onOpened,
    )
}

@Composable
private fun ChannelAnnouncementPickerRoute(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    key: ChannelAnnouncementKey,
    channelName: String,
    currentId: String?,
    replacing: Boolean,
    isDm: Boolean,
    running: Boolean,
    error: build.raft.app.data.ScreenError?,
    onConfirm: (ChannelAnnouncementChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val picker =
        remember(sharedStores, storeScope, key) {
            ChannelAnnouncementPickerStore(
                api = NetworkRaftConversationArtifactsApi(sharedStores.networkClient(storeScope)),
                serverId = key.serverId,
                channelId = key.channelId,
                scope = coroutineScope,
            )
        }
    LaunchedEffect(picker) { picker.dispatch(ChannelAnnouncementPickerIntent.Load) }
    val state by picker.state.collectAsState()
    val view =
        when (state.status) {
            ChannelAnnouncementPickerState.Status.Loading -> ChannelAnnouncementPickerView.Loading
            ChannelAnnouncementPickerState.Status.Failed -> ChannelAnnouncementPickerView.Failed
            ChannelAnnouncementPickerState.Status.Ready ->
                ChannelAnnouncementPickerView.Ready(
                    choices =
                        state.choicesExcept(currentId).map { artifact ->
                            ChannelAnnouncementChoice(
                                artifactId = artifact.summary.id,
                                title = artifact.summary.title,
                                // Web: `v3 · <date>`.
                                meta =
                                    stringResource(Res.string.artifacts_card_version, artifact.summary.latestVersion) +
                                        " · " + raftRelativeTimestampLabel(artifact.updatedAt),
                                kind = artifact.summary.kind,
                            )
                        },
                    hasMore = state.nextCursor != null,
                    loadingMore = state.loadingMore,
                )
        }
    val latestConfirm by rememberUpdatedState(onConfirm)
    ChannelAnnouncementPickerDialog(
        replacing = replacing,
        channelName = channelName,
        view = view,
        running = running,
        error = error,
        onRetry = { picker.dispatch(ChannelAnnouncementPickerIntent.Load) },
        onShowMore = { picker.dispatch(ChannelAnnouncementPickerIntent.ShowMore) },
        onConfirm = { latestConfirm(it) },
        onDismiss = onDismiss,
        isDm = isDm,
    )
}
