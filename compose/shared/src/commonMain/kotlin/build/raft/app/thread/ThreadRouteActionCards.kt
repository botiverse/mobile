package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.base.BridgeModule
import build.raft.app.compat.pager.Pager
import build.raft.app.data.DataResult
import build.raft.app.data.RaftManagedMcpConnectApiClient
import build.raft.app.data.RaftManagedMcpConnectNetworkApi
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftRatingPrompt
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpDialogState
import build.raft.app.data.SecondFactorStepUpRepository
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadActionCardHandledBy
import build.raft.app.data.featureFlagClientOs
import build.raft.app.data.raftAppActionCardCapabilities
import build.raft.app.model.ServerId
import build.raft.app.settings.currentServerSummary
import build.raft.app.settings.openRaftOwnWebPage
import build.raft.app.settings.openSettingsBillingPage
import build.raft.app.settings.openSettingsFeedbackPage
import build.raft.app.raftOwnWebUrlForLog
import build.raft.app.raftPlatformFollowsAppStoreBillingRules
import build.raft.app.settings.raftUrlIsAppStoreRestrictedPurchaseUrl
import build.raft.app.settings.raftUrlIsOwnBillingPath
import build.raft.app.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the page's action cards hand back to it: the step-up, the install dialog and the spec-card store and host. */
internal class ThreadRouteActionCards(
    val actionCardStepUp: SecondFactorStepUpCoordinator,
    val actionCardStepUpStateHolder: State<SecondFactorStepUpDialogState?>,
    val installAppDialogStore: ThreadInstallAppActionDialogStore,
    val installAppDialogStateHolder: State<ThreadInstallAppActionDialogState?>,
    val actionCardSpecStore: ThreadActionCardSpecStore,
    val actionCardSpecStateHolder: State<ThreadActionCardSpecDialogs>,
    val actionCardSpecHost: ThreadActionCardSpecHost,
)

/**
 * The page's action cards: the step-up, the install dialog store, the spec-card store with its
 * effects, and the spec-card host. Called every composition of the page from where this code stood,
 * so its remembers keep their keys and their place; only the holder is new each time.
 */
