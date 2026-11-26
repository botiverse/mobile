package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.assistant.LocalThreadAssistantOtter
import build.raft.app.assistant.ThreadAssistantOtter
import build.raft.app.assistant.newAssistantOtterHostId
import build.raft.app.assistant.rememberThreadAssistantOtter
import build.raft.app.base.BridgeModule
import build.raft.app.data.RaftKnownEntityResolver
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationDetailsViewModel
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskConversationHeaderViewModel
import build.raft.app.data.TasksUiState
import build.raft.app.data.appLinkViewerKey
import build.raft.app.data.homeStateForPage
import build.raft.app.markdown.LocalRaftMarkdownPlainCandidateSession
import build.raft.app.markdown.LocalRaftMarkdownRenderConfig
import build.raft.app.markdown.LocalRaftMarkdownTaskRefStatuses
import build.raft.app.markdown.LocalRaftMarkdownTaskStatuses
import build.raft.app.markdown.LocalRaftMarkdownTaskStatusesPending
import build.raft.app.markdown.LocalRaftMessagePermalinkLabelResolver
import build.raft.app.markdown.RaftMarkdownPlainCandidateSession
import build.raft.app.markdown.RaftMarkdownRenderConfig
import build.raft.app.markdown.RaftMessagePermalinkLabelResolver
import build.raft.app.markdown.raftMarkdownProvidedValuesWith
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary

/**
 * The thread page's feature hosts: in-place text selection, peer read receipts, agent typing, the
 * assistant's otter, app link previews, action card names, the member labels and preview cards, the
 * task conversation it shows, and the quote reply host. Called every composition of the page's
 * feature slots from where this code stood, so its remembers keep their keys and their place; only
 * the holder is new each time.
 */
internal class ThreadFeatureHosts(
    val inPlaceTextSelectionHost: ThreadInPlaceTextSelectionHost,
    val peerReadReceipts: ThreadPeerReadReceiptsUi?,
    val agentTyping: ThreadAgentTypingUi?,
    val assistantOtter: ThreadAssistantOtter?,
    val appLinkPreviewHost: ThreadAppLinkPreviewHost?,
    val actionCardNames: ThreadActionCardNameLookup,
    val channelJointServers: List<build.raft.app.model.MemberServerRef>,
    val memberModelLabelResolver: RaftMemberModelLabelResolver,
    val memberPreviewCardSource: RaftMemberPreviewCardSource,
    val viewerServerLogos: Map<String, String>,
    val taskConversationId: ChannelId?,
    val taskConversationHeaderViewModel: TaskConversationHeaderViewModel?,
    val taskConversationHeaderState: TaskConversationHeaderState?,
    val taskConversationParentChannelId: ChannelId?,
    val taskConversationTasksViewModel: SharedTasksViewModel?,
    val taskConversationTasksState: TasksUiState?,
    val taskConversationTask: TaskSummary?,
    val threadTaskTitlesByNumber: Map<Int, String>,
    val taskConversationDetailsViewModel: TaskConversationDetailsViewModel?,
    val taskConversationDetailsState: TaskConversationDetailsState,
    val taskConversationJointSurface: Boolean,
    val effectiveMessageAvatarResolver: RaftMemberAvatarResolver,
    val quoteReplyHost: ThreadQuoteReplyHost,
)

