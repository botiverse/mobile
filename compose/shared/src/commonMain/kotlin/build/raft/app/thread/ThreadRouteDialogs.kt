package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import build.raft.app.compat.pager.Pager
import build.raft.app.data.DataResult
import build.raft.app.data.QuestionAskActionCardType
import build.raft.app.data.isPreparedRequestAction
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpDialogState
import build.raft.app.data.SecondFactorStepUpIntent
import build.raft.app.data.SecondFactorStepUpWebTarget
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.expiredAsOf
import build.raft.app.data.secondFactorStepUpWebUrl
import build.raft.app.data.secondFactorStepUpWebServerSlug
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The route's state the dialogs and sheets at the end of `SharedThreadRouteContentContent` read and
 * write, in one object: the banner host's content lambda is at the JVM's captured-value limit.
 * Built every composition from states the route remembers; never remembered itself.
 */
internal class ThreadRouteDialogStates(
    val textSelectionMessage: MutableState<RaftThreadMessageItem?>,
    val viewContextMessage: MutableState<RaftThreadMessageItem?>,
    val attachmentCommentsDialogState: MutableState<ThreadAttachmentCommentsDialogState?>,
    val installAppDialogState: State<ThreadInstallAppActionDialogState?>,
    val createAgentDialogState: MutableState<ThreadCreateAgentActionDialogState?>,
    val migrateAgentDialogState: MutableState<ThreadMigrateAgentActionDialogState?>,
    val createAgentComputerServerId: State<String?>,
    val createAgentComputerServerResolved: State<Boolean>,
    val pendingActionCardIds: MutableState<Set<String>>,
    val renderSlice: State<SharedThreadRenderSlice>,
    val actionCardSpecStore: ThreadActionCardSpecStore,
    val createAgentFormStore: SharedCreateAgentFormStore,
    val viewerUserId: String? = null,
)

/**
 * The route's dialogs and sheets (Select Text, attachment comments, install app, spec card confirm
 * and form, "Verify it's you", create agent), with the effects that keep the card dialogs on the
 * live card. Called unconditionally where they were rendered, so their remembered state and
 * effects keep their place; non-restartable, so it runs whenever the route's content does.
 */