@Composable
internal fun rememberThreadRouteActionCards(
    bridge: BridgeModule,
    pager: Pager,
    storeScope: RaftShellStoreScope,
    sharedStores: RaftSharedStoreProvider,
    threadInstanceKey: String,
    coroutineScope: CoroutineScope,
    dataSource: SharedThreadDataSource,
    threadViewModel: SharedThreadViewModel,
    genericActionCardSuccessLabel: String,
    routeAuthRequired: (DataResult.NoPermission) -> Boolean,
    renderedThread: RaftThreadScreenPayload,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    onOpenThreadPayload: (RaftThreadScreenPayload) -> Unit,
    currentServerSlug: String?,
    sharedHomeState: SharedHomeState,
    currentSession: StoredAuthSession?,
    currentServerId: String?,
    threadServerMembersEntry: SharedServerMembersEntry,
    feedbackCardEntryEnabled: Boolean,
): ThreadRouteActionCards {
    // A card execute refused for a fresh second step (two-step verification on; approver-class
    // cards: Approve Login, Install App, and their spec-card forms): "Verify it's you", then the
    // card runs once more. One dialog for the typed commit, the install confirmation and spec cards.
    val actionCardStepUp =
        remember(sharedStores, storeScope, threadInstanceKey) {
            threadActionCardStepUpCoordinator(sharedStores.networkClient(storeScope))
        }
    val actionCardStepUpStateHolder = actionCardStepUp.state.collectAsState()
    // web InstallAppCardDialog: an install card's button opens this; only its Install executes.
    val installAppDialogStore =
        remember(threadInstanceKey) {
            threadInstallAppActionDialogStore(
                scope = coroutineScope,
                dataSource = dataSource,
                applyCard = { messageId, card -> threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(messageId, card)) },
                // The card's done line names what was installed.
                toastInstalled = { bridge.toast(genericActionCardSuccessLabel) },
                routeAuthRequired = { result -> routeAuthRequired(result) },
                stepUp = actionCardStepUp,
            )
        }
    val installAppDialogStateHolder = installAppDialogStore.state.collectAsState()

    // A4: spec card Buttons that run the card (execute, its confirm, a form) go through this store
    // to SharedThreadDataSource.executeActionCardSpec; links and the typed path don't
    // (threadActionCardSpecPress).
    val actionCardSpecStore =
        remember(threadInstanceKey) {
            ThreadActionCardSpecStore(
                scope = coroutineScope,
                execute = { messageId, card, form -> dataSource.executeActionCardSpec(messageId, card, form) },
                closeQuestion = { messageId, card -> dataSource.closeQuestionActionCard(messageId, card) },
                checkSeatRequest = { messageId, card -> dataSource.checkSeatRequest(messageId, card) },
                completeSeatRequest = { messageId, card -> dataSource.completeSeatRequest(messageId, card) },
                readExternalInviteAddresses = { messageId, card -> dataSource.readExternalInviteCardAddresses(messageId, card) },
                declineExternalInvite = { messageId, card -> dataSource.declineExternalInviteActionCard(messageId, card) },
                markExternalInvitesExecuted = { messageId, card, channelId, addresses ->
                    dataSource.markExternalInvitesActionCardExecuted(messageId, card, channelId, addresses)
                },
                reconfirmCard = { messageId, card -> dataSource.reconfirmActionCard(messageId, card) },
                stepUp = actionCardStepUp,
                applyCard = { messageId, card -> threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(messageId, card)) },
                onNoPermission = { result -> routeAuthRequired(result) },
                loadWebUrl = { sharedStores.apiEnvironmentStore.current().webUrl },
                openInBrowser = { url ->
                    openThreadActionCardWebUrl(
                        url = url,
                        activeWebUrl = sharedStores.apiEnvironmentStore.current().webUrl,
                        pager = pager,
                        storeScope = storeScope,
                        bridge = bridge,
                    )
                },
                onApproved = RaftRatingPrompt::onActionCardApproved,
            )
        }
    // The page reads only the open confirm / form / seat-request / external-invite sheet; each card reads its own slice (ThreadActionCardSpecView).
    val actionCardSpecDialogsFlow =
        remember(actionCardSpecStore) {
            actionCardSpecStore.state
                .map { ThreadActionCardSpecDialogs(it.confirm, it.form, it.seatRequest, it.inviteExternal) }
                .distinctUntilChanged()
        }
    val actionCardSpecStateHolder = actionCardSpecDialogsFlow.collectAsState(ThreadActionCardSpecDialogs())
    val actionCardAlreadyExecutedLabel = stringResource(Res.string.thread_action_card_already_executed)
    val actionCardAlreadyExecutedByTemplate =
        stringResource(Res.string.thread_action_card_already_executed_by, ThreadLocalizedFeedbackPlaceholder)
    val actionCardOpenInWebFailedLabel = stringResource(Res.string.thread_action_card_open_in_web_failed)
    val actionCardFrozenClosedLabel = stringResource(Res.string.thread_action_card_frozen_closed)
    val latestActionCardThread by rememberUpdatedState(renderedThread)
    val latestReplyMetadataByParentId by rememberUpdatedState(replyMetadataByParentId)
    val latestOnOpenThreadPayload by rememberUpdatedState(onOpenThreadPayload)
    val latestActionCardServerSlug by rememberUpdatedState(currentServerSlug)
    LaunchedEffect(actionCardSpecStore) {
        actionCardSpecStore.effects.collect { effect ->
            when (effect) {
                is ThreadActionCardSpecEffect.AlreadyExecuted ->
                    bridge.toast(
                        when {
                            effect.frozen -> actionCardFrozenClosedLabel
                            effect.executedByUserName != null ->
                                actionCardAlreadyExecutedByTemplate.replace(ThreadLocalizedFeedbackPlaceholder, effect.executedByUserName)
                            else -> actionCardAlreadyExecutedLabel
                        }
                    )
                ThreadActionCardSpecEffect.OpenInWebFailed -> bridge.toast(actionCardOpenInWebFailedLabel)
                is ThreadActionCardSpecEffect.OpenSeatRequestInWeb ->
                    actionCardSpecStore.dispatch(
                        ThreadActionCardSpecIntent.OpenInWeb(
                            effect.messageId,
                            latestActionCardThread.threadMessageLink(effect.messageId, latestActionCardServerSlug),
                        )
                    )
            }
        }
    }
    // Home's DM rows: a navigate to a message in a DM opens the DM route.
    val actionCardDirectMessageIds =
        remember(sharedHomeState.content) {
            (sharedHomeState.content as? SharedHomeContentState.Content)?.baseSnapshot?.directMessages.orEmpty().mapTo(HashSet()) { it.channelId.value }
        }
    val latestActionCardDirectMessageIds by rememberUpdatedState(actionCardDirectMessageIds)
    val evaluateMigrateAgentAccess =
        rememberThreadActionCardMigrateAccess(currentSession, currentServerId, threadServerMembersEntry)
    val evaluateInviteExternalAccess =
        rememberThreadActionCardInviteExternalAccess(currentSession, currentServerId, sharedHomeState)
    val featureFlagRevision by sharedStores.serverFeatureFlags.snapshotRevision.collectAsState()
    val mcpPersonalAccountsEnabled =
        remember(sharedStores, currentServerId, featureFlagRevision) {
            sharedStores.serverFeatureFlags.isMcpPersonalAccountsEnabled(currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId))
        }
    val mcpConnectCardHost =
        remember(sharedStores, storeScope, threadInstanceKey, dataSource, threadViewModel, bridge) {
            RealThreadMcpConnectCardHost(
                apiClient = RaftManagedMcpConnectApiClient(RaftManagedMcpConnectNetworkApi(sharedStores.networkClient(storeScope))),
                markExecuted = { messageId, card, mcpServerId, agentId ->
                    dataSource.markMcpConnectionActionCardExecuted(messageId, card, mcpServerId, agentId)
                },
                applyCard = { messageId, card -> threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(messageId, card)) },
                onNoPermission = { result -> routeAuthRequired(result) },
                openExternalUrl = { url -> bridge.hostCapabilities.openUrlInBrowser(url) },
                onApproved = RaftRatingPrompt::onActionCardApproved,
            )
        }
    val currentServerPlan =
        remember(currentSession, currentServerId) {
            val sid = currentServerId?.trim()?.takeIf { it.isNotEmpty() }
            sid?.let { id -> currentSession?.servers?.firstOrNull { it.id == id }?.plan }
                ?: currentSession?.currentServerSummary()?.plan
        }
    // Not keyed on the store's state: each card selects its own slice of it.
    val actionCardSpecHost =
        remember(
            feedbackCardEntryEnabled,
            actionCardSpecStore,
            currentSession?.user?.id,
            currentServerPlan,
            evaluateMigrateAgentAccess,
            evaluateInviteExternalAccess,
            mcpPersonalAccountsEnabled,
            mcpConnectCardHost,
            bridge,
        ) {
            ThreadActionCardSpecHost(
                capabilities = raftAppActionCardCapabilities(feedbackFormAvailable = feedbackCardEntryEnabled),
                state = actionCardSpecStore.state,
                onIntent = actionCardSpecStore::dispatch,
                openInWeb = { messageId ->
                    actionCardSpecStore.dispatch(
                        ThreadActionCardSpecIntent.OpenInWeb(
                            messageId,
                            latestActionCardThread.threadMessageLink(messageId, latestActionCardServerSlug),
                        )
                    )
                },
                openExternalUrl = { url -> bridge.hostCapabilities.openUrlInBrowser(url) },
                isDirectMessageChannel = { channelId -> channelId in latestActionCardDirectMessageIds },
                viewerUserId = currentSession?.user?.id,
                currentServerPlan = currentServerPlan,
                clientOs = featureFlagClientOs(),
                openFeedbackTicket = { ticketId ->
                    bridge.log("thread_feedback_ticket_open hasTicket=${ticketId != null}")
                    pager.openSettingsFeedbackPage(storeScope, ticketId = ticketId)
                },
                openPlan = {
                    bridge.log("thread_view_plan_open")
                    pager.openSettingsBillingPage(storeScope)
                },
                evaluateMigrateAgentAccess = evaluateMigrateAgentAccess,
                evaluateInviteExternalAccess = evaluateInviteExternalAccess,
                mcpPersonalAccountsEnabled = mcpPersonalAccountsEnabled,
                mcpConnectCardHost = mcpConnectCardHost,
                resolveHandledByOpen = { cardMessageId, handledBy ->
                    val thread = latestActionCardThread
                    val knownThreadChannelId =
                        latestReplyMetadataByParentId[cardMessageId]?.threadChannelId?.takeIf { it.isNotBlank() }
                            ?: thread.messages.firstOrNull { it.id == cardMessageId }?.threadChannelId?.takeIf { it.isNotBlank() }
                    resolveActionCardHandledByNavigation(
                        renderedThread = thread,
                        cardMessageId = cardMessageId,
                        handledBy = handledBy,
                        knownThreadChannelId = knownThreadChannelId,
                    )?.let { target ->
                        {
                            openActionCardHandledByNavigation(
                                target = target,
                                renderedThread = latestActionCardThread,
                                threadViewModel = threadViewModel,
                                onOpenThreadPayload = latestOnOpenThreadPayload,
                                bridge = bridge,
                            )
                        }
                    }
                },
                log = { line -> bridge.log(line) },
            )
        }
    return ThreadRouteActionCards(
        actionCardStepUp = actionCardStepUp,
        actionCardStepUpStateHolder = actionCardStepUpStateHolder,
        installAppDialogStore = installAppDialogStore,
        installAppDialogStateHolder = installAppDialogStateHolder,
        actionCardSpecStore = actionCardSpecStore,
        actionCardSpecStateHolder = actionCardSpecStateHolder,
        actionCardSpecHost = actionCardSpecHost,
    )
}

