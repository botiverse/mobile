package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.SecondFactorStepUpOutcome
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedReferenceFailure
import build.raft.app.data.sharedReferenceFailure
import build.raft.app.data.TasksUnassignedAssigneeFilterId
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.isActionCardExpiredRefusal
import build.raft.app.data.markedExpired
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.isSupportedCommitAction
import build.raft.app.data.isFeedbackSubmitAction
import build.raft.app.data.isInstallMarketplaceAppAction
import build.raft.app.model.ConversationKind
import build.raft.app.markdown.parseRaftMarkdownTaskRefLabel
import build.raft.app.markdown.parseRaftMarkdownMessageRefLabel
import build.raft.app.data.HomeChannelRow
import build.raft.app.model.MessageId
import build.raft.app.model.TaskStatus
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

// The larger branches of [ThreadRouteEventHandler.handle], in its order; bodies moved verbatim.

internal fun ThreadRouteEventHandler.handleCommitActionCard(itemId: String): Unit = run ThreadPageContent@{
                    if (itemId in pendingActionCardIds) {
                        return@ThreadPageContent
                    }
                    val actionCardMessage = renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    val actionCard = actionCardMessage?.threadActionCard()
                    if (actionCard == null) {
                        bridge.toast(actionUnavailableLabel)
                        return@ThreadPageContent
                    }
                    // web ActionCard: a frozen card's button is disabled; nothing runs.
                    if (actionCard.isFrozen) return@ThreadPageContent
                    // Expired (by state, or past `expiresAt` on this clock) or withdrawn: no run, and no
                    // create-agent / create-channel / add-members dialog (they create, then mark it executed).
                    if (threadActionCardCommitBlockedByEnd(actionCard, defaultNowMillis())) return@ThreadPageContent
                    // A reconfirm card's button reconfirms it (back to prepared), whatever its type.
                    if (actionCard.needsReconfirmation) {
                        pendingActionCardIds = pendingActionCardIds + itemId
                        coroutineScope.launch {
                            try {
                                when (val result = dataSource.commitThreadActionCard(renderedThread, itemId, actionCard)) {
                                    is DataResult.Content ->
                                        threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(itemId, result.data))
                                    is DataResult.Empty -> bridge.toast(actionCardReconfirmFailedLabel)
                                    is DataResult.Error ->
                                        if (result.error.isActionCardExpiredRefusal()) {
                                            // The server refused it as expired (409): the card says so itself.
                                            threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(itemId, actionCard.markedExpired()))
                                        } else {
                                            bridge.errorToast(actionCardReconfirmFailedLabel, result.error)
                                        }
                                    is DataResult.NoPermission -> {
                                        if (routeAuthRequired(result)) return@launch
                                        bridge.errorToast(actionCardReconfirmFailedLabel, result.blocker)
                                    }
                                }
                            } finally {
                                pendingActionCardIds = pendingActionCardIds - itemId
                            }
                        }
                        return@ThreadPageContent
                    }
                    if (actionCard.isFeedbackSubmitAction()) {
                        val feedbackCardEntry =
                            raftFeedbackCardEntryOrNull(
                                messageId = itemId,
                                card = actionCard,
                                viewingServerId = currentServerId,
                            )
                        if (feedbackCardEntry == null) {
                            actionCardSpecHost.openUnsupportedInWeb(itemId, actionCard.type)
                        } else {
                            // The native form on the card's draft (the web page's card flow).
                            bridge.log("thread_feedback_card_open messageId=$itemId")
                            pager.openSettingsFeedbackCardPage(storeScope, feedbackCardEntry)
                        }
                        return@ThreadPageContent
                    }
                    if (actionCard.isInstallMarketplaceAppAction()) {
                        installAppDialogStore.dispatch(ThreadInstallAppActionIntent.Open(itemId, actionCard))
                        return@ThreadPageContent
                    }
                    if (!actionCard.isSupportedCommitAction()) {
                        // Not runnable in the app: its message on the web, traced by type.
                        actionCardSpecHost.openUnsupportedInWeb(itemId, actionCard.type)
                        return@ThreadPageContent
                    }
                    if (actionCard.type.equals("agent:create", ignoreCase = true)) {
                        val dialogState =
                            ThreadCreateAgentActionDialogState(
                                messageId = itemId,
                                card = actionCard,
                                submitting = false,
                                errorCode = null
                        )
                        createAgentDialogState = dialogState
                        return@ThreadPageContent
                    }
                    pendingActionCardIds = pendingActionCardIds + itemId
                    coroutineScope.launch {
                        try {
                            var createdChannel: HomeChannelRow? = null
                            val outcome =
                                dataSource.commitThreadActionCard(renderedThread, itemId, actionCard, actionCardStepUp) { channel ->
                                    createdChannel = channel
                                    // As submitCreateChannel: Home lists it now, whatever marking the card answers.
                                    sharedHomeStore?.dispatch(SharedHomeIntent.ChannelCreated(channel))
                                    sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
                                }
                            // "Verify it's you" closed: the card stays as it was, nothing is shown.
                            val result = (outcome as? SecondFactorStepUpOutcome.Completed)?.result ?: return@launch
                            when (result) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyActionCard(itemId, result.data)
                                    )
                                    bridge.toast(actionCardSuccessLabels[actionCard.type] ?: genericActionCardSuccessLabel)
                                }
                                is DataResult.Empty -> {
                                    bridge.toast(actionCardFailureLabels[actionCard.type] ?: genericActionCardFailureLabel)
                                }
                                is DataResult.Error -> {
                                    if (result.error.isActionCardExpiredRefusal()) {
                                        // The server refused it as expired (409): the card says so itself.
                                        threadViewModel.dispatch(SharedThreadIntent.ApplyActionCard(itemId, actionCard.markedExpired()))
                                    } else {
                                        bridge.errorToast(actionCardFailureLabels[actionCard.type] ?: genericActionCardFailureLabel, result.error)
                                    }
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.errorToast(actionCardFailureLabels[actionCard.type] ?: genericActionCardFailureLabel, result.blocker)
                                }
                            }
                            // Web CreateChannelDialog `finish`: the new channel opens; marking the card doesn't hold it.
                            createdChannel?.let { channel ->
                                val next =
                                    buildAppShellThreadPayload(
                                        conversation = channel.toThreadLaunchConversationIdentity(),
                                        timeContext = renderedThread.toRaftTimeCapabilities().staticContext(),
                                        alignOwnMessagesRight = renderedThread.alignOwnMessagesRight,
                                        compactConsecutiveMessages = renderedThread.compactConsecutiveMessages,
                                        onWarn = bridge::logWarn,
                                    )
                                if (next != null) {
                                    onOpenThreadPayload(next)
                                } else {
                                    bridge.logWarn("action_card_created_channel_open_rejected reason=identity_unresolved")
                                }
                            }
                        } finally {
                            pendingActionCardIds = pendingActionCardIds - itemId
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleToggleReaction(itemId: String, tab: String): Unit = run ThreadPageContent@{
                    val reactionKey = threadReactionPendingKey(itemId, tab)
                    if (reactionKey in pendingReactionKeys) {
                        return@ThreadPageContent
                    }
                    val toggleTarget = renderedTimelineSlice.threadReactionToggleTarget(itemId, tab)
                    if (toggleTarget == null) {
                        return@ThreadPageContent
                    }
                    val nextSelected = toggleTarget.nextSelected
                    val previousReactions = toggleTarget.message.reactions
                    pendingReactionKeys = pendingReactionKeys + reactionKey
                    pendingReactionVisuals =
                        pendingReactionVisuals +
                            (reactionKey to previousReactions.toPendingReactionVisual(tab, nextSelected))
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyMessageReactionSelection(itemId, tab, selected = nextSelected)
                    )
                    bridge.log(
                        "thread_reaction_toggle messageId=$itemId emoji=$tab selected=$nextSelected"
                    )
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.setMessageReaction(itemId, tab, selected = nextSelected)) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(SharedThreadIntent.ApplyMessageMutation(result.data))
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyMessageReactions(itemId, previousReactions)
                                    )
                                    bridge.errorToast(unableToUpdateReactionLabel, result)
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyMessageReactions(itemId, previousReactions)
                                    )
                                    bridge.errorToast(unableToUpdateReactionLabel, result.blocker)
                                }
                            }
                        } finally {
                            pendingReactionKeys = pendingReactionKeys - reactionKey
                            pendingReactionVisuals = pendingReactionVisuals - reactionKey
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleOpenSourceTask(itemId: String) {
                    // Task v2: a row of the list under a message opens its task (web useOpenTask):
                    // its own conversation, or a pre-v2 task's host thread.
                    val task = linkedTasksProjection.sourceTaskById(itemId)
                    if (task == null) {
                        bridge.logWarn("thread_task_open_rejected reason=source_task_missing taskId=$itemId")
                        bridge.toast(unableToOpenTaskLabel)
                    } else {
                        openCanonicalTask(task, "message_task_list")
                    }
}

internal fun ThreadRouteEventHandler.handleOpenTask(itemId: String, markdownActionId: String?): Unit = run ThreadPageContent@{
                    val markdownPendingToken = markdownActionId?.let(::beginMarkdownReferencePending)
                    if (markdownActionId != null && markdownPendingToken == null) return@ThreadPageContent
                    try {
                        markdownTaskOpenRequestGate.nextRequest()
                        val canonicalTask = linkedTasksProjection.taskById(itemId)
                        val parentMessage = renderedTimelineSlice.findTaskParentMessage(itemId, linkedTasksProjection)
                        val parentReplyMetadata =
                            parentMessage?.let { message ->
                                replyMetadataByParentId.threadReplyMetadataFor(message)
                            }
                        val parentChannelId = renderedThread.replyParentChannelIdForNestedOpen()
                        val provenance =
                            dataSource.replyCacheProvenanceState.value
                                .currentRevisionForReplyCacheOwner(replyCacheProvenanceOwner)
                        val loadedParentProvenanceAccepted =
                            parentMessage != null &&
                                parentReplyMetadata != null &&
                                provenance != null &&
                                dataSource.rememberReplyParentCandidate(
                                    provenance = provenance,
                                    serverId = currentServerId,
                                    parentChannelId = parentChannelId,
                                    parentMessage = parentMessage,
                                    threadChannelId = parentReplyMetadata.threadChannelId,
                                    replyCount = parentReplyMetadata.replyCount
                                )
                        if (parentMessage != null && !loadedParentProvenanceAccepted) {
                            bridge.logWarn("thread_task_parent_seed_rejected reason=stale_provenance")
                        }
                        var useLoadedParent = false
                        arbitrateThreadTaskOpen(
                            loadedParentAvailable = parentMessage != null,
                            loadedParentProvenanceAccepted = loadedParentProvenanceAccepted,
                            canonicalTask = canonicalTask
                        ).dispatch(
                            onUseLoadedParent = { useLoadedParent = true },
                            onOpenCanonical = { task, reason ->
                                bridge.log(
                                    "thread_task_open result=fallback_to_canonical reason=${reason.diagnosticValue} " +
                                        "taskId=$itemId"
                                )
                                openCanonicalTask(
                                    task,
                                    if (markdownActionId == null) "inline_task" else "markdown_task"
                                )
                            },
                            onFailClosed = { reason ->
                                bridge.logWarn(
                                    "thread_task_open_rejected reason=${reason.diagnosticValue} taskId=$itemId"
                                )
                                bridge.toast(unableToOpenTaskLabel)
                            }
                        )
                        if (!useLoadedParent) {
                            return@ThreadPageContent
                        }
                        val loadedParentMessage = parentMessage ?: return@ThreadPageContent
                        val loadedParentReplyMetadata = parentReplyMetadata ?: return@ThreadPageContent
                        val loadingPayload =
                            renderedThread.replyThreadLoadingPayload(
                                parentMessageId = loadedParentMessage.id,
                                parentChannelId = parentChannelId,
                                threadChannelId = loadedParentReplyMetadata.threadChannelId,
                                focusedMessageId = loadedParentReplyMetadata.firstUnreadReplyMessageId
                            )
                        threadViewModel.dispatch(
                            SharedThreadIntent.ApplyReplyThreadRead(
                                parentMessageId = loadedParentMessage.id,
                                threadChannelId = loadedParentReplyMetadata.threadChannelId
                            )
                        )
                        bridge.log(
                            "thread_reply_open action=task parentChannelId=${loadingPayload.parentChannelId.orEmpty()} " +
                                "parentMessageId=${loadedParentMessage.id} taskId=$itemId replyCount=${loadedParentReplyMetadata.replyCount} " +
                                "knownThreadChannelId=${loadedParentReplyMetadata.threadChannelId.orEmpty()} " +
                                "loadingInstance=${loadingPayload.resolveThreadRouteIdentity().instanceKey}"
                        )
                        onOpenThreadPayload(loadingPayload)
                    } finally {
                        finishMarkdownReferencePending(markdownPendingToken)
                    }
}

internal fun ThreadRouteEventHandler.handleOpenMarkdownTaskNumber(itemId: String, markdownActionId: String?): Unit = run ThreadPageContent@{
                    val markdownPendingToken = markdownActionId?.let(::beginMarkdownReferencePending)
                    if (markdownActionId != null && markdownPendingToken == null) return@ThreadPageContent
                    // `#7`, or a conversation-qualified `#proj:#7` / `dm:@peer:#7` / `lounge:@agent:#7` (Web task refs).
                    val qualifiedTaskRef = parseRaftMarkdownTaskRefLabel(itemId)?.takeIf { it.qualified }
                    val taskNumber = qualifiedTaskRef?.taskNumber ?: itemId.removePrefix("#").trim().toIntOrNull()
                    if (taskNumber == null || currentServerId.isNullOrBlank()) {
                        finishMarkdownReferencePending(markdownPendingToken)
                        bridge.log("thread_markdown_task_resolve result=unresolvable label=${itemId.take(32)}")
                        return@ThreadPageContent
                    }
                    val requestGeneration = markdownTaskOpenRequestGate.nextRequest()
                    val resolveStartedAt = TimeSource.Monotonic.markNow()
                    bridge.log(
                        "thread_markdown_task_resolve result=start taskNumber=$taskNumber " +
                            "generation=$requestGeneration"
                    )
                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            val resolveChannelId =
                                if (qualifiedTaskRef == null) {
                                    markdownTaskContextChannelId
                                } else {
                                    val conversationLabel = qualifiedTaskRef.conversationLabel.orEmpty()
                                    prepareMarkdownRefLounge(conversationLabel)
                                    dataSource.markdownTaskRefChannelId(
                                        actionId = markdownActionId,
                                        currentPayload = renderedThread,
                                        conversationLabel = conversationLabel,
                                        requiredKind =
                                            if (qualifiedTaskRef.directMessagePeerName != null) {
                                                ConversationKind.DirectMessage
                                            } else {
                                                null
                                            }
                                    )
                                }
                            val resolved =
                                openMarkdownTaskNumberPayload(
                                    serverId = ServerId(currentServerId),
                                    taskNumber = taskNumber,
                                    currentPayload = renderedThread,
                                    currentTaskChannelId = resolveChannelId,
                                    tasksDataSource = tasksDataSource,
                                    tasksDataStore = tasksDataStore,
                                    conversationIdentityProvider = conversationIdentityProvider,
                                    referenceResolver = dataSource.referenceResolver,
                                    actionId = markdownActionId
                                )
                            val result = markdownTaskOpenRequestGate.accept(requestGeneration, resolved)
                            if (result == null) {
                                bridge.log(
                                    "thread_markdown_task_resolve result=stale taskNumber=$taskNumber " +
                                        "generation=$requestGeneration " +
                                        "durationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds}"
                                )
                                return@launch
                            }
                            when (result) {
                                is MarkdownTaskNumberOpenResult.Found -> {
                                    bridge.log(
                                        "thread_markdown_task_open taskNumber=$taskNumber taskId=${result.task.id.value} " +
                                            "source=${result.source.name} generation=$requestGeneration " +
                                            "resolveDurationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds} " +
                                            "parentChannelId=${result.payload.parentChannelId.orEmpty()} " +
                                            "parentMessageId=${result.payload.parentMessageId.orEmpty()} " +
                                            "threadId=${result.payload.streamChannelId.orEmpty()}"
                                    )
                                    onOpenThreadPayload(result.payload)
                                }
                                is MarkdownTaskNumberOpenResult.OpensInPlace -> {
                                    openCanonicalTask(result.task, "markdown_task")
                                }
                                MarkdownTaskNumberOpenResult.NotFound -> {
                                    // Web `openTaskByChannelNumber`: a ref that names no task of
                                    // its conversation opens nothing and says nothing.
                                    bridge.log(
                                        "thread_markdown_task_resolve result=not_found taskNumber=$taskNumber " +
                                            "generation=$requestGeneration " +
                                            "durationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds}"
                                    )
                                }
                                MarkdownTaskNumberOpenResult.AuthRequired -> {
                                    bridge.log(
                                        "thread_markdown_task_resolve result=auth_required taskNumber=$taskNumber " +
                                            "generation=$requestGeneration " +
                                            "durationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds}"
                                    )
                                    routeAuthRequired(
                                        DataResult.NoPermission(PermissionBlocker(code = "tasks_401"))
                                    )
                                }
                                MarkdownTaskNumberOpenResult.Failed -> {
                                    bridge.log(
                                        "thread_markdown_task_resolve result=failed taskNumber=$taskNumber " +
                                            "generation=$requestGeneration " +
                                            "durationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds}"
                                    )
                                    bridge.errorToast(localizedUnableToOpenTaskNumber(taskNumber))
                                }
                            }
                        } finally {
                            finishMarkdownReferencePending(markdownPendingToken)
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleOpenCommentHost(itemId: String): Unit = run ThreadPageContent@{
                    val commentRef =
                        renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)?.commentRef
                    val hostMessageId = commentRef?.hostMessageId?.takeIf { it.isNotBlank() }
                    val loadingPayload = commentRef?.let { renderedThread.commentHostLoadingPayload(it) }
                    if (hostMessageId == null || loadingPayload == null) {
                        bridge.toast(commentSourceUnavailableLabel)
                        return@ThreadPageContent
                    }
                    bridge.log(
                        "thread_route_open action=comment_host hostMessageId=$hostMessageId " +
                            "targetThreadId=${loadingPayload.sourceThreadId.orEmpty()} " +
                            "parentChannelId=${loadingPayload.parentChannelId.orEmpty()} " +
                            "parentMessageId=${loadingPayload.parentMessageId.orEmpty()} " +
                            "focusedMessageId=${loadingPayload.focusedMessageId.orEmpty()} " +
                            "loadingInstance=${loadingPayload.resolveThreadRouteIdentity().instanceKey}"
                    )
                    onOpenThreadPayload(loadingPayload)
}

internal fun ThreadRouteEventHandler.handleOpenMarkdownUrl(itemId: String) {
                    val open = raftMarkdownUrlOpen(itemId, sharedStores.apiEnvironmentStore.current().webUrl)
                    val serverId = currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
                    if (open.target != null && open.opensOnThisPage { channelId -> serverId?.let { conversationIdentityProvider?.resolveProjection(it, channelId) } != null }) {
                        openThreadWebLinkTargetInApp(open.target)
                    } else {
                        // Any other Raft link (a conversation the shell resolves, a page without a
                        // route here) or an outside one: the link navigator's rule.
                        linkNavigator.open(open.url)
                    }
}

internal fun ThreadRouteEventHandler.handleOpenActionCardChannel(itemId: String) {
                    openThreadWebLinkTargetInApp(RaftThreadWebLinkTarget(channelId = itemId))
}

internal fun ThreadRouteEventHandler.handleOpenMarkdownTag(itemId: String): Unit = run ThreadPageContent@{
                    val localTarget = renderedThread.resolveMarkdownMentionOpenTarget(itemId, emptyList())
                    if (localTarget != null) {
                        onOpenMemberProfile(renderedThread, renderedTimelineSlice, localTarget.identity)
                        return@ThreadPageContent
                    }
                    if (serverMembersStore == null || threadMembersServerId == null) {
                        bridge.toast(localizedUnableToOpenTarget(itemId.take(32)))
                        return@ThreadPageContent
                    }
                    coroutineScope.launch {
                        val membersResult =
                            serverMembersStore.refreshMembers(
                                serverId = threadMembersServerId,
                                priority = DataRequestPriority.Visible
                            )
                        if (membersResult is DataResult.NoPermission && routeAuthRequired(membersResult)) {
                            return@launch
                        }
                        if (threadMembersChannelId != null) {
                            val channelMembersResult =
                                serverMembersStore.loadChannelMembers(
                                    serverId = threadMembersServerId,
                                    channelId = threadMembersChannelId,
                                    priority = DataRequestPriority.Visible
                                )
                            if (channelMembersResult is DataResult.NoPermission && routeAuthRequired(channelMembersResult)) {
                                return@launch
                            }
                        }
                        val serverMembers =
                            serverMembersStore.currentServerMembers(threadMembersServerId) +
                                threadMembersChannelId
                                    ?.let { channelId ->
                                        serverMembersStore.currentChannelMembers(threadMembersServerId, channelId)
                                    }
                                    .orEmpty()
                        val target =
                            renderedThread.resolveMarkdownMentionOpenTarget(
                                label = itemId,
                                serverMembers = serverMembers
                            )
                        if (target == null) {
                            bridge.toast(localizedMemberNotFound(itemId.take(32)))
                        } else {
                            onOpenMemberProfile(renderedThread, renderedTimelineSlice, target.identity)
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleOpenMarkdownChannel(action: String, itemId: String, markdownActionId: String?): Unit = run ThreadPageContent@{
                    val markdownPendingToken = markdownActionId?.let(::beginMarkdownReferencePending)
                    if (markdownActionId != null && markdownPendingToken == null) return@ThreadPageContent
                    val openDirectMessage = action == "openMarkdownDirectMessage"
                    // `lounge:@agent` (slock #16279): [itemId] is that conversation.
                    val openLounge = action == "openMarkdownLounge"
                    coroutineScope.launch {
                        try {
                            if (openLounge) prepareMarkdownRefLounge(itemId)
                            val loadingPayload =
                                dataSource.markdownChannelLaunchPayload(
                                    currentPayload = renderedThread,
                                    channelLabel = itemId,
                                    requiredKind =
                                        if (openDirectMessage) {
                                            ConversationKind.DirectMessage
                                        } else {
                                            null
                                        }
                                )
                            if (loadingPayload == null) {
                                bridge.errorToast(
                                    localizedUnableToOpenTarget(
                                        when {
                                            openDirectMessage -> "dm:@${itemId.take(28)}"
                                            openLounge -> itemId.take(32)
                                            else -> "#${itemId.removePrefix("#").take(31)}"
                                        }
                                    )
                                )
                            } else {
                                val currentRouteIdentity = renderedThread.resolveThreadRouteIdentity()
                                val targetRouteIdentity = loadingPayload.resolveThreadRouteIdentity()
                                if (currentRouteIdentity.canRetainThreadPageFor(targetRouteIdentity)) {
                                    val targetFocusedMessageId = loadingPayload.focusedMessageId
                                    if (!targetFocusedMessageId.isNullOrBlank()) {
                                        threadViewModel.dispatch(
                                            SharedThreadIntent.FocusMessage(
                                                messageId = targetFocusedMessageId,
                                                switchToTab = loadingPayload.selectedTab
                                            )
                                        )
                                    }
                                } else {
                                    onOpenThreadPayload(loadingPayload)
                                }
                            }
                        } finally {
                            finishMarkdownReferencePending(markdownPendingToken)
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleOpenMarkdownRef(action: String, itemId: String, tab: String?, markdownActionId: String?): Unit = run ThreadPageContent@{
                    val openMessageRef = action == "openMarkdownMessageRef"
                    // `lounge:@agent:<short>` (slock #16279): [itemId] is the Lounge, `lounge:@agent`.
                    val openLoungeThreadRef = action == "openMarkdownLoungeThreadRef"
                    val unableToOpenRefLabel =
                        if (openMessageRef) localizedUnableToOpenTarget(itemId.take(32)) else unableToOpenThreadRefLabel
                    val refToastTarget =
                        when {
                            openMessageRef || tab.isNullOrBlank() -> itemId.take(32)
                            openLoungeThreadRef -> "${itemId.take(32)}:${tab.take(12)}"
                            else -> "#${itemId.removePrefix("#").take(31)}:${tab.take(12)}"
                        }
                    val markdownPendingToken = markdownActionId?.let(::beginMarkdownReferencePending)
                    if (markdownActionId != null && markdownPendingToken == null) return@ThreadPageContent
                    // How long the open waits for its target: a local hit opens with no spinner;
                    // only a miss fetches messages/context, and the chip is busy for that fetch alone.
                    val resolveStartedAt = TimeSource.Monotonic.markNow()
                    var resolveOutcome = "cancelled"
                    bridge.log("thread_markdown_ref_resolve result=start label=${itemId.take(32)}")
                    coroutineScope.launch {
                        try {
                            // A ref into a Lounge opens once this client knows that Lounge.
                            prepareMarkdownRefLounge(
                                if (openMessageRef) parseRaftMarkdownMessageRefLabel(itemId)?.conversationLabel.orEmpty() else itemId
                            )
                            val provenance =
                                dataSource.replyCacheProvenanceState.value
                                    .currentRevisionForReplyCacheOwner(replyCacheProvenanceOwner)
                            if (provenance == null) {
                                resolveOutcome = "no_provenance"
                                bridge.toast(unableToOpenRefLabel)
                                return@launch
                            }
                            when (
                                val result =
                                    if (openMessageRef) {
                                        parseRaftMarkdownMessageRefLabel(itemId)
                                            ?.let { ref ->
                                                dataSource.markdownMessageRefLaunchPayload(
                                                    provenance = provenance,
                                                    currentPayload = renderedThread,
                                                    ref = ref,
                                                    actionId = markdownActionId
                                                )
                                            }
                                            ?: DataResult.Empty(EmptyReason.NoMessages)
                                    } else {
                                        dataSource.markdownThreadRefLaunchPayload(
                                            provenance = provenance,
                                            currentPayload = renderedThread,
                                            channelLabel = itemId,
                                            threadShortId = tab,
                                            requiredParentKind =
                                                if (action == "openMarkdownDirectMessageThreadRef") {
                                                    ConversationKind.DirectMessage
                                                } else {
                                                    null
                                                },
                                            actionId = markdownActionId
                                        )
                                    }
                            ) {
                                is DataResult.Content -> {
                                    resolveOutcome = "opened"
                                    bridge.log(
                                        "thread_markdown_ref_open label=${itemId.take(32)} " +
                                            "parentChannelId=${result.data.parentChannelId.orEmpty()} " +
                                            "parentMessageId=${result.data.parentMessageId.orEmpty()} " +
                                            "threadId=${result.data.streamChannelId.orEmpty()} " +
                                            "focusedMessageId=${result.data.focusedMessageId.orEmpty()}"
                                    )
                                    val currentRouteIdentity = renderedThread.resolveThreadRouteIdentity()
                                    val targetRouteIdentity = result.data.resolveThreadRouteIdentity()
                                    if (currentRouteIdentity.canRetainThreadPageFor(targetRouteIdentity)) {
                                        val targetFocusedMessageId = result.data.focusedMessageId
                                        if (!targetFocusedMessageId.isNullOrBlank()) {
                                            threadViewModel.dispatch(
                                                if (openMessageRef) {
                                                    // The named message may be outside the loaded window.
                                                    SharedThreadIntent.JumpToMessage(
                                                        messageId = targetFocusedMessageId,
                                                        switchToTab = result.data.selectedTab
                                                    )
                                                } else {
                                                    SharedThreadIntent.FocusMessage(
                                                        messageId = targetFocusedMessageId,
                                                        switchToTab = result.data.selectedTab
                                                    )
                                                }
                                            )
                                        }
                                    } else {
                                        onOpenThreadPayload(result.data)
                                    }
                                }
                                // A failure never navigates: the toast names why and the page stays.
                                is DataResult.Empty -> {
                                    val failure = result.sharedReferenceFailure()
                                    resolveOutcome = if (failure == SharedReferenceFailure.OtherServer) "other_server" else "not_found"
                                    bridge.errorToast(localizedReferenceFailure(failure, refToastTarget, unableToOpenRefLabel))
                                }
                                is DataResult.Error -> {
                                    resolveOutcome = "error"
                                    bridge.errorToast(unableToOpenRefLabel, result.error)
                                }
                                is DataResult.NoPermission -> {
                                    resolveOutcome = "no_permission"
                                    if (!routeAuthRequired(result)) {
                                        bridge.errorToast(
                                            localizedReferenceFailure(
                                                result.sharedReferenceFailure(),
                                                refToastTarget,
                                                unableToOpenRefLabel
                                            ),
                                            result.blocker
                                        )
                                    }
                                }
                            }
                        } finally {
                            finishMarkdownReferencePending(markdownPendingToken)
                            bridge.log(
                                "thread_markdown_ref_resolve result=$resolveOutcome label=${itemId.take(32)} " +
                                    "durationMs=${resolveStartedAt.elapsedNow().inWholeMilliseconds}"
                            )
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleSubmitAddMember(itemId: String, tab: String?) {
                    val previousPayload = renderedThread
                    threadViewModel.dispatch(
                        SharedThreadIntent.SetAddMemberStatus(status = "adding", errorCode = null)
                    )
                    coroutineScope.launch {
                        when (val result = dataSource.addChannelMember(previousPayload, itemId, tab)) {
                            is DataResult.Content -> {
                                threadViewModel.dispatch(
                                    SharedThreadIntent.ApplyMembersPatch(
                                        membersCount = result.data.membersCount,
                                        members = result.data.members,
                                        memberCandidates = result.data.memberCandidates,
                                        canAddMembers = result.data.canAddMembers
                                    )
                                )
                                bridge.toast(memberAddedLabel)
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                val errorCode = result.threadActionErrorCode("member_add_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetAddMemberStatus(status = "failed", errorCode = errorCode)
                                )
                                bridge.errorToast(unableToAddMemberLabel, result)
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                val errorCode = result.threadActionErrorCode("member_add_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetAddMemberStatus(status = "failed", errorCode = errorCode)
                                )
                                bridge.errorToast(unableToAddMemberLabel, result.blocker)
                            }
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleRemoveMember(itemId: String) {
                    val previousPayload = renderedThread
                    val member = previousPayload.members.firstOrNull { it.id == itemId }
                    threadViewModel.dispatch(
                        SharedThreadIntent.SetRemoveMemberStatus(
                            status = "removing",
                            removingMemberId = itemId,
                            errorCode = null
                        )
                    )
                    coroutineScope.launch {
                        when (val result = dataSource.removeChannelMember(previousPayload, itemId, member?.kind)) {
                            is DataResult.Content -> {
                                threadViewModel.dispatch(
                                    SharedThreadIntent.ApplyMembersPatch(
                                        membersCount = result.data.membersCount,
                                        members = result.data.members,
                                        memberCandidates = result.data.memberCandidates,
                                        canAddMembers = result.data.canAddMembers
                                    )
                                )
                                bridge.toast(memberRemovedLabel)
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                val errorCode = result.threadActionErrorCode("member_remove_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetRemoveMemberStatus(
                                        status = "failed",
                                        removingMemberId = itemId,
                                        errorCode = errorCode
                                    )
                                )
                                bridge.errorToast(unableToRemoveMemberLabel, result)
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                val errorCode = result.threadActionErrorCode("member_remove_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetRemoveMemberStatus(
                                        status = "failed",
                                        removingMemberId = itemId,
                                        errorCode = errorCode
                                    )
                                )
                                bridge.errorToast(unableToRemoveMemberLabel, result.blocker)
                            }
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleArchiveChannel() {
                    val previousPayload = renderedThread
                    coroutineScope.launch {
                        when (val result = dataSource.archiveChannel(previousPayload)) {
                            is DataResult.Content -> {
                                bridge.toast(channelArchivedLabel)
                                onBack()
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> bridge.errorToast(unableToArchiveChannelLabel, result)
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                bridge.errorToast(unableToArchiveChannelLabel, result.blocker)
                            }
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleDeleteChannel() {
                    val previousPayload = renderedThread
                    coroutineScope.launch {
                        when (val result = dataSource.deleteChannel(previousPayload)) {
                            is DataResult.Content -> {
                                bridge.toast(channelDeletedLabel)
                                onBack()
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> bridge.errorToast(unableToDeleteChannelLabel, result)
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                bridge.errorToast(unableToDeleteChannelLabel, result.blocker)
                            }
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleCreateTaskFromMessage(itemId: String): Unit = run ThreadPageContent@{
                    // Task v2 (web MessageItem `openCreateFromMessage`): tasks are created FROM the
                    // message (several, under it), never converted from it.
                    val message = renderedTimelineSlice.threadActionMessage(itemId, linkedTasksProjection)
                    if (message == null || !renderedTimelineSlice.canConvertMessagesToTasks || !message.canCreateTaskFromMessage() || taskChannelId == null) {
                        bridge.toast(unableToCreateTaskLabel)
                        return@ThreadPageContent
                    }
                    createTaskFromMessage.open(
                        TaskCreateSourceMessage(
                            channelId = taskChannelId,
                            messageId = MessageId(message.id),
                            senderName = message.threadAvatarName().ifBlank { message.shownAuthorName() },
                            text = taskCreateSourceText(message.body),
                        )
                    )
}

internal fun ThreadRouteEventHandler.handleSetTaskStatus(itemId: String, tab: String): Unit = run ThreadPageContent@{
                    val status = tab.toSharedThreadTaskStatus()
                    if (status == null) {
                        bridge.toast(unsupportedTaskStatusLabel)
                        return@ThreadPageContent
                    }
                    val intent = linkedTasksProjection.statusUpdateIntent(itemId, status)
                    if (tasksViewModel == null || intent == null) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    tasksViewModel.dispatch(intent)
}

internal fun ThreadRouteEventHandler.handleMoveTaskToBacklog(itemId: String, tab: String?): Unit = run ThreadPageContent@{
                    val intent = linkedTasksProjection.statusUpdateIntent(itemId, TaskStatus.Backlog, tab)
                    if (tasksViewModel == null || intent == null) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    tasksViewModel.dispatch(intent)
}

internal fun ThreadRouteEventHandler.handleSetTaskAssignee(itemId: String, tab: String): Unit = run ThreadPageContent@{
                    val assignee =
                        if (tab == TasksUnassignedAssigneeFilterId) {
                            null
                        } else {
                            resolveThreadTaskAssignee(tab, taskAssigneeOptions)
                        }
                    if (assignee == null && tab != TasksUnassignedAssigneeFilterId) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    val intent = linkedTasksProjection.assignUpdateIntent(itemId, assignee)
                    if (tasksViewModel == null || intent == null) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    tasksViewModel.dispatch(intent)
}

internal fun ThreadRouteEventHandler.handleMarkTaskDone(itemId: String): Unit = run ThreadPageContent@{
                    val intent = linkedTasksProjection.statusUpdateIntent(itemId, TaskStatus.Done)
                    if (tasksViewModel == null || intent == null) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    tasksViewModel.dispatch(intent)
}

internal fun ThreadRouteEventHandler.handleReopenTask(itemId: String): Unit = run ThreadPageContent@{
                    val intent = linkedTasksProjection.statusUpdateIntent(itemId, TaskStatus.Todo)
                    if (tasksViewModel == null || intent == null) {
                        bridge.toast(unableToUpdateTaskLabel)
                        return@ThreadPageContent
                    }
                    tasksViewModel.dispatch(intent)
}

internal fun ThreadRouteEventHandler.handleJoinChannel(): Unit = run ThreadPageContent@{
                    // slock #12426: a join already in flight sends no second request.
                    val joinKey = renderedThread.joinChannelTargetId()
                    if (joinKey != null && !ThreadChannelJoinsInFlight.begin(joinKey)) return@ThreadPageContent
                    coroutineScope.launch {
                        try {
                            // A task conversation's Join joins its task channel, not the conversation.
                            when (
                                val outcome =
                                    renderedThread.joinThreadChannel(dataSource::loadTaskConversationRef, dataSource::joinChannel)
                            ) {
                                is ThreadJoinChannelOutcome.Joined -> {
                                    val joinedId = outcome.channel.joinedChannelId
                                    threadViewModel.dispatch(SharedThreadIntent.ApplyChannelJoined(outcome.channel.pageChannelId.value))
                                    currentSession?.currentServerId?.takeIf(String::isNotBlank)?.let { serverId ->
                                        // Now a member: closing this page must not leave the room (the
                                        // conversation directory may still list the channel as not joined).
                                        realtimeRoomLeaseRegistry.markJoinedByUser(serverId, joinedId.value)
                                        sharedHomeStore?.dispatch(channelJoinedHomeIntent(renderedThread, serverId, joinedId))
                                        sharedHomeStore?.dispatch(SharedHomeIntent.Refresh)
                                    }
                                    realtimeEvents.joinChannel(joinedId.value, currentSession?.currentServerId)
                                    bridge.toast(joinedChannelLabel)
                                }
                                ThreadJoinChannelOutcome.Unresolved -> bridge.toast(unableToJoinChannelLabel)
                                is ThreadJoinChannelOutcome.Failed ->
                                    when (val result = outcome.result) {
                                        is DataResult.NoPermission ->
                                            if (!routeAuthRequired(result)) bridge.errorToast(unableToJoinChannelLabel, result.blocker.traceId)
                                        is DataResult.Error -> bridge.errorToast(unableToJoinChannelLabel, result.error)
                                        else -> bridge.toast(unableToJoinChannelLabel)
                                    }
                            }
                        } finally {
                            joinKey?.let(ThreadChannelJoinsInFlight::end)
                        }
                    }
}

internal fun ThreadRouteEventHandler.handleSelectTab(tab: String) {
                    threadViewModel.dispatch(SharedThreadIntent.SelectTab(tab))
}

internal fun ThreadRouteEventHandler.handleOpenMembersPager(itemId: String?): Unit = run ThreadPageContent@{
                    val serverId = threadMembersServerId
                    val channelId = threadMembersChannelId
                    if (serverId == null || channelId == null) {
                        bridge.logWarn(
                            "thread_members_pager_open_skip reason=missing_scope " +
                                "serverId=${currentSession?.currentServerId.orEmpty()} " +
                                "channelId=${renderedThread.sourceThreadId.orEmpty()}"
                        )
                        return@ThreadPageContent
                    }
                    onOpenMembersPager(renderedThread, serverId, channelId, storeScope, itemId == "add")
}

internal fun ThreadRouteEventHandler.handleOpenChannelSettings(): Unit = run ThreadPageContent@{
                    val serverId = threadMembersServerId
                    val channelId = threadMembersChannelId
                    if (serverId == null || channelId == null) {
                        bridge.logWarn(
                            "channel_settings_open_skip reason=missing_scope " +
                                "serverId=${currentSession?.currentServerId.orEmpty()} " +
                                "channelId=${renderedThread.sourceThreadId.orEmpty()}"
                        )
                        return@ThreadPageContent
                    }
                    onOpenChannelSettings(serverId, channelId, storeScope, settingsPageLinkKey)
}

internal fun ThreadRouteEventHandler.handleLoadMembers() {
                    if (serverMembersStore != null && threadMembersServerId != null && threadMembersChannelId != null) {
                        coroutineScope.launch {
                            serverMembersStore.loadChannelMembers(
                                serverId = threadMembersServerId,
                                channelId = threadMembersChannelId,
                                priority = DataRequestPriority.Visible,
                                force = true
                            )
                        }
                    } else {
                        threadViewModel.dispatch(SharedThreadIntent.LoadMembers)
                    }
}

internal fun ThreadRouteEventHandler.handleLoadOlder() {
                    threadViewModel.dispatch(SharedThreadIntent.LoadOlder)
}

internal fun ThreadRouteEventHandler.handleLoadNewer() {
                    threadViewModel.dispatch(SharedThreadIntent.LoadNewer)
}

internal fun ThreadRouteEventHandler.handleDeleteLocalFailedSend(itemId: String): Unit = run ThreadPageContent@{
                    val message = renderedTimelineSlice.threadMessageById(itemId) ?: return@ThreadPageContent
                    if (message.isFailedLocalSend()) {
                        threadViewModel.dispatch(SharedThreadIntent.DismissLocalFailedSend(message.id))
                    }
}

internal fun ThreadRouteEventHandler.handleCancelLocalSend(itemId: String): Unit = run ThreadPageContent@{
                    val message = renderedTimelineSlice.threadMessageById(itemId) ?: return@ThreadPageContent
                    if (message.isSendingLocalSend()) {
                        threadViewModel.dispatch(SharedThreadIntent.CancelLocalSend(message.id))
                    }
}

internal fun ThreadRouteEventHandler.handleRetryLocalSend(itemId: String): Unit = run ThreadPageContent@{
                    val message = renderedTimelineSlice.threadMessageById(itemId) ?: return@ThreadPageContent
                    val pendingContext = message.pendingSendContext ?: return@ThreadPageContent
                    if (!message.canRetryLocalSend()) {
                        return@ThreadPageContent
                    }
                    val sendAuthority =
                        dataSource.captureThreadLocalSendAuthority(
                            state = threadViewModel.state.value,
                            mountedOwnerProvenance = replyCacheProvenance
                        )
                            ?: run {
                                bridge.logWarn("thread_local_send_retry_rejected reason=missing_owner_provenance")
                                return@ThreadPageContent
                            }
                    val admitted =
                        threadViewModel.admitLocalSend(
                            message =
                                message
                                    .restampedForLocalSendRetry(
                                        buildThreadPendingSendTimestampLabel(bridge, payload.nowEpochMillis)
                                    )
                                    .copy(
                                        deliveryState = ThreadMessageDeliverySending,
                                        pendingSendErrorCode = null,
                                        pendingSendErrorMessage = null
                                    ),
                            expectedProvenance = sendAuthority.provenance,
                            expectedRoute = sendAuthority.route
                        )
                    if (!admitted) {
                        bridge.logWarn("thread_local_send_retry_rejected reason=owner_changed_before_admission")
                        return@ThreadPageContent
                    }
                    sendPendingLocalMessage(
                        localMessageId = message.id,
                        context = pendingContext,
                        startedFromComposer = false,
                        authority = sendAuthority
                    )
}

internal fun ThreadRouteEventHandler.handleUnsupportedEvent(action: String, itemId: String?, attachmentId: String?, tab: String?) {
                    bridge.log(
                        "thread_event_unsupported instanceId=${payload.instanceId} action=$action itemId=${itemId.orEmpty()} attachmentId=${attachmentId.orEmpty()} tab=${tab.orEmpty()}"
                    )
}