@Composable
@NonRestartableComposable
internal fun ThreadRouteDialogs(
    states: ThreadRouteDialogStates,
    data: SharedThreadRouteContentData,
    labels: ThreadRouteLabels,
    selectionQuoteTarget: ThreadSelectTextQuoteTarget,
    quoteReplyHost: ThreadQuoteReplyHost,
    payload: RaftThreadScreenPayload,
    resolvedMessageBodyTextStyle: TextStyle,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    threadViewModel: SharedThreadViewModel,
    renderedTimelineSlice: ThreadTimelineSlice,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    installAppDialogStore: ThreadInstallAppActionDialogStore,
    actionCardSpecState: ThreadActionCardSpecDialogs,
    actionCardNames: ThreadActionCardNameLookup,
    currentServerId: String?,
    sharedHomeState: SharedHomeState,
    threadServerMembersEntry: SharedServerMembersEntry?,
    threadMembersServerId: ServerId?,
    threadMembersChannelId: ChannelId?,
    actionCardStepUp: SecondFactorStepUpCoordinator,
    actionCardStepUpState: SecondFactorStepUpDialogState?,
    coroutineScope: CoroutineScope,
    pager: Pager,
    onAuthRequired: (String) -> Unit,
    routeAuthRequiredForCode: (String?) -> Boolean,
    routeAuthRequiredForResult: (DataResult.NoPermission) -> Boolean,
    onReloadAttachmentComments: () -> Unit,
    onSendAttachmentComment: () -> Unit,
    onResolveAttachmentComment: (ThreadAttachmentCommentItem) -> Unit,
) {
    // The route's names for these, so the moved code reads as it did there.
    val bridge = data.bridge
    val dataSource = data.dataSource
    val computersStore = data.computersStore
    val serverMembersStore = data.serverMembersStore
    val storeScope = data.storeScope
    val copiedLabel = labels.copiedLabel
    val actionCardSuccessLabels = labels.actionCardSuccessLabels
    val genericActionCardSuccessLabel = labels.genericActionCardSuccessLabel
    var textSelectionMessage by states.textSelectionMessage
    var viewContextMessage by states.viewContextMessage
    var attachmentCommentsDialogState by states.attachmentCommentsDialogState
    val installAppDialogState by states.installAppDialogState
    var createAgentDialogState by states.createAgentDialogState
    var migrateAgentDialogState by states.migrateAgentDialogState
    val createAgentComputerServerId by states.createAgentComputerServerId
    val createAgentComputerServerResolved by states.createAgentComputerServerResolved
    var pendingActionCardIds by states.pendingActionCardIds
    val renderSlice by states.renderSlice
    val actionCardSpecStore = states.actionCardSpecStore
    val createAgentFormStore = states.createAgentFormStore
    fun routeAuthRequired(code: String? = null): Boolean = routeAuthRequiredForCode(code)
    fun routeAuthRequired(result: DataResult.NoPermission): Boolean = routeAuthRequiredForResult(result)
    fun reloadAttachmentComments() = onReloadAttachmentComments()
    fun sendAttachmentComment() = onSendAttachmentComment()
    fun resolveAttachmentComment(comment: ThreadAttachmentCommentItem) = onResolveAttachmentComment(comment)
    textSelectionMessage?.let { message ->
        // Select Text (web #11467): the message's Markdown in a bottom sheet; Copy / Quote take
        // the selection made there, or the whole message.
        val quoteTarget = selectionQuoteTarget
        // Same time source as the message list: the live provider when there is one, else the payload's.
        val timeContext =
            currentRaftDateTimeContext(null) ?: payload.toRaftTimeCapabilities().staticContext()
        val localizedSenderName = message.localizedAuthorName()
        key(message.id, message.body) {
            ThreadMessageSelectTextSheet(
                markdown = message.body,
                senderName = localizedSenderName,
                timeLabel = formatThreadCompactTimestampLabel(message.timestampLabel, timeContext),
                textStyle = resolvedMessageBodyTextStyle,
                onDismiss = { textSelectionMessage = null },
                onCopy = { text ->
                    bridge.log("thread_select_text_copy messageId=${message.id} chars=${text.length}")
                    bridge.copyToPasteboard(text)
                    bridge.briefToast(copiedLabel)
                },
                onQuote = null,
                onQuoteSelection =
                    if (quoteTarget == ThreadSelectTextQuoteTarget.None) {
                        null
                    } else {
                        { selectedText ->
                            // Close first so the sheet's window gives the composer its focus back.
                            textSelectionMessage = null
                            val insert =
                                threadSelectionQuoteInsert(
                                    selectedText = selectedText,
                                    markdown = message.body,
                                    message = message,
                                    authorName = localizedSenderName,
                                )
                            bridge.log(
                                "thread_select_text_quote messageId=${message.id} target=${quoteTarget.name} " +
                                    "structured=${insert.quote != null} chars=${(insert.quote?.excerpt ?: insert.fallbackMarkdown).length}"
                            )
                            quoteReplyHost.requestInsertQuote(insert)
                        }
                    },
                // A sent message's quote points at it (web MessageSelectTextDrawer `messageId`).
                quoteMessageId = message.id.takeIf { message.canQuoteReply() }
            )
        }
    }
    viewContextMessage?.let { message ->
        val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
        val repository = remember(sharedStores, storeScope) { sharedStores.agentContextViewRepository(storeScope) }
        val agentName = message.localizedAuthorName()
        val createdAtLabel = raftLocalizedShortDateTime(message.timestampLabel)
        val channelName =
            payload.title
                .trim()
                .removePrefix("#")
                .takeIf { it.isNotBlank() && payload.surfaceKind != RaftThreadSurfaceKind.DirectMessage }
        key(message.id) {
            AgentContextViewOverlay(
                request =
                    AgentContextViewRequest(
                        agentId = message.authorId,
                        messageId = message.id,
                        agentName = agentName,
                        agentAvatarWireValue = message.avatarWireValue,
                        messageBody = message.body,
                        createdAtLabel = createdAtLabel,
                        channelName = channelName,
                        serverId = currentServerId,
                        viewerUserId = states.viewerUserId,
                    ),
                repository = repository,
                onDismiss = { viewContextMessage = null },
            )
        }
    }
    attachmentCommentsDialogState
        .retainForThreadWriteAuthority(renderSlice.writeActionsEnabled)
        ?.let { dialogState ->
        ThreadAttachmentCommentsSheet(
            state = dialogState,
            onDismiss = { attachmentCommentsDialogState = null },
            onFilterChange = { filter ->
                attachmentCommentsDialogState =
                    attachmentCommentsDialogState
                        ?.takeIf { it.attachment.id == dialogState.attachment.id }
                        ?.copy(filter = filter)
            },
            onDraftChange = { draft ->
                attachmentCommentsDialogState =
                    attachmentCommentsDialogState
                        ?.takeIf { it.attachment.id == dialogState.attachment.id }
                        ?.withAttachmentCommentEditorValue(draft)
            },
            onRetry = ::reloadAttachmentComments,
            onSend = ::sendAttachmentComment,
            onResolve = ::resolveAttachmentComment,
            onQuote = { comment ->
                attachmentCommentsDialogState =
                    attachmentCommentsDialogState
                        ?.takeIf { it.attachment.id == dialogState.attachment.id }
                        ?.withQuotedAttachmentComment(comment)
            }
        )
    }
    // The dialog follows the live card (realtime / the execute answer), not the one it opened with.
    val liveInstallCard =
        installAppDialogState?.messageId?.let { messageId ->
            renderedTimelineSlice.threadActionMessage(messageId, linkedTasksProjection)?.threadActionCard()
        }
    LaunchedEffect(installAppDialogState?.messageId, liveInstallCard) {
        val messageId = installAppDialogState?.messageId ?: return@LaunchedEffect
        liveInstallCard?.let { installAppDialogStore.dispatch(ThreadInstallAppActionIntent.CardUpdated(messageId, it)) }
    }
    installAppDialogState?.let { dialogState ->
        ThreadInstallAppActionDialog(state = dialogState, onIntent = installAppDialogStore::dispatch)
    }
    // A4: a spec card's confirm / form / seat-request sheet follows the live card (its confirmation version, done elsewhere).
    val actionCardSpecDialogMessageId =
        actionCardSpecState.confirm?.messageId
            ?: actionCardSpecState.form?.messageId
            ?: actionCardSpecState.seatRequest?.messageId
    val liveActionCardSpecCard =
        actionCardSpecDialogMessageId?.let { messageId ->
            renderedTimelineSlice.threadActionMessage(messageId, linkedTasksProjection)?.threadActionCard()
        }
    LaunchedEffect(actionCardSpecDialogMessageId, liveActionCardSpecCard) {
        val messageId = actionCardSpecDialogMessageId ?: return@LaunchedEffect
        liveActionCardSpecCard?.let { actionCardSpecStore.dispatch(ThreadActionCardSpecIntent.CardUpdated(messageId, it)) }
    }
    actionCardSpecState.confirm?.let { confirmState ->
        // The sheet sits outside the thread's locals: give it the card's names and viewer server.
        CompositionLocalProvider(
            LocalThreadActionCardNames provides actionCardNames,
            LocalThreadViewerServerId provides currentServerId,
        ) {
            ThreadActionCardSpecConfirmSheet(state = confirmState, onIntent = actionCardSpecStore::dispatch)
        }
    }
    actionCardSpecState.seatRequest?.let { seatRequestState ->
        CompositionLocalProvider(
            LocalThreadActionCardNames provides actionCardNames,
            LocalThreadViewerServerId provides currentServerId,
        ) {
            ThreadActionCardSpecSeatRequestSheet(
                state = seatRequestState,
                onOpenWeb = { actionCardSpecStore.dispatch(ThreadActionCardSpecIntent.OpenSeatRequestInWeb) },
                onDismiss = { actionCardSpecStore.dispatch(ThreadActionCardSpecIntent.DismissSeatRequest) },
            )
        }
    }
    actionCardSpecState.form?.let { formState ->
        // Pickers choose from what this device already holds (spec §3.4): the server's and
        // this channel's members, and the Home sidebar's channels.
        val homeChannels =
            (sharedHomeState.content as? SharedHomeContentState.Content)?.baseSnapshot?.channels.orEmpty()
        val candidates =
            remember(formState.messageId, threadServerMembersEntry, homeChannels) {
                threadActionCardFormCandidates(
                    serverMembers =
                        threadMembersServerId?.let { serverMembersStore?.currentServerMembers(it) }.orEmpty(),
                    channelMembers =
                        threadMembersServerId?.let { serverId ->
                            threadMembersChannelId?.let { channelId -> serverMembersStore?.currentChannelMembers(serverId, channelId) }
                        }.orEmpty(),
                    channels = homeChannels,
                )
            }
        val isQuestion = formState.card.type.equals(QuestionAskActionCardType, ignoreCase = true)
        val isPreparedRequest = formState.card.isPreparedRequestAction
        val nowMillis = currentRaftTimeContextProvider?.current?.nowEpochMillis ?: defaultNowMillis()
        val effectiveCard = formState.card.expiredAsOf(nowMillis)
        val canSubmit =
            !effectiveCard.isExecuted &&
                !effectiveCard.isFrozen &&
                !effectiveCard.hasEnded &&
                !effectiveCard.needsReconfirmation &&
                (!isQuestion || effectiveCard.questionAsksViewer(states.viewerUserId)) &&
                (!isPreparedRequest || effectiveCard.preparedRequestAddressedToViewer(states.viewerUserId))
        // A paged form and a question card's keep a local draft until submitted or the card ends (web ActionCardSpecCard).
        val draftKey =
            if (formState.form.steps != null || isQuestion) {
                actionCardFormDraftKey(states.viewerUserId, formState.messageId, formState.formId)
            } else {
                null
            }
        key(formState.messageId, formState.formId) {
            RaftActionCardFormSheet(
                form = formState.form,
                values = formState.values,
                onValuesChange = { actionCardSpecStore.dispatch(ThreadActionCardSpecIntent.FormValuesChanged(it)) },
                fieldErrors = formState.issues,
                fieldErrorsTraceId = formState.issuesTraceId,
                submitting = formState.submitting,
                onSubmit = { actionCardSpecStore.dispatch(ThreadActionCardSpecIntent.SubmitForm(it)) },
                onDismiss = { actionCardSpecStore.dispatch(ThreadActionCardSpecIntent.DismissForm) },
                candidates = candidates,
                formError = threadActionCardSpecErrorText(formState.error),
                formErrorTraceId = formState.error.traceIdOrNull(),
                canSubmit = canSubmit,
                draftKey = draftKey,
                question = isQuestion,
            )
        }
    }
    // Last of the card dialogs, so it opens over the install confirmation or spec sheet that asked.
    actionCardStepUpState?.let { dialogState ->
        // Open on the web: the card's conversation, on the environment's web in this server.
        val webUrl = rememberThreadActionCardStepUpWebUrl(data, payload)
        SecondFactorStepUpDialog(
            state = if (dialogState.webUrl == null && webUrl != null) dialogState.copy(webUrl = webUrl) else dialogState,
            onIntent = { intent ->
                coroutineScope.launch { actionCardStepUp.accept(intent) }
                if (intent == SecondFactorStepUpIntent.SignInAgain) onAuthRequired("auth_required")
            },
            description = stringResource(Res.string.settings_step_up_generic_description),
        )
    }
    createAgentDialogState?.let { dialogState ->
        val proposedEnvVarsProposer =
            dialogState.card.requesterAgentId?.let { id ->
                actionCardNames.name(ThreadActionCardEntityKind.Agent, id)
            }
        ThreadCreateAgentActionDialogHost(
            dialogState = dialogState,
            computersStore = computersStore,
            createAgentFormStore = createAgentFormStore,
            computerServerId = createAgentComputerServerId,
            computerServerResolved = createAgentComputerServerResolved,
            suggestedEnvVarsProposer = proposedEnvVarsProposer,
            onAuthRequired = ::routeAuthRequired,
            onAddComputer = { pager.openSettingsComputersPage(storeScope, openAddComputer = true) },
            onDismiss = {
                if (!dialogState.submitting) {
                    createAgentDialogState = null
                }
            },
            onSubmit = submitCreateAgent@{ request ->
                if (!renderSlice.writeActionsEnabled || dialogState.submitting) {
                    return@submitCreateAgent
                }
                val normalizedName = request.name.trim().removePrefix("@")
                if (normalizedName.isBlank()) {
                    createAgentDialogState = dialogState.failedWith("agent_name_required")
                    return@submitCreateAgent
                }
                val submitCard =
                    dialogState.card.copy(
                        name = normalizedName,
                        description = request.description,
                        suggestedComputer =
                            if (dialogState.card.requiredComputer.isNullOrBlank()) {
                                request.machineId
                            } else {
                                dialogState.card.suggestedComputer
                            }
                    )
                val submittingState = dialogState.submittingWith(submitCard)
                createAgentDialogState = submittingState
                pendingActionCardIds = pendingActionCardIds + dialogState.messageId
                coroutineScope.launch {
                    try {
                        when (
                            val result =
                                dataSource.commitAgentCreateActionCardFromDialog(
                                    messageId = dialogState.messageId,
                                    card = submitCard,
                                    submit = request
                                )
                        ) {
                            is DataResult.Content -> {
                                createAgentFormStore.rememberCreatedAgent(createAgentComputerServerId, request)
                                threadViewModel.dispatch(
                                    SharedThreadIntent.ApplyActionCard(dialogState.messageId, result.data)
                                )
                                createAgentDialogState = null
                                bridge.toast(actionCardSuccessLabels[submitCard.type] ?: genericActionCardSuccessLabel)
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                createAgentDialogState =
                                    submittingState.failedWith(
                                        errorCode = result.threadActionErrorCode("agent_create_failed"),
                                        errorServerMessage = (result as? DataResult.Error)?.error?.serverMessage
                                    )
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                createAgentDialogState =
                                    submittingState.failedWith(errorCode = result.blocker.code)
                            }
                        }
                    } finally {
                        pendingActionCardIds = pendingActionCardIds - dialogState.messageId
                    }
                }
            }
        )
    }
    migrateAgentDialogState?.let { dialogState ->
        val liveMigrateCard =
            renderedTimelineSlice.threadActionMessage(dialogState.messageId, linkedTasksProjection)?.threadActionCard()
        ThreadMigrateAgentActionDialogHost(
            dialogState = dialogState,
            liveCard = liveMigrateCard,
            data = data,
            serverId = currentServerId,
            serverMembersEntry = threadServerMembersEntry,
            onOpenUpgrade = { pager.openRaftUpgradeDialog(RaftUpgradeGate.AgentMigration, storeScope) },
            onOpenBilling = { pager.openSettingsBillingPage(storeScope) },
            onDismiss = { migrateAgentDialogState = null },
            onMigrationStarted = { messageId, card, agentId, targetMachineId, placedWithoutSource ->
                pendingActionCardIds = pendingActionCardIds + messageId
                coroutineScope.launch {
                    try {
                        when (
                            val result =
                                dataSource.markAgentMigrationActionCardExecuted(
                                    messageId = messageId,
                                    card = card,
                                    agentId = agentId,
                                    targetMachineId = targetMachineId,
                                )
                        ) {
                            is DataResult.Content ->
                                threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(messageId, result.data))
                            is DataResult.NoPermission -> {
                                if (!routeAuthRequired(result)) {
                                    bridge.logWarn("action_card_agent_migrate_mark_executed_denied code=${result.blocker.code}")
                                }
                            }
                            is DataResult.Error ->
                                bridge.logWarn("action_card_agent_migrate_mark_executed_failed code=${result.error.code}")
                            is DataResult.Empty ->
                                bridge.logWarn("action_card_agent_migrate_mark_executed_empty")
                        }
                        if (placedWithoutSource && threadMembersServerId != null) {
                            serverMembersStore?.refreshMembers(threadMembersServerId, build.raft.app.data.DataRequestPriority.Foreground)
                        }
                    } finally {
                        pendingActionCardIds = pendingActionCardIds - messageId
                    }
                }
            },
        )
    }
}