/**
 * The thread's action-card step-up (out of SharedThreadRouteContentContent, whose JVM method size
 * is ratcheted: scripts/ci/check_jvm_method_size.py).
 */
private fun threadActionCardStepUpCoordinator(networkClient: RaftNetworkClient): SecondFactorStepUpCoordinator {
    val repository = SecondFactorStepUpRepository(networkClient)
    return SecondFactorStepUpCoordinator(
        stepUp = repository::stepUp,
        passkeyOptions = repository::passkeyAssertionOptions,
        webStepUp = repository.webStepUp(),
    )
}

/**
 * Opens an action card's "Open in web" link with the App Store 3.1.1 guard and own-origin
 * authenticated WebView (out of SharedThreadRouteContentContent to stay below its JVM method-size cap).
 */
private fun openThreadActionCardWebUrl(
    url: String,
    activeWebUrl: String,
    pager: Pager,
    storeScope: RaftShellStoreScope,
    bridge: build.raft.app.base.BridgeModule,
) {
    bridge.log("thread_action_card_open_in_web")
    if (
        raftPlatformFollowsAppStoreBillingRules() &&
        raftUrlIsAppStoreRestrictedPurchaseUrl(url, activeWebUrl)
    ) {
        bridge.logWarn("thread_action_card_appstore_purchase_blocked url=${raftOwnWebUrlForLog(url)}")
        if (raftUrlIsOwnBillingPath(url, activeWebUrl)) {
            pager.openSettingsBillingPage(storeScope)
        }
    } else if (!pager.openRaftOwnWebPage(url, storeScope, activeWebBaseUrl = activeWebUrl)) {
        bridge.hostCapabilities.openUrlInBrowser(url)
    }
}

