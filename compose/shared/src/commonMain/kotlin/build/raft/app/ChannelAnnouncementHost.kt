package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import build.raft.app.base.BridgeModule
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.CHANNEL_ANNOUNCEMENT_FEATURE_FLAG_KEY
import build.raft.app.data.CHANNEL_ANNOUNCEMENT_POLL_MILLIS
import build.raft.app.data.ChannelAnnouncementIntent
import build.raft.app.data.ChannelAnnouncementKey
import build.raft.app.data.ChannelAnnouncementMutationStatus
import build.raft.app.data.ChannelAnnouncementPickerIntent
import build.raft.app.data.ChannelAnnouncementPickerState
import build.raft.app.data.ChannelAnnouncementPickerStore
import build.raft.app.data.ChannelAnnouncementSlice
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.NetworkRaftConversationArtifactsApi
import build.raft.app.data.RaftChannelAnnouncement
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.SharedChannelAnnouncementStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.channelTypeSupportsAnnouncement
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import kotlinx.coroutines.delay

/*
 * Channel announcements wired to a page: the flag, the shared store's slice, the reads (open,
 * resume, every minute while shown, the realtime events SharedChannelAnnouncementStore names),
 * opening the Artifact in the in-app viewer and the details' set / replace / remove.
 */

/**
 * The channel whose announcement shows here, or null: the flag off for the server (or not yet
 * evaluated), no channel, or a channel that can't have one (a DM, a thread, a task).
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
 * [key]'s announcement, read when it shows and whenever it comes back ([active]: the viewer
 * closed, so a version just opened reads as seen), every [CHANNEL_ANNOUNCEMENT_POLL_MILLIS] while
 * it shows, and on the realtime events that may change it.
 */
@Composable
internal fun rememberChannelAnnouncementSlice(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    key: ChannelAnnouncementKey?,
    active: Boolean,
): ChannelAnnouncementSlice {
    val store = remember(sharedStores, storeScope) { sharedStores.channelAnnouncementStore(storeScope) }
    LaunchedEffect(store, key, active) {
        if (key == null || !active) return@LaunchedEffect
        store.dispatch(ChannelAnnouncementIntent.Load(key, "open", DataRequestPriority.Visible))
        while (true) {
            delay(CHANNEL_ANNOUNCEMENT_POLL_MILLIS)
            store.dispatch(ChannelAnnouncementIntent.Load(key, "poll", DataRequestPriority.Background))
        }
    }
    val owner = remember(sharedStores, storeScope) { sharedStores.realtimeDomainEventOwner(storeScope) }
    DisposableEffect(owner, store, key) {
        if (key == null) return@DisposableEffect onDispose {}
        val sink = RealtimeGuardedActiveThreadSink { eventServerId, event -> store.dispatch(ChannelAnnouncementIntent.Realtime(key, eventServerId, event)) }
        owner.registerGuardedActiveThreadSink(sink)
        onDispose { owner.unregisterGuardedActiveThreadSink(sink) }
    }
    if (key == null) return ChannelAnnouncementSlice.Unread
    val slice by remember(store, key) { store.slice(key) }.collectAsState(store.current(key))
    return slice
}

/** Opens an announcement in the in-app Artifact viewer (its latest version), the browser only where no page can open. */
@Composable
internal fun rememberChannelAnnouncementOpener(webBaseUrl: String): (RaftChannelAnnouncement) -> Unit {
    val uriHandler = LocalUriHandler.current
    val opener = rememberRaftArtifactOpener { uriHandler.openUri(artifactWebUrl(webBaseUrl, it)) }
    return remember(opener) { { announcement -> opener.open(ArtifactLinkTarget(announcement.artifactId), null, "channel_announcement") } }
}

/** The Developer environment's web URL (AGENTS.md "Environment Web Link URL Rule"), as the Artifact viewer reads it. */
internal fun channelAnnouncementWebBaseUrl(sharedStores: RaftSharedStoreProvider): String =
    sharedSettingsResolvedWebBaseUrl(standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore))