/**
 * Where on the web an action card of [this] page is: its conversation (the channel, or the DM; a
 * reply thread's parent conversation). Null when the page names none.
 */
internal fun RaftThreadScreenPayload.actionCardStepUpWebTarget(): SecondFactorStepUpWebTarget? {
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        val parent = parentChannelId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return SecondFactorStepUpWebTarget.Conversation(parent, directMessage = parentSurfaceKind == RaftThreadSurfaceKind.DirectMessage)
    }
    val channelId =
        streamChannelId?.trim()?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
    return SecondFactorStepUpWebTarget.Conversation(channelId, directMessage = surfaceKind == RaftThreadSurfaceKind.DirectMessage)
}

/**
 * The action cards' "Verify it's you" Open on the web: [payload]'s conversation on the Developer
 * environment's web URL (AGENTS.md Environment Web Link URL Rule) in the session's server.
 */
@Composable
private fun rememberThreadActionCardStepUpWebUrl(
    data: SharedThreadRouteContentData,
    payload: RaftThreadScreenPayload,
): String? {
    val target = payload.actionCardStepUpWebTarget()
    val webUrl by
        produceState<String?>(null, data.bridge, data.storeScope, target) {
            val stores = RaftSharedStoreRegistry.get(data.bridge)
            value =
                target?.let {
                    secondFactorStepUpWebUrl(
                        webBaseUrl = stores.apiEnvironmentStore.current().webUrl,
                        serverSlug = stores.authStore(data.storeScope).readSession().secondFactorStepUpWebServerSlug(),
                        target = it,
                    )
                }
        }
    return webUrl
}
