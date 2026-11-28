package build.raft.app.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftAppSpacing
import build.raft.app.members.RaftMemberAvatarResolver
import build.raft.app.members.RaftMemberModelLabelResolver
import build.raft.app.members.localizedLabel
import build.raft.app.TaskConversationHeadRow
import build.raft.app.TaskConversationHeadRowContent
import build.raft.app.currentRaftTimeContextProvider
import build.raft.app.data.HiddenHistory
import build.raft.app.data.RaftMessageAction
import build.raft.app.data.RaftMessageActionSurface
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.SharedMessageTranslationState
import build.raft.app.data.SharedMessageTranslationStore
import build.raft.app.data.TaskHandoffTimeline
import build.raft.app.localizedLabel
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.messages
import build.raft.app.origin
import build.raft.app.rememberRaftMessageTimeFormat
import build.raft.app.resources.*
import build.raft.app.thread.localizedLabel
import kotlin.time.TimeSource

/**
 * The thread list's rows, in the `LazyColumn` of [ThreadMessagesListContent]: the origin card, the
 * older history boundary, the reply empty / loading rows, the messages, the newer messages row and
 * the bottom padding row. Called from the list's content lambda where this code stood, so the rows,
 * their keys and content types are the same.
 */
internal fun LazyListScope.threadMessageItems(
    renderConfig: ThreadTimelineRenderConfig,
    messageBodyTextStyle: TextStyle,
    timelineSlice: ThreadTimelineSlice,
    replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    activeReplyDraftParentMessageIds: Set<String>,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    highlightedMentions: Set<String>,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver,
    memberModelLabelResolver: RaftMemberModelLabelResolver,
    inlineReplyParticipantsById: Map<String, RaftThreadMemberItem>,
    pendingActionCardIds: Set<String>,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState>,
    writeActionsEnabled: Boolean,
    currentServerSlug: String?,
    contentExtrasVisible: Boolean,
    longMessageCollapseEnabled: Boolean,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean,
    markdownTagContextSignature: String,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)?,
    activeMarkdownTagKey: String?,
    onThreadEvent: ThreadEventHandler,
    onOpenMember: (MemberDetailIdentity) -> Unit,
    onExternalAuthor: (RaftThreadMessageItem) -> Unit,
    onMentionAuthor: (RaftThreadMessageItem) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag, String) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    quotedPermalinkLoader: ThreadQuotedPermalinkLoader?,
    diagnosticsLogger: (String) -> Unit,
    shareSelectionActive: Boolean,
    shareSelectionCaptureActive: Boolean,
    shareSelectionCaptureTailPaddingActive: Boolean,
    selectedShareMessageIds: Set<String>,
    onShareSelectionBounds: (String, ThreadShareCaptureRect?) -> Unit,
    onForwardMessage: (String) -> Unit,
    onToggleShareSelection: (String) -> Unit,
    onOpenForwardBundle: (String) -> Unit,
    translationStore: SharedMessageTranslationStore?,
    translationState: SharedMessageTranslationState,
    renderPayload: ThreadTimelineSlice,
    taskHeadRow: TaskConversationHeadRow?,
    listHiddenHistory: HiddenHistory?,
    historyPaywall: HiddenHistory?,
    peerReadReceipts: ThreadPeerReadReceiptsUi?,
    dmReadStatusMessageId: String?,
    showReplyLoadingSkeleton: Boolean,
    showReplyEmptyState: Boolean,
    replyLoadingSkeletonVisible: Boolean,
    olderBoundaryStatusSpec: ThreadHistoryStatusSpec?,
    visibleBoundaryLoadingSlot: ThreadBoundaryLoadingSlot?,
    bottomSpacerAndAnchorTrailingHeight: Dp,
    reverseLayout: Boolean,
    listState: ThreadListViewport,
    duplicateMessageRandomIds: Set<String>,
    threadRowProbeSessionMarker: String,
    firstViewportProbe: ThreadFirstViewportProbe,
    imeTailDrawProbe: ThreadImeTailDrawProbe,
    firstViewportProbeActiveState: State<Boolean>,
    menuState: ThreadMessageMenuState,
    longMessageCollapseController: RaftLongMessageCollapseController,
    recordMessageItemHeight: (String, Int) -> Unit,
    toggleLongMessage: (String, Int) -> Unit,
    keepReadingAnchorAfterLayout: () -> Unit,
    onThreadEventKeepingAnchor: ThreadEventHandler,
    activeFocusedMessageIdState: State<String?>,
    alignInitialFocusedAfterLayout: () -> Unit,
    activeInlineSearchMessageId: String?,
    systemMessageFolds: List<ThreadSystemMessageFold?>,
    taskHandoffTimelineById: Map<String, TaskHandoffTimeline>,
    expandedSystemMessageGroupsState: State<Set<String>>,
    toggleSystemMessageGroup: (String, String, String) -> Unit,
    requestRowExpandReveal: (String) -> Unit,
    handoffReasonMarkdown: ThreadTaskHandoffReasonMarkdown,
    requestNewerPage: (String) -> Unit,
    outlineOriginJumpRequestState: MutableState<Int>,
    timeReveal: ThreadTimeRevealState?,
) {
    val firstViewportProbeActive by firstViewportProbeActiveState
    val activeFocusedMessageId by activeFocusedMessageIdState
    val expandedSystemMessageGroups by expandedSystemMessageGroupsState
    val threadListSegments = mutableListOf<LazyListScope.() -> Unit>()
    threadListSegments += {
    if (renderPayload.hasThreadOriginItem()) {
        item(key = ThreadOriginListItemKey) {
            ThreadOriginCard(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            renderPayload.originMessage?.id?.let { messageId ->
                                recordMessageItemHeight(messageId, coordinates.size.height)
                            }
                            alignInitialFocusedAfterLayout()
                            keepReadingAnchorAfterLayout()
                        },
                renderConfig = renderConfig,
                messageBodyTextStyle = messageBodyTextStyle,
                timelineSlice = renderPayload,
                focusedMessageId = activeFocusedMessageId,
                searchMatchMessageId = activeInlineSearchMessageId,
                linkedTasksProjection = linkedTasksProjection,
                highlightedMentions = highlightedMentions,
                pendingActionCardIds = pendingActionCardIds,
                attachmentOpenStates = attachmentOpenStates,
                currentServerSlug = currentServerSlug,
                contentExtrasVisible = contentExtrasVisible,
                isMarkdownTagValid = isMarkdownTagValid,
                markdownTagContextSignature = markdownTagContextSignature,
                resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                activeMarkdownTagKey = activeMarkdownTagKey,
                diagnosticsLogger = diagnosticsLogger,
                onThreadEvent = onThreadEventKeepingAnchor,
                onOpenMember = onOpenMember,
                onExternalAuthor = onExternalAuthor,
                showReactionReactors = true,
                onShowAuthorDescription = { description, anchor ->
                    menuState.authorDescriptionPopover =
                        ThreadAuthorDescriptionPopoverRequest(
                            description = description,
                            anchor = anchor
                        )
                },
                onShowActions = { message, anchor ->
                    menuState.actionMenuAnchor = anchor
                    menuState.actionMessageId = message.id
                    menuState.classicMenuOverrideMessageId = null
                    menuState.actionMenuFromLongPress = false
                    diagnosticsLogger("thread_origin_menu_tap messageId=${message.id}")
                },
                writeActionsEnabled = writeActionsEnabled,
                onShowQuickReactions = { message, anchor ->
                    menuState.quickReactionBar = ThreadQuickReactionBarRequest(message.id, anchor)
                },
                onLongPressMenuOpened = { message ->
                    if (menuState.actionMessageId == message.id) {
                        menuState.actionMenuFromLongPress = true
                        RaftProductEvents.messageActionSelected(RaftMessageAction.OpenMenu, inThread = false)
                    }
                },
                onOpenTaskStatusMenu = { message, anchor ->
                    menuState.taskStatusMenuMessageId = message.id
                    menuState.taskStatusMenuAnchor = anchor
                },
                onMentionAuthor = onMentionAuthor,
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                memberModelLabelResolver = memberModelLabelResolver,
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl,
                onOpenForwardBundle = onOpenForwardBundle,
                shareSelectionActive = shareSelectionActive,
                selectedShareMessageIds = selectedShareMessageIds,
                onToggleShareSelection = onToggleShareSelection,
                longMessageCollapse =
                    longMessageCollapseController.takeIf {
                        longMessageCollapseEnabled && !shareSelectionCaptureActive
                    },
                onToggleLongMessage = { messageId ->
                    toggleLongMessage(messageId, ThreadOriginListItemIndex)
                },
                popupOpen =
                    renderPayload.originMessage?.id?.let { originId ->
                        menuState.actionMessageId == originId || menuState.quickReactionBar?.messageId == originId
                    } == true,
                hoverToolbar =
                    renderPayload.originMessage?.let { origin ->
                        val originCapabilities =
                            threadMessageMenuCapabilities(
                                message = origin,
                                replyMetadata = origin.toThreadReplyMetadata(),
                                slice = renderPayload,
                                writeActionsEnabled = writeActionsEnabled
                            )
                        val originQuoteHost = LocalThreadInPlaceTextSelectionHost.current
                        val originQuoteIntoComposer = originQuoteHost?.quoteIntoComposer
                        val originInsertQuoteIntoComposer = originQuoteHost?.insertQuoteIntoComposer
                        val originPendingQuote =
                            if (
                                originCapabilities.copyText &&
                                (originInsertQuoteIntoComposer != null || originQuoteIntoComposer != null)
                            ) {
                                threadMessageMenuPendingQuote(origin, origin.localizedAuthorName())
                            } else {
                                null
                            }
                        threadMessageHoverToolbarActions(
                            message = origin,
                            capabilities = originCapabilities,
                            onShowReactions = { anchor ->
                                if (menuState.actionMessageId == origin.id) {
                                    menuState.actionMessageId = null
                                    menuState.classicMenuOverrideMessageId = null
                                }
                                RaftProductEvents.messageActionSelected(
                                    RaftMessageAction.OpenReactionPicker,
                                    inThread = false,
                                    surface = RaftMessageActionSurface.HoverToolbar,
                                )
                                menuState.quickReactionBar =
                                    ThreadQuickReactionBarRequest(origin.id, anchor, fromHoverToolbar = true)
                            },
                            onQuickReact = { emoji ->
                                if (menuState.quickReactionBar?.messageId == origin.id) {
                                    menuState.quickReactionBar = null
                                }
                                if (menuState.actionMessageId == origin.id) {
                                    menuState.actionMessageId = null
                                    menuState.classicMenuOverrideMessageId = null
                                }
                                RaftProductEvents.messageActionSelected(
                                    RaftMessageAction.ReactQuick,
                                    inThread = false,
                                    surface = RaftMessageActionSurface.HoverToolbar,
                                )
                                onThreadEventKeepingAnchor("toggleReaction", origin.id, null, emoji)
                            },
                            onForward = { onForwardMessage(origin.id) },
                            onToggleSave = { onThreadEventKeepingAnchor("toggleMessageSaved", origin.id, null, null) },
                            quote = originPendingQuote != null,
                            onQuote = {
                                originPendingQuote?.let { pending ->
                                    if (menuState.quickReactionBar?.messageId == origin.id) {
                                        menuState.quickReactionBar = null
                                    }
                                    if (menuState.actionMessageId == origin.id) {
                                        menuState.actionMessageId = null
                                        menuState.classicMenuOverrideMessageId = null
                                    }
                                    RaftProductEvents.messageActionSelected(
                                        RaftMessageAction.Quote,
                                        inThread = false,
                                        surface = RaftMessageActionSurface.HoverToolbar,
                                    )
                                    val insert =
                                        ThreadComposerQuoteInsert(
                                            quote = pending,
                                            fallbackMarkdown = formatThreadMessageQuote(pending.excerpt),
                                        )
                                    if (originInsertQuoteIntoComposer != null) {
                                        originInsertQuoteIntoComposer(insert)
                                    } else {
                                        threadMessageMenuQuote(origin)?.let { originQuoteIntoComposer?.invoke(it) }
                                    }
                                }
                            },
                        )
                    }
            )
        }
    }
    }
    threadListSegments += {
    if (renderPayload.hasThreadHistoryBoundaryItem()) {
        item(key = ThreadOlderMessagesListItemKey) {
            Column(Modifier.fillMaxWidth()) {
            // Task v2 on a phone: the head above where the conversation begins, or above the
            // older messages still to load (they come in under it: it stays the first row).
            taskHeadRow?.let { TaskConversationHeadRowContent(it) }
            when {
                renderPayload.canLoadMoreBefore && renderPayload.olderMessagesStatus == "error" ->
                    ThreadInlineActionRow(
                        label = stringResource(Res.string.thread_retry_older),
                        onClick = { onThreadEvent("retryLoadOlder", null, null, null) }
                    )
                renderPayload.canLoadMoreBefore ->
                    olderBoundaryStatusSpec
                        .visibleForBoundaryLoadingSlot(visibleBoundaryLoadingSlot)
                        ?.let { spec ->
                            ThreadHistoryStatusRow(spec, loadingRevealDelayMs = 0L)
                        }
                historyPaywall != null ->
                    ThreadHistoryPaywall(
                        scope = renderPayload.historyPaywallScope(),
                        hidden = historyPaywall,
                        allHidden = false,
                        timeContext = renderConfig.timeContext,
                        onUpgrade = { onThreadEvent(ThreadOpenPlanBillingEvent, null, null, null) }
                    )
                else ->
                    olderBoundaryStatusSpec
                        .visibleForBoundaryLoadingSlot(visibleBoundaryLoadingSlot)
                        ?.let { spec ->
                            ThreadHistoryStatusRow(spec, loadingRevealDelayMs = 0L)
                        }
            }
            }
        }
    }
    }
    threadListSegments += {
    if (showReplyEmptyState) {
        item(key = ThreadReplyEmptyListItemKey) {
            if (listHiddenHistory != null) {
                // Every reply is behind the Free plan's window: not empty, hidden.
                ThreadHistoryPaywall(
                    scope = renderPayload.historyPaywallScope(),
                    hidden = listHiddenHistory,
                    allHidden = true,
                    timeContext = renderConfig.timeContext,
                    onUpgrade = { onThreadEvent(ThreadOpenPlanBillingEvent, null, null, null) }
                )
            } else {
                ThreadStateMessage(
                    localizedThreadStateTitle(renderPayload.emptyTitle),
                    localizedThreadStateDetail(renderPayload.emptyDetail)
                )
            }
        }
    }
    }
    threadListSegments += {
    if (showReplyLoadingSkeleton) {
        item(key = ThreadReplyLoadingListItemKey) {
            if (renderPayload.loadingIndicatorRevealed) {
                ThreadLoadingState()
            }
        }
    }
    }
    threadListSegments += {
    val messageCount = renderPayload.messages.size
    items(
        messageCount,
        key = { slot ->
            renderPayload.messages[if (reverseLayout) messageCount - 1 - slot else slot]
                .messageListItemKey(duplicateMessageRandomIds)
        }
    ) { slot ->
        // `index` stays the forward (oldest-first) message index in both layouts.
        val index = if (reverseLayout) messageCount - 1 - slot else slot
        val message = renderPayload.messages[index]
        val absoluteMessageIndex = index
        val previousMessage = renderPayload.messages.getOrNull(index - 1)
        SideEffect {
            firstViewportProbe.recordComposedMessage(message.id, absoluteMessageIndex)
        }
        val systemFold = systemMessageFolds.getOrNull(index)
        val systemGroupExpanded = systemFold != null && systemFold.groupId in expandedSystemMessageGroups
        if (systemFold is ThreadSystemMessageFold.Member && !systemGroupExpanded) {
            // Folded into its group's closed summary row: the item stays (key and index
            // unchanged for anchoring and jumps) but draws nothing.
            Box(
                Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                    recordMessageItemHeight(message.id, coordinates.size.height)
                    alignInitialFocusedAfterLayout()
                    keepReadingAnchorAfterLayout()
                }
            )
            return@items
        }
        val messagePerformanceKey =
            remember(message.id, absoluteMessageIndex) {
                threadMessagePerformanceKey(message.id, absoluteMessageIndex)
            }
        val isSystemMessage = remember(message.messageType) { message.isSystemMessage() }
        val previousCompactAuthorSignature = previousMessage?.threadCompactAuthorSignature()
        val messageCompactAuthorSignature = message.threadCompactAuthorSignature()
        val suppressRepeatedAuthor =
            remember(
                renderPayload.compactConsecutiveMessages,
                renderConfig.timeContext.localOffsetMinutes,
                previousCompactAuthorSignature,
                messageCompactAuthorSignature
            ) {
                renderPayload.compactConsecutiveMessages &&
                    shouldSuppressCompactThreadAuthor(
                        previous = previousMessage,
                        next = message,
                        localOffsetMinutes = renderConfig.timeContext.localOffsetMinutes
                    )
            }
        // Web MessageItem `showSelectedGroupedHeader`: while messages are picked for the share
        // image, the first selected row of a run shows its author even when the timeline groups
        // it under an unselected message, so the image (the live rows) never starts headless.
        val rowSuppressesRepeatedAuthor =
            threadShareSelectionSuppressesAuthor(
                timelineSuppressesAuthor = suppressRepeatedAuthor,
                selectionActive = shareSelectionActive || shareSelectionCaptureActive,
                selected = message.id in selectedShareMessageIds,
                previousSelected = previousMessage != null && previousMessage.id in selectedShareMessageIds,
            )
        val isRichContentEnabled = contentExtrasVisible
        val actionCardSignature =
            if (isRichContentEnabled) {
                remember(message.id, message.metadata) { message.threadActionCardSignature() }
            } else {
                null
            }
        val actionCard =
            if (isRichContentEnabled) {
                remember(actionCardSignature) {
                    val startedAt = TimeSource.Monotonic.markNow()
                    message.threadActionCard()
                        .also { card ->
                            val durationMs = startedAt.elapsedNow().inWholeMilliseconds
                            if (shouldLogThreadPerformance(durationMs)) {
                                diagnosticsLogger(
                                    "thread_action_card_parse key=$messagePerformanceKey " +
                                        "hasActionCard=${card != null} durationMs=$durationMs"
                                )
                            }
                        }
                }
            } else {
                null
            }
        // Phase 1 (#172) instrumentation: one-shot per-row log gated
        // by DEBUG_THREAD_VIRTUAL_RENDER_LOG so the diagnostic is
        // not a hot path. Fires on first composition of this row
        // per Thread sessionMarker; does not re-fire on scroll.
        if (DEBUG_THREAD_VIRTUAL_RENDER_LOG) {
            androidx.compose.runtime.LaunchedEffect(message.id, threadRowProbeSessionMarker) {
                        diagnosticsLogger(
                            "thread_row_compose sessionMarker=${threadRowProbeSessionMarker} " +
                                "index=$absoluteMessageIndex id=${message.id} messageType=${message.messageType} " +
                                "bodyLength=${message.body.length} isSystem=${isSystemMessage} " +
                                "hasActionCard=${actionCard != null} " +
                                "attachmentCount=${message.attachments.size} " +
                                "threadId=${renderPayload.sourceThreadId.orEmpty()} " +
                                "messagesTotal=${renderPayload.messages.size} richContentEnabled=$isRichContentEnabled " +
                                "renderMessages=${renderPayload.messages.size}"
                        )
            }
        }
        val linkedTask = linkedTasksProjection.lookup.threadLinkedTaskForMessage(message)
        val replyMetadata = replyMetadataByParentId.threadReplyMetadataFor(message)
        // Task v2: the list under the message replaces its single task chip (web MessageItem).
        // In a reply thread only the parent message carries it (web ThreadPanel), never a reply.
        val replyInThread =
            threadMessageIsReplyInThread(renderPayload.replyThreadSurface, renderPayload.originMessage?.id, message.id)
        val sourceTasks =
            remember(message.id, linkedTasksProjection, replyInThread) {
                if (replyInThread) emptyList() else linkedTasksProjection.sourceTasksFor(message)
            }
        val displayMessage =
            remember(message, linkedTasksProjection, sourceTasks.isEmpty()) {
                val startedAt = TimeSource.Monotonic.markNow()
                message.withLinkedTaskProjection(linkedTasksProjection)
                    .let { if (sourceTasks.isEmpty()) it else it.withoutLinkedTask() }
                    .also {
                        val durationMs = startedAt.elapsedNow().inWholeMilliseconds
                        if (shouldLogThreadPerformance(durationMs)) {
                            diagnosticsLogger(
                                "thread_row_heavy_work stage=linked_task key=$messagePerformanceKey " +
                                    "hasLinkedTask=${linkedTask != null} durationMs=$durationMs"
                            )
                        }
                    }
            }
        val linkedTaskAssigneeName = linkedTask?.threadLinkedTaskAssigneeDisplayName()
        // Start tracking as soon as the user selects a row. Export can
        // begin UNDISTPATCHED before the capture-mode recomposition
        // reaches this lazy item, so its already-visible bounds are the
        // only deterministic initial placement in that window.
        val captureShareBounds =
            (shareSelectionActive || shareSelectionCaptureActive) &&
                message.id in selectedShareMessageIds
        val liveTodayEpochDay = currentRaftTimeContextProvider?.todayEpochDay
        val messageTime = rememberRaftMessageTimeFormat()
        val dateDividerDay =
            remember(
                previousMessage?.timestampLabel,
                message.timestampLabel,
                renderConfig.timeContext.localOffsetMinutes,
                liveTodayEpochDay
            ) {
                threadDateDividerDay(
                    previous = previousMessage,
                    next = message,
                    timeContext = renderConfig.dateDividerTimeContext(liveTodayEpochDay)
                )
            }
        // The row showing its hover toolbar draws over its neighbours: the toolbar reaches
        // 18dp into the row above, which a reversed list draws later.
        val raisedForHoverToolbar by remember(message.id) {
            derivedStateOf { menuState.hoverToolbarMessageId == message.id }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (raisedForHoverToolbar) 1f else 0f)
                    .onGloballyPositioned { coordinates ->
                        recordMessageItemHeight(message.id, coordinates.size.height)
                        imeTailDrawProbe.recordPlaced(message.id)
                        alignInitialFocusedAfterLayout()
                        keepReadingAnchorAfterLayout()
                    }
                    .drawWithContent {
                        imeTailDrawProbe.recordDraw(message.id)
                        drawContent()
                    }
        ) {
            dateDividerDay?.let { day ->
                val dayLabel = day.localizedLabel()
                // Under the history paywall the first date line says the window: "Last 30 days · …".
                ThreadDateDivider(
                    if (index == 0 && historyPaywall != null) {
                        stringResource(Res.string.thread_history_paywall_recent_divider_with_date, ThreadHistoryPaywallDays, dayLabel)
                    } else {
                        dayLabel
                    }
                )
            }
            Box(
                modifier =
                    Modifier
                        .then(
                            if (firstViewportProbeActive) {
                                Modifier.onGloballyPositioned {
                                    firstViewportProbe.recordPlacedMessage(message.id, absoluteMessageIndex)
                                }
                            } else {
                                Modifier
                            }
                        )
                        .then(
                            if (captureShareBounds) {
                                Modifier.onGloballyPositioned { coordinates ->
                                    val pos = coordinates.positionInRoot()
                                    onShareSelectionBounds(
                                        message.id,
                                        ThreadShareCaptureRect(
                                            x = pos.x,
                                            y = pos.y,
                                            width = coordinates.size.width.toFloat(),
                                            height = coordinates.size.height.toFloat()
                                        )
                                    )
                                }
                            } else {
                                Modifier
                            }
                        )
            ) {
                if (!captureShareBounds) {
                    SideEffect {
                        onShareSelectionBounds(message.id, null)
                    }
                }
                if (systemFold is ThreadSystemMessageFold.Summary) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ThreadSystemMessageGroupHeader(
                            summary = threadSystemMessageGroupSummary(systemFold.categories),
                            expanded = systemGroupExpanded,
                            onToggle = { toggleSystemMessageGroup(systemFold.groupId, message.id, systemFold.memberIds.last()) },
                        )
                        if (systemGroupExpanded) {
                            ThreadSystemMessageRow(
                                message = displayMessage,
                                timestampValue = message.timestampLabel,
                                timeline = renderPayload.messages,
                                handoffTimeline = taskHandoffTimelineById[message.id],
                                serverSlug = currentServerSlug,
                                onOpenUrl = onOpenMarkdownUrl,
                                onExpand = { requestRowExpandReveal(message.id) },
                                handoffReasonMarkdown = handoffReasonMarkdown,
                                messageBodyTextStyle = messageBodyTextStyle,
                                memberAvatarResolver = memberAvatarResolver,
                                memberModelLabelResolver = memberModelLabelResolver,
                                onOpenMember = onOpenMember,
                                isDm = renderPayload.directMessageSurface,
                            )
                        }
                    }
                } else if (isSystemMessage && actionCard == null) {
                    ThreadSystemMessageRow(
                        message = displayMessage,
                        timestampValue = message.timestampLabel,
                        timeline = renderPayload.messages,
                        handoffTimeline = taskHandoffTimelineById[message.id],
                        serverSlug = currentServerSlug,
                        onOpenUrl = onOpenMarkdownUrl,
                        onExpand = { requestRowExpandReveal(message.id) },
                        handoffReasonMarkdown = handoffReasonMarkdown,
                        messageBodyTextStyle = messageBodyTextStyle,
                        memberAvatarResolver = memberAvatarResolver,
                        memberModelLabelResolver = memberModelLabelResolver,
                        onOpenMember = onOpenMember,
                        isDm = renderPayload.directMessageSurface,
                    )
                } else {
                    val translationUi =
                        if (translationStore != null) {
                            resolveThreadMessageTranslationUi(displayMessage, translationState)
                        } else {
                            null
                        }
                    ThreadTimeRevealRow(
                        state = timeReveal,
                        timeLabel = formatThreadTimeRevealLabel(message.timestampLabel, renderConfig.timeContext),
                        continuation = renderPayload.compactConsecutiveMessages && rowSuppressesRepeatedAuthor,
                    ) {
                        ThreadMessageCard(
                            message = displayMessage,
                            messageBodyTextStyle = messageBodyTextStyle,
                            replyMetadata = replyMetadata,
                            sourceTasks = sourceTasks,
                            onOpenSourceTask = { onThreadEvent("openSourceTask", it, null, null) },
                            hasReplyDraft = activeReplyDraftParentMessageIds.hasReplyComposerDraft(message.id),
                            actionCard = actionCard,
                            messageIndex = absoluteMessageIndex,
                            diagnosticsLogger = diagnosticsLogger,
                            highlightedMentions = highlightedMentions,
                            agentStatusResolver = agentStatusResolver,
                            memberAvatarResolver = memberAvatarResolver,
                            memberModelLabelResolver = memberModelLabelResolver,
                            inlineReplyParticipantsById = inlineReplyParticipantsById,
                            focused = activeFocusedMessageId == message.id,
                            searchMatch = activeInlineSearchMessageId == message.id,
                            popupOpen = menuState.actionMessageId == message.id || menuState.quickReactionBar?.messageId == message.id,
                            hoverToolbar =
                                run {
                                    val hoverCapabilities =
                                        threadMessageMenuCapabilities(
                                            message = message,
                                            replyMetadata = replyMetadata,
                                            slice = renderPayload,
                                            writeActionsEnabled = writeActionsEnabled
                                        )
                                    val hoverSelectionHost = LocalThreadInPlaceTextSelectionHost.current
                                    val hoverInsertQuote = hoverSelectionHost?.insertQuoteIntoComposer
                                    val hoverQuoteFallback = hoverSelectionHost?.quoteIntoComposer
                                    val hoverPendingQuote =
                                        if (
                                            hoverCapabilities.copyText &&
                                            (hoverInsertQuote != null || hoverQuoteFallback != null)
                                        ) {
                                            threadMessageMenuPendingQuote(message, message.localizedAuthorName())
                                        } else {
                                            null
                                        }
                                    threadMessageHoverToolbarActions(
                                        message = message,
                                        capabilities = hoverCapabilities,
                                        onShowReactions = { anchor ->
                                            if (menuState.actionMessageId == message.id) {
                                                menuState.actionMessageId = null
                                                menuState.classicMenuOverrideMessageId = null
                                            }
                                            RaftProductEvents.messageActionSelected(
                                                RaftMessageAction.OpenReactionPicker,
                                                inThread = replyInThread,
                                                surface = RaftMessageActionSurface.HoverToolbar,
                                            )
                                            menuState.quickReactionBar =
                                                ThreadQuickReactionBarRequest(message.id, anchor, fromHoverToolbar = true)
                                        },
                                        onQuickReact = { emoji ->
                                            if (menuState.quickReactionBar?.messageId == message.id) {
                                                menuState.quickReactionBar = null
                                            }
                                            if (menuState.actionMessageId == message.id) {
                                                menuState.actionMessageId = null
                                                menuState.classicMenuOverrideMessageId = null
                                            }
                                            RaftProductEvents.messageActionSelected(
                                                RaftMessageAction.ReactQuick,
                                                inThread = replyInThread,
                                                surface = RaftMessageActionSurface.HoverToolbar,
                                            )
                                            onThreadEventKeepingAnchor("toggleReaction", message.id, null, emoji)
                                        },
                                        quote = hoverPendingQuote != null,
                                        onQuote = {
                                            if (hoverPendingQuote != null) {
                                                if (menuState.quickReactionBar?.messageId == message.id) {
                                                    menuState.quickReactionBar = null
                                                }
                                                if (menuState.actionMessageId == message.id) {
                                                    menuState.actionMessageId = null
                                                    menuState.classicMenuOverrideMessageId = null
                                                }
                                                RaftProductEvents.messageActionSelected(
                                                    RaftMessageAction.Quote,
                                                    inThread = replyInThread,
                                                    surface = RaftMessageActionSurface.HoverToolbar,
                                                )
                                                val insert =
                                                    ThreadComposerQuoteInsert(
                                                        quote = hoverPendingQuote,
                                                        fallbackMarkdown = formatThreadMessageQuote(hoverPendingQuote.excerpt),
                                                    )
                                                if (hoverInsertQuote != null) {
                                                    hoverInsertQuote(insert)
                                                } else {
                                                    threadMessageMenuQuote(message)?.let { hoverQuoteFallback?.invoke(it) }
                                                }
                                            }
                                        },
                                        onForward = { onForwardMessage(message.id) },
                                        onToggleSave = { onThreadEventKeepingAnchor("toggleMessageSaved", message.id, null, null) },
                                        onShownChange = { shown ->
                                            if (shown) {
                                                menuState.hoverToolbarMessageId = message.id
                                            } else if (menuState.hoverToolbarMessageId == message.id) {
                                                menuState.hoverToolbarMessageId = null
                                            }
                                        }
                                    )
                                },
                            shareSelectionActive = shareSelectionActive,
                            selectedForShare = message.id in selectedShareMessageIds,
                            onToggleShareSelection = { onToggleShareSelection(message.id) },
                            showSaveAction = !captureShareBounds,
                            // Defer this snapshot read to the avatar leaf. Reading it at the
                            // card boundary invalidates every visible message while anchoring.
                            isMessageListScrolling = { listState.isScrollInProgress },
                            alignOwnMessageRight = renderPayload.alignOwnMessagesRight && message.isOwn,
                            messageAlignmentModeEnabled = renderPayload.alignOwnMessagesRight,
                            compactConsecutiveMessages = renderPayload.compactConsecutiveMessages,
                            suppressRepeatedAuthor = rowSuppressesRepeatedAuthor,
                            showReplyAction = renderPayload.showReplyActions,
                            allowStartThreadReply = renderPayload.allowStartThreadReply,
                            quickReactionsEnabled = threadMessageCanQuickReact(message, renderPayload, writeActionsEnabled),
                            resolvedTaskAssigneeName = linkedTaskAssigneeName,
                            resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                            markdownTagContextSignature = markdownTagContextSignature,
                            activeMarkdownTagKey = activeMarkdownTagKey,
                            taskOpenEnabled = renderPayload.showTaskOpenActions && isRichContentEnabled,
                            taskStatusActionsEnabled = renderPayload.showTaskStatusActions && isRichContentEnabled,
                            isMarkdownTagValid = isMarkdownTagValid,
                            onShowActions = { anchor ->
                                if (!message.hasLocalSendState() || message.isFailedLocalSend() || message.isSendingLocalSend()) {
                                    menuState.actionMenuAnchor = anchor
                                    menuState.actionMessageId = message.id
                                    menuState.classicMenuOverrideMessageId = null
                                    menuState.actionMenuFromLongPress = false
                                    diagnosticsLogger("thread_message_menu_tap messageId=${message.id}")
                                }
                            },
                            onLongPressMenuOpened = {
                                if (menuState.actionMessageId == message.id) {
                                    menuState.actionMenuFromLongPress = true
                                    RaftProductEvents.messageActionSelected(
                                        RaftMessageAction.OpenMenu,
                                        threadMessageIsReplyInThread(
                                            renderPayload.replyThreadSurface,
                                            renderPayload.originMessage?.id,
                                            message.id
                                        )
                                    )
                                }
                            },
                            onOpenMember = onOpenMember,
                            onExternalAuthor = onExternalAuthor,
                            onOpenThread = { onThreadEvent("openMessageThread", it, null, null) },
                            onShowQuickReactions = { anchor ->
                                menuState.quickReactionBar = ThreadQuickReactionBarRequest(message.id, anchor)
                            },
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onOpenTaskStatusMenu = { anchor ->
                                menuState.taskStatusMenuMessageId = message.id
                                menuState.taskStatusMenuAnchor = anchor
                            },
                            onToggleReaction = { emoji -> onThreadEventKeepingAnchor("toggleReaction", message.id, null, emoji) },
                            onToggleSave = { onThreadEventKeepingAnchor("toggleMessageSaved", message.id, null, null) },
                            showReactionReactors = true,
                            onShowAuthorDescription = { description, anchor ->
                                menuState.authorDescriptionPopover =
                                    ThreadAuthorDescriptionPopoverRequest(
                                        description = description,
                                        anchor = anchor
                                    )
                            },
                            showReactionAddAction = renderPayload.showReactionActions && !message.hasLocalSendState(),
                            onOpenCommentHost = { onThreadEvent("openCommentHost", message.id, null, null) },
                            timestampLabel = message.timestampLabel,
                            compactTimestampLabel = renderConfig.formatCompactTimestampLabel(message),
                            actionCardExecutedTimeLabel =
                                renderConfig.formatActionCardExecutedTimeLabel(
                                    message.threadActionCard()?.executedAt,
                                    liveTodayEpochDay,
                                    messageTime
                                ),
                            onOpenAttachment = { onThreadEvent("openAttachment", null, it, null) },
                            onDownloadAttachment = { onThreadEvent("downloadAttachment", null, it, null) },
                            onRetryLocalSend = { onThreadEvent("retryLocalSend", message.id, null, null) },
                            onCopyLocalSendFailureDetails = {
                                onThreadEvent("copyLocalSendFailureDetails", message.id, null, null)
                            },
                            attachmentOpenStates = if (isRichContentEnabled) attachmentOpenStates else emptyMap(),
                            pendingActionCardIds = if (isRichContentEnabled) pendingActionCardIds else emptySet(),
                            writeActionsEnabled = writeActionsEnabled,
                            onCommitActionCard = { onThreadEvent("commitActionCard", it, null, null) },
                            onOpenActionCardChannel = { onThreadEvent("openActionCardChannel", it, null, null) },
                            currentServerSlug = currentServerSlug,
                            quotedPermalinkLoader = quotedPermalinkLoader,
                            onOpenMarkdownTag = onOpenMarkdownTag,
                            onOpenMarkdownUrl = onOpenMarkdownUrl,
                            onMentionAuthor = onMentionAuthor,
                            richContentEnabled = isRichContentEnabled,
                            translation = translationUi,
                            onToggleTranslationOriginal = {
                                translationStore?.setShowOriginal(
                                    message.id,
                                    !(translationUi?.showOriginal ?: false)
                                )
                            },
                            onRetryTranslation = {
                                translationStore?.retryTranslation(displayMessage.toTranslationCandidate())
                            },
                            onOpenForwardBundle = onOpenForwardBundle,
                            longMessageCollapse =
                                longMessageCollapseController.takeIf {
                                    longMessageCollapseEnabled && !shareSelectionCaptureActive
                                },
                            onToggleLongMessage = {
                                toggleLongMessage(
                                    message.id,
                                    renderPayload.threadMessageListItemIndex(message.id)
                                )
                            },
                            longPressGestureInstanceKey =
                                message.messageListItemKey(duplicateMessageRandomIds),
                            // A quote card's tap (web useJumpToQuotedMessage): the thread's first
                            // message scrolls to its row as the timeline's jump does; any other
                            // is focused here, loaded around first.
                            onJumpToQuotedMessage = { quotedMessageId ->
                                listState.noteUserNavigation()
                                if (quotedMessageId == (renderPayload.parentMessageId ?: renderPayload.originMessage?.id)) {
                                    outlineOriginJumpRequestState.value += 1
                                } else {
                                    onThreadEvent(ThreadOutlineJumpAction, quotedMessageId, null, null)
                                }
                            }
                        )
                    }
                }
            }
            // read_receipts_v0 (web option C): a DM's "Read" line under its last message.
            if (message.id == dmReadStatusMessageId && peerReadReceipts?.showsDmReadStatus(displayMessage) == true) {
                // The card already ends in its 8dp bottom padding, which stands in for web `mt-1`.
                ThreadDmReadStatusRow(
                    Modifier.padding(start = RaftAppSpacing.Sm, end = RaftAppSpacing.Sm, bottom = RaftAppSpacing.Xs)
                )
            }
        }
    }
    }
    threadListSegments += {
    if (renderPayload.canLoadMoreAfter) {
        item(key = ThreadNewerMessagesListItemKey) {
            ThreadNewerMessagesBoundaryRow(
                newerMessagesStatus = renderPayload.newerMessagesStatus,
                onRetry = { requestNewerPage("retryLoadNewer") },
                suppressLoadingIndicator =
                    shouldSuppressThreadNewerBoundaryLoading(
                        visibleLoadingSlot = visibleBoundaryLoadingSlot,
                        replyLoadingSkeletonVisible = replyLoadingSkeletonVisible,
                    ),
            )
        }
    }
    }
    threadListSegments += {
    item(key = ThreadBottomScrollPaddingListItemKey) {
        Column(Modifier.fillMaxWidth()) {
        // No band for the assistant's otter: it lies over the list's end, so the list
        // never moves when it comes or goes (artin 11-03); a reader scrolls if it covers.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    // The list's end, and the typing line's slot only while the line
                    // shows (web AgentTypingListEnd). The bottom-up list keeps this row's
                    // bottom where it is, so a reader resting on the end rides up and down
                    // with it and one scrolled into the history never sees it change (the
                    // typing line's SideEffect keeps the viewport's intent in step,
                    // threadTypingLineEndFollowsLatest). The scroll owners read the same
                    // value.
                    bottomSpacerAndAnchorTrailingHeight +
                        (if (shareSelectionCaptureTailPaddingActive) {
                            ThreadShareCaptureBottomScrollPaddingHeight
                        } else {
                            0.dp
                        })
                )
        )
        }
    }
    }
    // Reversed (bottom-up) layout emits the same rows newest-first: physical item 0 is the
    // bottom padding row, the messages count down, the origin card comes last (at the top).
    (if (reverseLayout) threadListSegments.asReversed() else threadListSegments).forEach { segment -> segment() }
}

private const val ThreadNewerMessagesListItemKey = "thread-page:newer-messages"
private const val ThreadReplyEmptyListItemKey = "thread-page:reply-empty"
private const val ThreadReplyLoadingListItemKey = "thread-page:reply-loading"
private val ThreadShareCaptureBottomScrollPaddingHeight = 1200.dp