/**
 * The conversation's strip, or nothing: the flag off, a DM or thread, no announcement, or not yet
 * read. Copy link copies the Artifact's web link; Announcement settings ([onOpenSettings]) only
 * for who may change it.
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
) {
    val slice = rememberChannelAnnouncementSlice(sharedStores, storeScope, key, active)
    val announcement = slice.announcement ?: return
    val webBaseUrl = remember(sharedStores) { channelAnnouncementWebBaseUrl(sharedStores) }
    val open = rememberChannelAnnouncementOpener(webBaseUrl)
    val copiedMessage = stringResource(Res.string.channel_announcement_strip_link_copied)
    ChannelAnnouncementStrip(
        announcement = announcement,
        authorName = channelAnnouncementAuthorName(announcement.updatedBy.name),
        onOpen = { open(announcement) },
        onCopyLink = {
            bridge.copyToPasteboard(artifactWebUrl(webBaseUrl, ArtifactLinkTarget(announcement.artifactId)))
            bridge.toast(copiedMessage)
        },
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

/** Which dialog of the details' Announcement row is open (web `Step`). */
private sealed interface ChannelAnnouncementStep {
    data object Idle : ChannelAnnouncementStep

    data class Pick(val replacing: Boolean) : ChannelAnnouncementStep

    data class ConfirmReplace(val artifactId: String, val title: String) : ChannelAnnouncementStep

    data object ConfirmRemove : ChannelAnnouncementStep
}

/**
 * Channel details › Announcement, wired: the row, the picker, the confirmations, and the change
 * through the shared store (a refusal stays in its dialog with the server's sentence and the
 * trace id; success closes it).
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
) {
    val slice = rememberChannelAnnouncementSlice(sharedStores, storeScope, key, active)
    if (key == null) return
    val store: SharedChannelAnnouncementStore = remember(sharedStores, storeScope) { sharedStores.channelAnnouncementStore(storeScope) }
    val mutation by remember(store, key) { store.mutation(key) }.collectAsState(null)
    var step by remember(key) { mutableStateOf<ChannelAnnouncementStep>(ChannelAnnouncementStep.Idle) }
    val webBaseUrl = remember(sharedStores) { channelAnnouncementWebBaseUrl(sharedStores) }
    val open = rememberChannelAnnouncementOpener(webBaseUrl)
    val name = channelName.removePrefix("#")

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

    ChannelAnnouncementDetailsRow(
        slice = slice,
        canEdit = canEdit,
        onOpen = { slice.announcement?.let(open) },
        onSet = { step = ChannelAnnouncementStep.Pick(replacing = false) },
        onReplace = { step = ChannelAnnouncementStep.Pick(replacing = true) },
        onRemove = { step = ChannelAnnouncementStep.ConfirmRemove },
        modifier = modifier,
    )

    when (val current = step) {
        ChannelAnnouncementStep.Idle -> Unit
        is ChannelAnnouncementStep.Pick ->
            ChannelAnnouncementPickerRoute(
                sharedStores = sharedStores,
                storeScope = storeScope,
                key = key,
                channelName = name,
                currentId = slice.announcement?.artifactId,
                replacing = current.replacing,
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
        is ChannelAnnouncementStep.ConfirmReplace ->
            ChannelAnnouncementConfirmDialog(
                title = stringResource(Res.string.channel_announcement_replace_title),
                body =
                    stringResource(
                        Res.string.channel_announcement_replace_body,
                        current.title,
                        slice.announcement?.title.orEmpty(),
                        name,
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
                body = stringResource(Res.string.channel_announcement_remove_body, slice.announcement?.title.orEmpty(), name),
                confirmLabel = stringResource(Res.string.channel_announcement_details_remove),
                confirmTag = ChannelAnnouncementTestTags.RemoveConfirm,
                running = running,
                error = error,
                onConfirm = { store.dispatch(ChannelAnnouncementIntent.Change(key, null)) },
                onDismiss = close,
            )
    }
}

@Composable
private fun ChannelAnnouncementPickerRoute(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    key: ChannelAnnouncementKey,
    channelName: String,
    currentId: String?,
    replacing: Boolean,
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
    )
}
