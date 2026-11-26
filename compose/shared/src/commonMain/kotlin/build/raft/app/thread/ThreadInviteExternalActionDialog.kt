package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.compat.pager.Pager
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.JointChannelRole
import build.raft.app.data.JointChannelServer
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.isInviteExternalAction
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftMessageActionCardActions

internal const val ThreadActionCardInviteExternalOpenTag = "action-card-invite-external"
internal const val ThreadActionCardInviteExternalDeclineTag = "action-card-decline-invite"
internal const val ThreadActionCardInviteExternalReconfirmTag = "action-card-reconfirm"

/**
 * Viewer permission evaluation for a `channel:invite_external` action card (web `actionCardCommon.tsx`
 * `capabilities.federateChannels` + `channelCapabilities.federateChannels`, slock #16530 / mobile #5862).
 */
internal data class InviteExternalCardAccess(
    val canInvite: Boolean,
)

/**
 * Evaluates whether the viewer may operate an open `channel:invite_external` card on its host server:
 * - Guests never hold `federateChannels`.
 * - When the target channel is live and carries server-resolved `channelCapabilities.federateChannels`,
 *   honor it strictly.
 * - Otherwise fall back to server `owner` / `admin` role (matching web `capabilities.federateChannels`).
 * Returns null for any non-`channel:invite_external` card.
 */
internal fun evaluateInviteExternalCardAccess(
    card: ThreadActionCard,
    viewerServerRole: String?,
    targetChannel: HomeChannelRow?,
): InviteExternalCardAccess? {
    if (!card.isInviteExternalAction()) return null
    if (viewerServerRole.equals("guest", ignoreCase = true)) {
        return InviteExternalCardAccess(canInvite = false)
    }
    val channelFederate = targetChannel?.takeIf { !it.archived }?.channelCapabilities?.federateChannels
    val canInvite =
        channelFederate
            ?: (viewerServerRole.equals("owner", ignoreCase = true) || viewerServerRole.equals("admin", ignoreCase = true))
    return InviteExternalCardAccess(canInvite = canInvite)
}

/**
 * Stable lookup callback for [ThreadActionCardSpecHost.evaluateInviteExternalAccess].
 * Kept out of `SharedThreadRouteContentContent` to stay under its JVM method-size ceiling.
 */
@Composable
internal fun rememberThreadActionCardInviteExternalAccess(
    currentSession: StoredAuthSession?,
    currentServerId: String?,
    sharedHomeState: SharedHomeState,
): (ThreadActionCard) -> InviteExternalCardAccess? {
    val latestSession by rememberUpdatedState(currentSession)
    val latestServerId by rememberUpdatedState(currentServerId)
    val homeChannels =
        remember(sharedHomeState.content) {
            (sharedHomeState.content as? SharedHomeContentState.Content)?.baseSnapshot?.channels.orEmpty()
        }
    val latestChannels by rememberUpdatedState(homeChannels)
    return remember {
        { card ->
            val serverId = latestServerId
            val role = latestSession?.servers?.firstOrNull { it.id == serverId }?.role
            val targetChannelId = card.channel?.trim()?.takeIf { it.isNotEmpty() }
            val targetChannel =
                targetChannelId?.let { id ->
                    latestChannels.firstOrNull { it.channelId.value == id || it.threadId.value == id }
                }
            evaluateInviteExternalCardAccess(
                card = card,
                viewerServerRole = role,
                targetChannel = targetChannel,
            )
        }
    }
}

/**
 * Frame-drawn action buttons for a `channel:invite_external` card (web `inviteExternalCard.tsx`,
 * slock #16530, #16671):
 * - When `reconfirm_required`: a single "Reconfirm" button.
 * - Otherwise: "Invite people" (when `addresses > 0`) or "Connect servers" (when `addresses == 0`),
 *   beside "Decline". Both buttons disable while frozen or while either action is in flight.
 */