/** The thread page's feature hosts: see [ThreadFeatureHosts]. */
@Composable
internal fun rememberThreadFeatureHosts(
    bridge: BridgeModule,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    active: Boolean,
    currentSession: StoredAuthSession?,
    currentServerId: String?,
    newMessageMenuEnabled: Boolean,
    copiedLabel: String,
    renderedThread: RaftThreadScreenPayload,
    renderedTimelineSlice: ThreadTimelineSlice,
    initialRouteIdentity: ThreadRouteIdentity,
    serverMembersStore: SharedServerMembersStore?,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpenMemberProfile: (RaftThreadScreenPayload, ThreadTimelineSlice, MemberDetailIdentity) -> Unit,
    sharedHomeState: SharedHomeState,
    threadViewerIsGuest: Boolean,
    threadServerMembersEntry: SharedServerMembersEntry,
    knownEntityResolver: RaftKnownEntityResolver,
    knownEntityAccountId: String?,
    computersStore: SharedComputersStore?,
    conversationDirectoryLookupIds: List<String>,
    taskConversation: ThreadTaskConversationState,
    headerProjectionServerId: ServerId?,
    billingDirectoryProjections: Map<String, SharedConversationDirectoryProjection>,
    conversationIdentityProvider: SharedConversationIdentityProvider?,
    memberAvatarResolver: RaftMemberAvatarResolver,
    threadMembersServerId: ServerId?,
    threadMembersChannelId: ChannelId?,
    markdownTaskContextProjectionSlice: SharedTasksProjectionSlice,
    serverTaskProjectionSlice: SharedTasksProjectionSlice,
    markdownTaskContextChannelId: ChannelId?,
    jointMessageSurface: Boolean,
    messageAvatarResolver: RaftMemberAvatarResolver,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    threadViewModel: SharedThreadViewModel,
): ThreadFeatureHosts {
    // New Message Menu toggle: message bodies become selectable in place (ThreadInPlaceTextSelection.kt).
    // Always on where the pointer selects text (desktop: drag, double-click, Cmd+C, like the web).
    val inPlaceSelectionEnabled = newMessageMenuEnabled || raftPlatformMessageTextPointerSelectable()
    val inPlaceTextSelectionHost =
        remember(inPlaceSelectionEnabled, bridge, copiedLabel) {
            ThreadInPlaceTextSelectionHost(
                enabled = inPlaceSelectionEnabled,
                // Web: copying a selection shows nothing (artin 2026-11-02).
                copyText = { text -> bridge.copyToPasteboard(text) }
            )
        }
    // read_receipts_v0: other participants' read positions for this page's scope (null = no hydrate).
    val peerReadReceipts =
        rememberThreadPeerReadReceipts(
            payload = renderedThread,
            sharedStores = sharedStores,
            storeScope = storeScope,
            serverId = currentServerId,
            accountId = currentSession?.user?.id,
            active = active,
            serverMembersStore = serverMembersStore,
        )
    // The line above the composer: who is about to post into this conversation and, in a
    // channel, who of its agents is working (null = nobody). A Working now row opens the agent.
    val agentTyping =
        rememberThreadAgentTyping(
            payload = renderedThread,
            sharedStores = sharedStores,
            storeScope = storeScope,
            serverId = currentServerId,
            serverMembersStore = serverMembersStore,
            agentStatusResolver = agentStatusResolver,
            openAgent = { agentId ->
                memberDetailIdentity(agentId, MemberKind.Agent, currentServerId)?.let { identity ->
                    onOpenMemberProfile(renderedThread, renderedTimelineSlice, identity)
                }
            },
        )
    // The personal assistant's otter (web AssistantOtterAnchor): on this conversation's composer
    // when the assistant is ready with its DM, never on that DM or a thread in it.
    val assistantOtterHostId = remember { newAssistantOtterHostId() }
    val assistantOtterInSplitPane = threadInSplitDetailPane()
    val assistantOtterReplyThread = renderedThread.surfaceKind == RaftThreadSurfaceKind.ReplyThread
    val assistantOtter =
        rememberThreadAssistantOtter(
            store = remember(sharedStores, storeScope) { sharedStores.myAssistantStore(storeScope) },
            featureFlags = sharedStores.serverFeatureFlags,
            serverMembersStore = serverMembersStore,
            homeState = sharedHomeState,
            serverId = currentServerId,
            userId = currentSession?.user?.id,
            viewerIsGuest = threadViewerIsGuest,
            conversationChannelId =
                if (assistantOtterReplyThread) renderedThread.parentChannelId else renderedThread.streamChannelId,
            hostId = assistantOtterHostId,
            wide = assistantOtterInSplitPane,
            replyThread = assistantOtterReplyThread,
            pageActive = active,
            // A thread's composer also names the thread: opened from here, the assistant's DM
            // attaches it (web ThreadPanel's AssistantOtterAnchor threadParentMessageId/Text).
            threadParentMessageId =
                renderedThread.parentMessageId?.takeIf { assistantOtterReplyThread && it.isNotBlank() }
                    ?: renderedThread.originParentMessageId?.takeIf { assistantOtterReplyThread && it.isNotBlank() },
            threadParentText =
                renderedThread.originMessage
                    ?.takeIf { message ->
                        assistantOtterReplyThread &&
                            message.id == (renderedThread.parentMessageId ?: renderedThread.originParentMessageId)
                    }
                    ?.body,
        )
    // App link previews (artin #20 lane L): this viewer on this Server, asked once per conversation.
    val appLinkPreviewViewerId = currentSession?.user?.id?.takeIf(String::isNotBlank)
    val appLinkPreviewConversationId = renderedThread.streamChannelId ?: initialRouteIdentity.instanceKey
    val appLinkPreviewHost =
        remember(sharedStores, appLinkPreviewViewerId, currentServerId, appLinkPreviewConversationId) {
            val viewerId = appLinkPreviewViewerId
            val serverId = currentServerId
            if (viewerId == null || serverId == null) {
                null
            } else {
                ThreadAppLinkPreviewHost(
                    store = sharedStores.appLinkPreviewStore,
                    viewerKey = appLinkViewerKey(viewerId, serverId),
                    serverId = serverId,
                    conversationId = appLinkPreviewConversationId,
                    toast = { message -> bridge.toast(message) },
                    toastWithIntent = { message, intent -> bridge.toastWithIntent(message, intent) },
                    toastWithAction = { message, actionLabel, onAction -> bridge.toastWithAction(message, actionLabel, onAction) },
                    errorToast = { message, traceId -> bridge.errorToast(message, traceId) },
                    copyText = { text -> bridge.copyToPasteboard(text) },
                    // Workspace Artifacts links (behind workspace_artifacts_v0, read per message).
                    artifacts =
                        ThreadArtifactLinkHost(
                            summaries = sharedStores.artifactSummaryStore,
                            appHosts = sharedStores.clientConfigStore.appHosts,
                            featureFlags = sharedStores.serverFeatureFlags,
                            webBaseUrl = sharedStores.apiEnvironmentStore.current().webUrl,
                            screenshots = sharedStores.artifactScreenshotStore,
                            readerId = viewerId,
                            follows = sharedStores.artifactFollowStore,
                        ),
                    // The batched link-card read (App link cards v2).
                    batchedRead = true,
                    installedAppOrigins = sharedStores.threadInstalledAppOriginResolver(viewerId, serverId),
                )
            }
        }
    // Action cards store ids; show the names this server's roster (and channels) already hold.
    val actionCardNames =
        rememberThreadActionCardNameLookup(
            members = threadServerMembersEntry,
            serverId = currentServerId,
            jointActors = serverMembersStore?.jointActors,
            knownEntities = knownEntityResolver,
            accountId = knownEntityAccountId,
            knownEntitiesRevision = sharedHomeState.content,
            // Computers are named from this server's list only (botiverse/slock #14234).
            computersStore = computersStore,
        )
    // The profile card an @mention in a message, or an author's avatar, opens under a mouse or
    // trackpad pointer (web MentionLink): a mention is the member its click opens.
    val currentMentionThread by rememberUpdatedState(renderedThread)
    // Connected servers of this external channel (or parent channel for reply threads / tasks):
    // feeds server logos & names into message authors, @ badges, and profile preview cards.
    val channelJointServers =
        rememberThreadChannelJointServers(
            lookupIds = conversationDirectoryLookupIds,
            parentChannelId = renderedThread.parentChannelId,
            taskParentChannelId = taskConversation.parentChannelId?.value,
            serverId = headerProjectionServerId?.value,
            projections = billingDirectoryProjections,
            conversationIdentityProvider = conversationIdentityProvider,
            homeContentRevision = sharedHomeState.content,
        )
    val memberModelLabelResolver =
        remember(serverMembersStore, headerProjectionServerId, channelJointServers) {
            raftMemberModelLabelResolver(
                serverMembersStore = serverMembersStore,
                serverId = headerProjectionServerId?.value,
                knownServers = channelJointServers,
            )
        }
    val memberPreviewCardSource =
        remember(serverMembersStore, headerProjectionServerId, agentStatusResolver, memberAvatarResolver, threadMembersServerId, threadMembersChannelId, channelJointServers) {
            RaftMemberPreviewCardSource(
                serverMembersStore = serverMembersStore,
                serverId = headerProjectionServerId?.value,
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                knownServers = channelJointServers,
                resolveMention = { label ->
                    val serverMembers =
                        if (serverMembersStore == null || threadMembersServerId == null) {
                            emptyList()
                        } else {
                            serverMembersStore.currentServerMembers(threadMembersServerId) +
                                threadMembersChannelId
                                    ?.let { serverMembersStore.currentChannelMembers(threadMembersServerId, it) }
                                    .orEmpty()
                        }
                    currentMentionThread.resolveMarkdownMentionOpenTarget(label, serverMembers)?.identity
                },
            )
        }
    // Member labels (web #14535/#15817): viewer's joined servers overlaid with this external
    // channel's `jointServers[].serverAvatarUrl` so badges show other servers' logos too.
    val viewerServerLogos =
        remember(currentSession?.servers, channelJointServers) {
            raftOriginServerLogos(currentSession?.servers.orEmpty().map { it.id to it.avatarUrl }, channelJointServers)
        }
    val taskConversationId = taskConversation.conversationId
    val taskConversationHeaderViewModel = taskConversation.headerViewModel
    val taskConversationHeaderState = taskConversation.headerState
    val taskConversationParentChannelId = taskConversation.parentChannelId
    val taskConversationTasksViewModel = taskConversation.tasksViewModel
    val taskConversationTasksState = taskConversation.tasksState
    val taskConversationTask = taskConversation.task
    val threadTaskTitlesByNumber =
        remember(
            markdownTaskContextProjectionSlice.tasks,
            serverTaskProjectionSlice.tasks,
            markdownTaskContextChannelId,
            taskConversationTask,
        ) {
            threadTaskTitlesByNumber(
                channelTasks = markdownTaskContextProjectionSlice.tasks,
                serverTasks = serverTaskProjectionSlice.tasks,
                currentChannelId = markdownTaskContextChannelId?.value,
                currentTask = taskConversationTask,
            )
        }
    val taskConversationDetailsViewModel = taskConversation.detailsViewModel
    val taskConversationDetailsState = taskConversation.detailsState
    val taskConversationJointSurface =
        jointMessageSurface ||
            parseConversationKindOrNull(taskConversationTask?.channelType) == ConversationKind.Joint ||
            (taskConversationParentChannelId?.value?.let { id ->
                currentServerId?.let(::ServerId)?.let { server ->
                    conversationIdentityProvider?.resolveProjection(server, id)?.conversationKind == ConversationKind.Joint
                }
            } == true)
    val effectiveMessageAvatarResolver =
        remember(messageAvatarResolver, taskConversationJointSurface) {
            messageAvatarResolver.forJointSurface(taskConversationJointSurface)
        }
    val quoteReplyHost =
        rememberThreadQuoteReplyHost(
            serverId = currentServerId,
            renderedThread = renderedThread,
            timelineMessages = renderedTimelineSlice.messages,
            serverMembersStore = serverMembersStore,
            currentSession = currentSession,
            memberAvatarResolver = effectiveMessageAvatarResolver,
            composerDraftViewModel = composerDraftViewModel,
            threadViewModel = threadViewModel,
            bridge = bridge,
        )
    return ThreadFeatureHosts(
        inPlaceTextSelectionHost = inPlaceTextSelectionHost,
        peerReadReceipts = peerReadReceipts,
        agentTyping = agentTyping,
        assistantOtter = assistantOtter,
        appLinkPreviewHost = appLinkPreviewHost,
        actionCardNames = actionCardNames,
        channelJointServers = channelJointServers,
        memberModelLabelResolver = memberModelLabelResolver,
        memberPreviewCardSource = memberPreviewCardSource,
        viewerServerLogos = viewerServerLogos,
        taskConversationId = taskConversationId,
        taskConversationHeaderViewModel = taskConversationHeaderViewModel,
        taskConversationHeaderState = taskConversationHeaderState,
        taskConversationParentChannelId = taskConversationParentChannelId,
        taskConversationTasksViewModel = taskConversationTasksViewModel,
        taskConversationTasksState = taskConversationTasksState,
        taskConversationTask = taskConversationTask,
        threadTaskTitlesByNumber = threadTaskTitlesByNumber,
        taskConversationDetailsViewModel = taskConversationDetailsViewModel,
        taskConversationDetailsState = taskConversationDetailsState,
        taskConversationJointSurface = taskConversationJointSurface,
        effectiveMessageAvatarResolver = effectiveMessageAvatarResolver,
        quoteReplyHost = quoteReplyHost,
    )
}