/** What tapping a withdrawn question card's `handledBy` link opens (slock #17265). */
internal sealed interface ActionCardHandledByNavigation {
    /** Focus (scroll to and highlight) [messageId] in the currently open conversation timeline. */
    data class JumpInCurrentConversation(val messageId: String) : ActionCardHandledByNavigation

    /** Open the card's own reply thread ([parentMessageId] in [parentChannelId]) focused on [focusedMessageId]. */
    data class OpenCardThread(
        val parentMessageId: String,
        val parentChannelId: String,
        val threadChannelId: String?,
        val focusedMessageId: String,
    ) : ActionCardHandledByNavigation

    /** Open the parent conversation (channel or DM) focused on [focusedMessageId]. */
    data class OpenParentConversation(val focusedMessageId: String) : ActionCardHandledByNavigation
}

/**
 * Resolves where tapping a withdrawn question card's `handledBy` link should navigate (slock #17265):
 * - Channel / DM: in-card-thread opens the card's reply thread focused on `handledBy.messageId`;
 *   same-conversation jumps to `handledBy.messageId` in place.
 * - Reply thread: an origin card's in-card-thread message jumps in place and its same-conversation
 *   message opens the parent channel/DM focused on `handledBy.messageId`; a reply card's
 *   same-conversation message jumps in place; nested threads do not exist (null).
 * - Task conversation: same-conversation jumps in place; nested threads do not exist (null).
 */
