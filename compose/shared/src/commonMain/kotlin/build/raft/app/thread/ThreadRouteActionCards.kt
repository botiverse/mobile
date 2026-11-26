package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.base.BridgeModule
import build.raft.app.compat.pager.Pager
import build.raft.app.data.DataResult
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
import build.raft.app.data.featureFlagClientOs
import build.raft.app.data.raftAppActionCardCapabilities
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
    // The page reads only the open confirm / form / seat-request sheet; each card reads its own slice (ThreadActionCardSpecView).
    val actionCardSpecDialogsFlow =
        remember(actionCardSpecStore) {
            actionCardSpecStore.state.map { ThreadActionCardSpecDialogs(it.confirm, it.form, it.seatRequest) }.distinctUntilChanged()
        }
    val actionCardSpecStateHolder = actionCardSpecDialogsFlow.collectAsState(ThreadActionCardSpecDialogs())
    val actionCardAlreadyExecutedLabel = stringResource(Res.string.thread_action_card_already_executed)
    val actionCardAlreadyExecutedByTemplate =
        stringResource(Res.string.thread_action_card_already_executed_by, ThreadLocalizedFeedbackPlaceholder)
    val actionCardOpenInWebFailedLabel = stringResource(Res.string.thread_action_card_open_in_web_failed)
    val actionCardFrozenClosedLabel = stringResource(Res.string.thread_action_card_frozen_closed)
    val latestActionCardThread by rememberUpdatedState(renderedThread)
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
    // Not keyed on the store's state: each card selects its own slice of it.
    val actionCardSpecHost =
        remember(feedbackCardEntryEnabled, actionCardSpecStore, currentSession?.user?.id, evaluateMigrateAgentAccess) {
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
                isDirectMessageChannel = { channelId -> channelId in latestActionCardDirectMessageIds },
                viewerUserId = currentSession?.user?.id,
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