/**
 * The composition locals the thread page's feature slots provide, built from the feature hosts and
 * the page's values. Called every composition of the feature slots from where this code stood, so
 * the remembers inside keep their keys and their place.
 */
@Composable
internal fun rememberThreadProvidedValues(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    bridge: BridgeModule,
    currentSession: StoredAuthSession?,
    currentServerId: String?,
    headerProjectionServerId: ServerId?,
    renderedThread: RaftThreadScreenPayload,
    sharedHomeStore: SharedHomeStore?,
    quoteReplyHost: ThreadQuoteReplyHost,
    viewerServerLogos: Map<String, String>,
    channelJointServers: List<build.raft.app.model.MemberServerRef>,
    memberPreviewCardSource: RaftMemberPreviewCardSource,
    appLinkPreviewHost: ThreadAppLinkPreviewHost?,
    inPlaceTextSelectionHost: ThreadInPlaceTextSelectionHost,
    peerReadReceipts: ThreadPeerReadReceiptsUi?,
    agentTyping: ThreadAgentTypingUi?,
    assistantOtter: ThreadAssistantOtter?,
    threadRouteAttachmentActions: ThreadRouteAttachmentActions,
    attachmentDownloadHost: ThreadAttachmentDownloadHost?,
    messagePermalinkLabelResolver: RaftMessagePermalinkLabelResolver,
    markdownTaskStatuses: Map<Int, TaskStatus>?,
    markdownTaskRefStatuses: Map<String, TaskStatus>,
    threadTaskTitlesByNumber: Map<Int, String>,
    markdownTaskContextChannelId: ChannelId?,
    singleParagraphLightPathEnabled: Boolean,
    threadReverseLayoutEnabled: Boolean,
    feedbackCardEntryEnabled: Boolean,
    taskConversationJointSurface: Boolean,
    messageBodyOwnerKey: String?,
    actionCardNames: ThreadActionCardNameLookup,
    threadServerMembersEntry: SharedServerMembersEntry,
    threadChannelMembersSlice: SharedServerChannelMembersSlice,
    threadMembersServerId: ServerId?,
    serverMembersStore: SharedServerMembersStore?,
    actionCardSpecHost: ThreadActionCardSpecHost,
    threadKeyboardTapDismissal: ThreadKeyboardTapDismissal,
    threadPointerSessionKey: Int,
    threadMessageLongPressGestureOwnerStore: ThreadMessageLongPressGestureOwnerStore,
    pendingReactionVisuals: Map<ThreadReactionPendingKey, ThreadReactionPendingVisual>,
    memberAppBotResolver: RaftMemberAppBotResolver,
    timeContextProvider: RaftTimeContextProvider,
    plainCandidateCensusSession: RaftMarkdownPlainCandidateSession?,
): Array<ProvidedValue<*>> {
    val threadProvidedValues = raftMarkdownProvidedValuesWith(
        arrayOf(
            LocalPhoneConversationHeaderPrimaryConfig provides
                rememberPhoneConversationHeaderPrimaryConfig(
                    flags = sharedStores.serverFeatureFlags,
                    serverId = headerProjectionServerId,
                ),
            LocalThreadQuoteReplyHost provides quoteReplyHost,
            LocalRaftViewerServerLogos provides viewerServerLogos,
            LocalRaftChannelJointServers provides channelJointServers,
            // One channel-kind glyph per channel (forward targets, composer suggestions). Read
            // from values this lambda already captures (see the JVM-limit note below).
            LocalRaftChannelKinds provides rememberRaftChannelKindsState(sharedHomeStore.homeStateForPage()),
            LocalRaftMemberPreviewCardSource provides memberPreviewCardSource,
            LocalThreadAppLinkPreviewHost provides appLinkPreviewHost,
            // The conversation its Artifacts (cards, links, Files) are opened from (`hostConversation`).
            // From values this lambda already captures (see the JVM-limit note below).
            LocalRaftArtifactOpenConversation provides renderedThread.artifactOpenConversationId(),
            LocalThreadInPlaceTextSelectionHost provides inPlaceTextSelectionHost,
            LocalThreadPeerReadReceipts provides peerReadReceipts,
            LocalThreadAgentTyping provides agentTyping,
            LocalThreadAssistantOtter provides assistantOtter,
            // The assistant DM's receipt and WeChat notice cards. Read from values this lambda
            // already captures (see the JVM-limit note below).
            LocalThreadAssistantDmCards provides
                rememberThreadAssistantDmCardsHost(
                    sharedStores = sharedStores,
                    storeScope = storeScope,
                    serverId = currentServerId,
                    userId = currentSession?.user?.id,
                    conversationChannelId =
                        renderedThread.streamChannelId.takeIf { renderedThread.surfaceKind != RaftThreadSurfaceKind.ReplyThread },
                    bridge = bridge,
                ),
            LocalThreadInlineVideoPreviewHost provides threadRouteAttachmentActions.inlineVideoPreviewHost(),
            LocalThreadInlineAudioPreviewHost provides threadRouteAttachmentActions.inlineAudioPreviewHost(),
            LocalThreadInlineImageFallbackHost provides threadRouteAttachmentActions.inlineImageFallbackHost(),
            LocalThreadAttachmentDownloadHost provides attachmentDownloadHost,
            LocalRaftMessagePermalinkLabelResolver provides messagePermalinkLabelResolver,
            LocalRaftMarkdownTaskStatuses provides markdownTaskStatuses,
            LocalRaftMarkdownTaskRefStatuses provides markdownTaskRefStatuses,
            LocalThreadTaskTitlesByNumber provides threadTaskTitlesByNumber,
            // The conversation has a task source that has not loaded yet: task refs keep the
            // status chip's shape in a neutral look until it does.
            LocalRaftMarkdownTaskStatusesPending provides
                (markdownTaskStatuses == null && markdownTaskContextChannelId != null),
            // web MessageMarkdownBody: a Raft message permalink in a message body is a
            // PermalinkReferenceChip (link icon, the conversation, a mono "msg"), not a bare URL.
            LocalRaftMarkdownRenderConfig provides
                RaftMarkdownRenderConfig(
                    renderMessagePermalinksAsTag = true,
                    singleParagraphLightPath = singleParagraphLightPathEnabled,
                ),
            LocalThreadReverseLayout provides threadReverseLayoutEnabled,
            LocalThreadFeedbackCardEntryEnabled provides feedbackCardEntryEnabled,
            LocalThreadViewerServerId provides currentServerId,
            // A joint channel's conversation (including a task conversation in one, slock #14517):
            // every person's face (hand-off card, task head, typing, followers, composer chips)
            // reads the other server's people as its message rows do.
            LocalRaftJointActorSurface provides taskConversationJointSurface,
            // The Add Reaction picker keeps its skin tone and frequently used emoji per account.
            LocalRaftEmojiPickerOwnerKey provides messageBodyOwnerKey,
            LocalThreadActionCardNames provides actionCardNames,
            LocalPendingMentionTargetMap provides
                rememberThreadPendingMentionTargetMap(
                    serverMembersEntry = threadServerMembersEntry,
                    channelMembersSlice = threadChannelMembersSlice,
                    payloadMembers = renderedThread.members,
                    payloadMemberCandidates = renderedThread.memberCandidates,
                    serverId = threadMembersServerId,
                    jointActors = serverMembersStore?.jointActors,
                ),
            LocalThreadActionCardSpecHost provides actionCardSpecHost,
            LocalThreadKeyboardTapDismissal provides threadKeyboardTapDismissal,
            LocalThreadMessagePointerSessionKey provides threadPointerSessionKey,
            LocalThreadMessageLongPressGestureOwnerStore provides
                threadMessageLongPressGestureOwnerStore,
            LocalThreadPendingReactionVisuals provides pendingReactionVisuals,
            LocalRaftMemberAppBotResolver provides memberAppBotResolver,
            LocalRaftTimeContextProvider provides timeContextProvider
        ),
        LocalRaftMarkdownPlainCandidateSession,
        plainCandidateCensusSession
    )
    return threadProvidedValues
}