internal fun resolveActionCardHandledByNavigation(
    renderedThread: RaftThreadScreenPayload,
    cardMessageId: String,
    handledBy: ThreadActionCardHandledBy,
    knownThreadChannelId: String? = null,
): ActionCardHandledByNavigation? {
    val cleanCardId = cardMessageId.trim().takeIf { it.isNotEmpty() } ?: return null
    val targetMessageId = handledBy.messageId.trim().takeIf { it.isNotEmpty() } ?: return null
    if (renderedThread.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        val isOriginCard =
            cleanCardId == renderedThread.parentMessageId?.trim() ||
                cleanCardId == renderedThread.originParentMessageId?.trim()
        return when {
            isOriginCard && handledBy.inCardThread ->
                ActionCardHandledByNavigation.JumpInCurrentConversation(targetMessageId)
            isOriginCard && !handledBy.inCardThread ->
                if (renderedThread.parentChannelId?.isNotBlank() == true) {
                    ActionCardHandledByNavigation.OpenParentConversation(targetMessageId)
                } else {
                    null
                }
            !handledBy.inCardThread ->
                ActionCardHandledByNavigation.JumpInCurrentConversation(targetMessageId)
            else -> null
        }
    }
    if (renderedThread.isTaskConversationSurface()) {
        return if (handledBy.inCardThread) {
            null
        } else {
            ActionCardHandledByNavigation.JumpInCurrentConversation(targetMessageId)
        }
    }
    if (handledBy.inCardThread) {
        val parentChannelId = renderedThread.replyParentChannelIdForNestedOpen()?.takeIf { it.isNotBlank() } ?: return null
        return ActionCardHandledByNavigation.OpenCardThread(
            parentMessageId = cleanCardId,
            parentChannelId = parentChannelId,
            threadChannelId = knownThreadChannelId?.trim()?.takeIf { it.isNotEmpty() },
            focusedMessageId = targetMessageId,
        )
    }
    return ActionCardHandledByNavigation.JumpInCurrentConversation(targetMessageId)
}

private fun openActionCardHandledByNavigation(
    target: ActionCardHandledByNavigation,
    renderedThread: RaftThreadScreenPayload,
    threadViewModel: SharedThreadViewModel,
    onOpenThreadPayload: (RaftThreadScreenPayload) -> Unit,
    bridge: BridgeModule,
) {
    when (target) {
        is ActionCardHandledByNavigation.JumpInCurrentConversation -> {
            bridge.log("thread_action_card_handled_by_open mode=jump target=${target.messageId.take(8)}")
            threadViewModel.dispatch(
                SharedThreadIntent.JumpToMessage(
                    messageId = target.messageId,
                    switchToTab = RaftThreadTab.Chat.wireValue,
                    unreadThreadPill = false,
                )
            )
        }
        is ActionCardHandledByNavigation.OpenCardThread -> {
            bridge.log(
                "thread_action_card_handled_by_open mode=card_thread " +
                    "parent=${target.parentMessageId.take(8)} target=${target.focusedMessageId.take(8)}"
            )
            onOpenThreadPayload(
                renderedThread.replyThreadLoadingPayload(
                    parentMessageId = target.parentMessageId,
                    parentChannelId = target.parentChannelId,
                    threadChannelId = target.threadChannelId,
                    focusedMessageId = target.focusedMessageId,
                )
            )
        }
        is ActionCardHandledByNavigation.OpenParentConversation -> {
            bridge.log("thread_action_card_handled_by_open mode=parent target=${target.focusedMessageId.take(8)}")
            onOpenThreadPayload(renderedThread.parentMessageContextLoadingPayload(parentMessageId = target.focusedMessageId))
        }
    }
}