@Composable
internal fun ThreadInviteExternalActionCardButtons(
    card: ThreadActionCard,
    busyElementKey: String?,
    onOpen: () -> Unit,
    onDecline: () -> Unit,
    onReconfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workingLabel = stringResource(Res.string.thread_action_card_busy_working)
    val anyBusy = busyElementKey != null
    val openBusy = busyElementKey == ACTION_CARD_INVITE_EXTERNAL_OPEN_ELEMENT_ID
    val declineBusy = busyElementKey == ACTION_CARD_INVITE_EXTERNAL_DECLINE_ELEMENT_ID
    val reconfirmBusy = busyElementKey == ACTION_CARD_INVITE_EXTERNAL_RECONFIRM_ELEMENT_ID
    val disabled = card.isFrozen || anyBusy
    RaftMessageActionCardActions(modifier = modifier) {
        if (card.needsReconfirmation) {
            RaftButton(
                text = stringResource(Res.string.thread_action_card_reconfirm),
                onClick = onReconfirm,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                enabled = !disabled,
                loading = reconfirmBusy,
                loadingLabel = workingLabel,
                modifier = Modifier.testTag(ThreadActionCardInviteExternalReconfirmTag),
            )
        } else {
            val connectOnly = (card.externalInviteAddresses ?: 0) == 0
            RaftButton(
                text =
                    if (connectOnly) {
                        stringResource(Res.string.thread_action_card_verb_connect_own_servers)
                    } else {
                        stringResource(Res.string.thread_action_card_verb_invite_external)
                    },
                onClick = onOpen,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                enabled = !disabled,
                loading = openBusy,
                loadingLabel = workingLabel,
                modifier = Modifier.testTag(ThreadActionCardInviteExternalOpenTag),
            )
            RaftButton(
                text = stringResource(Res.string.thread_action_card_verb_decline_invite),
                onClick = onDecline,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                enabled = !disabled,
                loading = declineBusy,
                loadingLabel = workingLabel,
                modifier = Modifier.testTag(ThreadActionCardInviteExternalDeclineTag),
            )
        }
    }
}

/** Per-opening one-shot latch so `mark-executed` runs at most once per dialog opening (web `markedRef`). */
private class InviteExternalDoneOnce {
    var done: Boolean = false
}

/**
 * Pure presentation host for a `channel:invite_external` card's dialog (web `AddExternalMemberDialog`
 * opened from `InviteExternalCard`, slock #16530, #16662, #16671):
 * - When `addresses == 0` ([connectOnly] = true): renders [JointMemberConnectSheet] directly with
 *   no invite sheet beneath, no Back button, and `sayWhenEmpty` enabled.
 * - When `addresses > 0` ([connectOnly] = false): renders [ExternalInviteSheet] prefilled with the
 *   card's emails and, when the viewer has eligible connectable servers, offers the quiet
 *   "Connect your servers" footer link that stacks [JointMemberConnectSheet] over it (Back returns
 *   to the emails as typed; connecting marks the card executed).
 */
@Composable
internal fun ThreadInviteExternalActionDialogContent(
    connectOnly: Boolean,
    serverName: String,
    channelName: String,
    willBecomePrivate: Boolean,
    joint: Boolean,
    externalInvitesState: ExternalInvitesState,
    onExternalInvitesIntent: (ExternalInvitesIntent) -> Unit,
    connectState: JointChannelConnectionsState,
    onConnectIntent: (JointChannelConnectionsIntent) -> Unit,
    connectServersAvailable: Boolean,
    publicConnect: PublicChannelConnect?,
    onDismiss: () -> Unit,
    onUpgradeExternalLimit: () -> Unit,
    onUpgradeServer: (serverId: String) -> Unit,
) {
    if (connectOnly) {
        if (connectState.memberConnectSheet != null) {
            JointMemberConnectSheet(
                state = connectState,
                onDismiss = {
                    onConnectIntent(JointChannelConnectionsIntent.DismissMemberConnect)
                    onDismiss()
                },
                onBack = null,
                onSelectionChange = { onConnectIntent(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
                onSubmit = { onConnectIntent(JointChannelConnectionsIntent.SubmitMemberConnect) },
                onUpgradeServer = { targetServerId ->
                    onConnectIntent(JointChannelConnectionsIntent.DismissMemberConnect)
                    onDismiss()
                    onUpgradeServer(targetServerId)
                },
            )
        }
        return
    }
    externalInvitesState.dialog?.let { dialog ->
        ExternalInviteSheet(
            dialog = dialog,
            serverName = serverName,
            willBecomePrivate = willBecomePrivate,
            joint = joint,
            channelSlotsLeft = externalInvitesState.slotsLeft,
            channelName = channelName,
            serversHistoryHint = false,
            onConnectServers =
                if (connectServersAvailable) {
                    { onConnectIntent(JointChannelConnectionsIntent.OpenFromExternalInvite(publicChannel = publicConnect)) }
                } else {
                    null
                },
            onDismiss = {
                onExternalInvitesIntent(ExternalInvitesIntent.CloseDialog)
                onDismiss()
            },
            onTextChange = { onExternalInvitesIntent(ExternalInvitesIntent.SetText(it)) },
            onServersChange = { onExternalInvitesIntent(ExternalInvitesIntent.SetServers(it)) },
            onNext = { onExternalInvitesIntent(ExternalInvitesIntent.Next) },
            onBack = { onExternalInvitesIntent(ExternalInvitesIntent.Back) },
            onSend = { onExternalInvitesIntent(ExternalInvitesIntent.Send(makePrivate = willBecomePrivate)) },
            onUpgrade = {
                onExternalInvitesIntent(ExternalInvitesIntent.CloseDialog)
                onDismiss()
                onUpgradeExternalLimit()
            },
        )
    }
    val connectSheet = connectState.memberConnectSheet
    if (connectSheet != null) {
        val backToEmails: (() -> Unit)? =
            if (connectSheet.done.isEmpty()) {
                { onConnectIntent(JointChannelConnectionsIntent.DismissMemberConnect) }
            } else {
                null
            }
        JointMemberConnectSheet(
            state = connectState,
            onDismiss = {
                onConnectIntent(JointChannelConnectionsIntent.DismissMemberConnect)
                onDismiss()
            },
            onBack = backToEmails,
            onSelectionChange = { onConnectIntent(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
            onSubmit = { onConnectIntent(JointChannelConnectionsIntent.SubmitMemberConnect) },
            onUpgradeServer = { targetServerId ->
                onConnectIntent(JointChannelConnectionsIntent.DismissMemberConnect)
                onExternalInvitesIntent(ExternalInvitesIntent.CloseDialog)
                onDismiss()
                onUpgradeServer(targetServerId)
            },
        )
    }
}

/**
 * Route-level host mounted by [ThreadRouteDialogs] when a viewer opens a `channel:invite_external`
 * action card: owns the scoped [ExternalInvitesStore] and [JointConnectedServersHolder] for the
 * card's target channel, prefills the fetched addresses (or opens Connect your servers directly
 * when `addresses == 0`), and calls [onMarkExecuted] at most once when invites are sent or a
 * server is connected.
 */
@Composable
internal fun ThreadInviteExternalActionDialogHost(
    dialogState: ThreadActionCardSpecInviteExternalState,
    data: SharedThreadRouteContentData,
    currentServerId: String?,
    sharedHomeState: SharedHomeState,
    actionCardNames: ThreadActionCardNameLookup,
    pager: Pager,
    onMarkExecuted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val targetChannelId = dialogState.card.channel?.trim()?.takeIf { it.isNotEmpty() }
    val resolvedServerId = currentServerId?.trim()?.takeIf { it.isNotEmpty() }
    if (targetChannelId == null || resolvedServerId == null) {
        LaunchedEffect(dialogState.messageId) { onDismiss() }
        return
    }
    val sharedStores = remember(data.bridge) { RaftSharedStoreRegistry.get(data.bridge) }
    val authorityGateway = remember(sharedStores, data.storeScope) { sharedStores.authenticatedRouteAuthorityGateway(data.storeScope) }
    val liveOwner by
        remember(sharedStores, data.storeScope) { sharedStores.authenticatedRouteOwnerFlow(data.storeScope) }
            .collectAsState(sharedStores.currentAuthenticatedRouteOwner(data.storeScope))
    val routeOwner = remember(dialogState.messageId) { liveOwner }
    if (routeOwner == null || routeOwner.serverId != resolvedServerId) {
        LaunchedEffect(dialogState.messageId) { onDismiss() }
        return
    }
    val scope = rememberCoroutineScope()
    val homeChannels =
        remember(sharedHomeState.content) {
            (sharedHomeState.content as? SharedHomeContentState.Content)?.baseSnapshot?.channels.orEmpty()
        }
    val targetRow =
        remember(homeChannels, targetChannelId) {
            homeChannels.firstOrNull { it.channelId.value == targetChannelId || it.threadId.value == targetChannelId }
        }
    val isJoint = targetRow?.let { it.kind == ConversationKind.Joint || it.channelType.equals("joint", ignoreCase = true) } == true
    val isPrivate = !isJoint && targetRow?.channelType.equals("private", ignoreCase = true) == true
    val isPublic = !isJoint && !isPrivate && targetRow?.channelType.equals("channel", ignoreCase = true) == true
    val channelName =
        targetRow?.name?.removePrefix("#")?.takeIf { it.isNotBlank() }
            ?: actionCardNames.name(ThreadActionCardEntityKind.Channel, targetChannelId)?.removePrefix("#")
            ?: ""
    val sessionState by
        produceState<Pair<StoredAuthSession?, Boolean>>(null to false, data.authStore, dialogState.messageId) {
            value = data.authStore.readSession() to true
        }
    val session = sessionState.first
    val sessionLoaded = sessionState.second
    val currentServer = session?.servers?.firstOrNull { it.id == resolvedServerId }
    val serverName = currentServer?.name.orEmpty()
    val viewerEmail = session?.user?.email?.trim()?.takeIf { it.isNotEmpty() }
    val memberConnectAccount = remember(session, resolvedServerId) { session?.memberConnectAccount(resolvedServerId) }
    val connectOnly = (dialogState.card.externalInviteAddresses ?: 0) == 0
    val initialEmails = remember(dialogState.messageId, dialogState.emails) { dialogState.emails.joinToString(", ") }
    val doneOnce = remember(dialogState.messageId) { InviteExternalDoneOnce() }
    val latestOnMarkExecuted by rememberUpdatedState(onMarkExecuted)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val markDoneOnce: () -> Unit =
        remember(dialogState.messageId) {
            {
                if (!doneOnce.done) {
                    doneOnce.done = true
                    latestOnMarkExecuted()
                }
            }
        }
    val sharedHomeStore = remember(sharedStores, data.storeScope) { sharedStores.sharedHomeStore(data.storeScope) }

    val externalInvitesStore =
        remember(dialogState.messageId, targetChannelId, routeOwner, authorityGateway, scope) {
            ExternalInvitesStore(
                scope = scope,
                dataSource =
                    RealExternalInvitesDataSource(
                        channelId = ChannelId(targetChannelId),
                        owner = routeOwner,
                        gateway = authorityGateway,
                        logger = { data.bridge.log("thread_invite_external_card $it") },
                    ),
                onChannelMutated = { sharedHomeStore?.dispatch(SharedHomeIntent.Refresh) },
                onSent = {
                    markDoneOnce()
                    latestOnDismiss()
                },
            )
        }
    val externalInvitesState by externalInvitesStore.state.collectAsState()

    LaunchedEffect(externalInvitesStore, viewerEmail) {
        externalInvitesStore.dispatch(ExternalInvitesIntent.SetOwnEmail(viewerEmail))
    }
    LaunchedEffect(externalInvitesStore, dialogState.messageId, connectOnly) {
        if (!connectOnly) {
            externalInvitesStore.dispatch(
                ExternalInvitesIntent.OpenDialog(
                    joint = isJoint,
                    loadOwnServers = false,
                    initialText = initialEmails,
                )
            )
            externalInvitesStore.dispatch(ExternalInvitesIntent.Load)
        }
    }
    LaunchedEffect(externalInvitesState.effects) {
        if (externalInvitesState.effects.isEmpty()) return@LaunchedEffect
        val effects = externalInvitesState.effects
        data.bridge.postExternalInviteToasts(
            effects = effects,
            joint = isJoint,
            channel = "#$channelName",
            host = serverName,
        )
        effects.forEach { effect -> externalInvitesStore.dispatch(ExternalInvitesIntent.ConsumeEffect(effect)) }
    }

    val seedConnections =
        remember(targetRow, targetChannelId, resolvedServerId, channelName, memberConnectAccount, isJoint, isPrivate, isPublic) {
            val jointRole = JointChannelRole.fromWire(targetRow?.jointRole)
            val servers =
                if (isJoint) {
                    targetRow?.jointServers.orEmpty()
                        .map { ref ->
                            JointChannelServer(
                                serverId = ref.id,
                                name = ref.name,
                                slug = ref.slug,
                                active = true,
                                isCurrentServer = ref.id == resolvedServerId,
                                plan = null,
                                role = if (jointRole == JointChannelRole.Host && ref.id == resolvedServerId) JointChannelRole.Host else null,
                                avatarUrl = ref.avatarUrl,
                            )
                        }
                        .ifEmpty {
                            listOf(
                                JointChannelServer(
                                    serverId = resolvedServerId,
                                    name = memberConnectAccount?.currentServerName.orEmpty(),
                                    slug = memberConnectAccount?.currentServerSlug.orEmpty(),
                                    active = true,
                                    isCurrentServer = true,
                                    plan = null,
                                    role = jointRole,
                                )
                            )
                        }
                } else {
                    emptyList()
                }
            // A channel not in Home yet has no rows to choose; default to private so wording stays truthful (web AddExternalMemberDialog).
            JointChannelConnections(
                channelId = ChannelId(targetChannelId),
                channelName = channelName,
                isJoint = isJoint,
                archived = targetRow?.archived == true,
                servers = servers,
                hasCurrentServerPendingInvites = false,
                jointRole = jointRole,
                federateChannels = targetRow?.channelCapabilities?.federateChannels,
                isPrivate = if (isJoint || isPublic) false else true,
                joined = targetRow?.joined ?: true,
            )
        }
    val settingsRoute = remember(resolvedServerId, targetChannelId) { ChannelSettingsRoute(ServerId(resolvedServerId), ChannelId(targetChannelId)) }
    val connectHolder =
        rememberJointConnectedServers(
            route = settingsRoute,
            routeOwner = routeOwner,
            liveOwner = liveOwner,
            sharedStores = sharedStores,
            storeScope = data.storeScope,
            bridge = data.bridge,
            active = isJoint,
            seedConnections = seedConnections,
            onConnectionsChanged = {
                markDoneOnce()
                sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
            },
            onMemberConnectFinished = { latestOnDismiss() },
        )
    val connectState by connectHolder.store.state.collectAsState()
    val canMakePrivate =
        evaluateInviteExternalCardAccess(
            card = dialogState.card,
            viewerServerRole = currentServer?.role,
            targetChannel = targetRow,
        )?.canInvite == true
    val publicConnect = if (isPublic) PublicChannelConnect(canMakePrivate = canMakePrivate) else null
    var openedConnectOnly by remember(dialogState.messageId) { mutableStateOf(false) }

    LaunchedEffect(connectHolder.store, sessionLoaded, memberConnectAccount, seedConnections, connectOnly, publicConnect) {
        if (!connectOnly || !sessionLoaded || openedConnectOnly) return@LaunchedEffect
        connectHolder.store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(memberConnectAccount))
        connectHolder.store.dispatch(JointChannelConnectionsIntent.SeedChannel(seedConnections))
        openedConnectOnly = true
        connectHolder.store.dispatch(
            JointChannelConnectionsIntent.OpenFromExternalInvite(
                publicChannel = publicConnect,
                sayWhenEmpty = true,
            )
        )
    }

    val connectServersAvailable = (isJoint || isPrivate || isPublic) && connectState.memberConnectInviteRows.isNotEmpty()
    ThreadInviteExternalActionDialogContent(
        connectOnly = connectOnly,
        serverName = serverName,
        channelName = channelName,
        willBecomePrivate = isPublic,
        joint = isJoint,
        externalInvitesState = externalInvitesState,
        onExternalInvitesIntent = externalInvitesStore::dispatch,
        connectState = connectState,
        onConnectIntent = connectHolder.store::dispatch,
        connectServersAvailable = connectServersAvailable,
        publicConnect = publicConnect,
        onDismiss = onDismiss,
        onUpgradeExternalLimit = {
            if (settingsBillingOffersUpgrade()) {
                pager.openRaftUpgradeDialog(
                    gate = RaftUpgradeGate.ExternalChannelFreeLimit,
                    storeScope = data.storeScope,
                    externalContext =
                        externalSlotUpgradeContext(
                            channels = homeChannels,
                            serverId = resolvedServerId,
                            exceptChannelId = targetChannelId,
                        ),
                )
            } else {
                pager.openSettingsBillingPage(data.storeScope)
            }
        },
        onUpgradeServer = { targetServerId ->
            if (settingsBillingOffersUpgrade()) {
                pager.openRaftUpgradeDialog(
                    gate = RaftUpgradeGate.ExternalChannelFreeLimit,
                    storeScope = data.storeScope,
                    targetServerId = targetServerId,
                )
            } else {
                pager.openSettingsBillingPage(data.storeScope)
            }
        },
    )
}
