package build.raft.app

import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.AvatarReference
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.InboxItemKind
import build.raft.app.model.Message
import build.raft.app.model.RaftSlackSyncProblem
import build.raft.app.model.MessageId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.threadIdentity
import build.raft.app.settings.AppShellThreadTimeContext
import build.raft.app.settings.ThreadLaunchAccessState
import build.raft.app.settings.buildAppShellThreadPayload
import build.raft.app.settings.buildStandaloneConversationThreadLaunchPayload
import build.raft.app.shell.RAFT_SHELL_SERVER_ID
import build.raft.app.shell.raftShellStoreScopeOrNull
import build.raft.app.shell.withRaftShellStoreScope
import build.raft.app.thread.MarkdownTaskNumberOpenResult
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadPendingSendContext
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.RaftThreadSettingsAction
import build.raft.app.thread.SharedThreadHeaderContextStore
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.ThreadAddMemberRowInteraction
import build.raft.app.thread.ThreadHeaderSearchAction
import build.raft.app.thread.ThreadLinkedTasksProjection
import build.raft.app.thread.ThreadMessageDeliveryFailed
import build.raft.app.thread.ThreadMessageDeliverySending
import build.raft.app.thread.ThreadPickedAttachmentComposerAction
import build.raft.app.thread.ThreadReplyEmptyDetail
import build.raft.app.thread.ThreadReplyEmptyTitle
import build.raft.app.thread.ThreadReplyMetadata
import build.raft.app.thread.ThreadReplyPageTitle
import build.raft.app.thread.ThreadTaskOpenRecoveryReason
import build.raft.app.thread.ThreadTimelineRenderConfig
import build.raft.app.thread.activeRealtimeRoomIds
import build.raft.app.thread.arbitrateThreadTaskOpen
import build.raft.app.thread.buildMarkdownChannelLaunchPayload
import build.raft.app.thread.buildThreadSurfaceComposerPayload
import build.raft.app.thread.canConvertMessagesToTasks
import build.raft.app.thread.canConvertToTask
import build.raft.app.thread.consumeComposerSeedForSend
import build.raft.app.thread.dispatch
import build.raft.app.thread.displaySizeLabel
import build.raft.app.thread.expandedSystemContent
import build.raft.app.thread.expandedSystemDetails
import build.raft.app.thread.findTaskParentMessage
import build.raft.app.thread.formatCompactTimestampLabel
import build.raft.app.thread.formatThreadForwardedTimestampLabel
import build.raft.app.thread.formatTimestampLabel
import build.raft.app.thread.handleThreadStoreRetryEvent
import build.raft.app.thread.isPreviewableVideoAttachment
import build.raft.app.thread.isSystemMessage
import build.raft.app.thread.key
import build.raft.app.thread.memberKey
import build.raft.app.thread.normalizedThreadSettingsChannelActions
import build.raft.app.thread.openMarkdownTaskNumberPayload
import build.raft.app.thread.parseThreadScreenPayload
import build.raft.app.thread.preserveStableTimelineFrom
import build.raft.app.thread.replyParentChannelIdForNestedOpen
import build.raft.app.thread.replyThreadLoadingPayload
import build.raft.app.thread.resolveReplyParentConversationOpenRequest
import build.raft.app.thread.resolveThreadAgentStatusIndicatorState
import build.raft.app.thread.resolveThreadPickedAttachmentComposerAction
import build.raft.app.thread.sanitizeThreadSystemText
import build.raft.app.thread.shouldShowThreadComposerForTab
import build.raft.app.thread.shouldShowThreadFollowAction
import build.raft.app.thread.shouldShowThreadMessageSavedBadge
import build.raft.app.thread.shouldStartThreadTasksJoin
import build.raft.app.thread.shouldStartThreadTasksLoad
import build.raft.app.thread.shouldSuppressCompactThreadAuthor
import build.raft.app.thread.shouldThreadAutoLoadOlderMessages
import build.raft.app.thread.sortForThreadMentionParticipants
import build.raft.app.thread.supportsChannelTasks
import build.raft.app.thread.systemContentSummary
import build.raft.app.thread.taskById
import build.raft.app.thread.threadActivityMuteState
import build.raft.app.thread.threadActivityMutedIndicatorVisible
import build.raft.app.thread.threadAddMemberRowInteraction
import build.raft.app.thread.threadComposerMemberScopedMentions
import build.raft.app.thread.threadComposerMemberSuggestionHeadersVisible
import build.raft.app.thread.threadComposerMessageBodyForSend
import build.raft.app.thread.threadDateDividerLabel
import build.raft.app.thread.threadFindWindow
import build.raft.app.thread.threadHeaderDisplayModelFromDirectory
import build.raft.app.thread.threadHeaderSearchAction
import build.raft.app.thread.threadInlineImageShowsFailure
import build.raft.app.thread.threadLinkedTaskReferences
import build.raft.app.thread.threadLinkedTasksByMessageId
import build.raft.app.thread.threadLinkedTasksByMessageLookup
import build.raft.app.thread.threadLinkedTasksProjection
import build.raft.app.thread.threadMessagePermalink
import build.raft.app.thread.threadMessageTaskMetaLabel
import build.raft.app.thread.threadParentNavigationServerId
import build.raft.app.thread.threadSelfMentionNames
import build.raft.app.thread.toConversationAccessCandidate
import build.raft.app.thread.toMessage
import build.raft.app.thread.toRaftThreadMessageItem
import build.raft.app.thread.toThreadAuthorProfileCandidate
import build.raft.app.thread.toThreadLaunchConversationIdentity
import build.raft.app.thread.toThreadMembersPageData
import build.raft.app.thread.toThreadMembersPageRouteOrNull
import build.raft.app.thread.toThreadTimelineSlice
import build.raft.app.thread.withConversationAccessFallback
import build.raft.app.thread.withLinkedTask
import build.raft.app.thread.withSavedMessageIds
import build.raft.app.thread.withSharedShellEventCapabilities
import build.raft.app.thread.withThreadLinkedTasksProjection
import build.raft.app.toConversationAccessCandidate
import build.raft.app.toThreadLaunchConversationIdentity
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.AuthPendingOpenRequest
import build.raft.app.data.InboxItem
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksDataStoreStatus
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedMembersMemberSuggestion
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.TasksUiState
import build.raft.app.data.TaskSnapshot
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.WireActionCardAction
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.toWireJsonObject
import build.raft.app.displaySizeLabel
import build.raft.app.data.WireAttachment
import build.raft.app.data.WireMessage
import build.raft.app.data.WireReaction
import build.raft.app.data.WireThreadSummary
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.convertMessageToCanonicalTask
import build.raft.app.data.parseSharedThreadUploadedAttachments
import build.raft.app.data.toHomeChannelRow
import androidx.compose.ui.text.font.FontFamily
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import build.raft.app.data.threadFindMatches
import build.raft.app.data.threadFindSearchable
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ThreadPayloadTest {
    private val launchJson = Json { ignoreUnknownKeys = true }

    @Test
    fun threadLaunchPayloadOpensReplyThreadWithMissingParentKind() {
        // task #304/#307 (cae1eb2e4): the parent channel+message tuple alone is
        // enough to open a reply thread; an unresolved parent kind stays null on
        // the payload instead of rejecting the launch.
        val warnings = mutableListOf<String>()
        val identity =
            ThreadLaunchConversationIdentity(
                threadId = ThreadId("thread-1"),
                channelId = ChannelId("parent-1"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("parent-1"),
                        parentMessageId = MessageId("msg-1"),
                        streamChannelId = ChannelId("thread-1"),
                        parentSurfaceKind = null,
                        parentChannelKind = null
                    ),
                kind = ConversationKind.Thread
            )

        val standalone =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = identity,
                timeContext = null,
                onWarn = { warnings.add(it) }
            )
        val appShell =
            buildAppShellThreadPayload(
                conversation = identity,
                onWarn = { warnings.add(it) }
            )

        assertNotNull(standalone)
        assertNotNull(appShell)
        assertTrue(warnings.none { it.contains("reply_identity_incomplete") }, warnings.toString())
    }

    @Test
    fun composerSendBodyFallsBackToAttachmentLabelWhenDraftIsBlank() {
        assertEquals("[1 attachment]", threadComposerMessageBodyForSend("   ", attachmentCount = 1))
        assertEquals("[2 attachments]", threadComposerMessageBodyForSend("", attachmentCount = 2))
    }

    @Test
    fun pickedSingleImageOpensAnnotation() {
        assertEquals(
            ThreadPickedAttachmentComposerAction.OpenImageAnnotation,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachImage",
                fileCount = 1,
            )
        )
    }

    @Test
    fun composerImageButtonMapsTapToLibraryAndLongPressToCamera() {
        assertEquals(ThreadComposerAttachImageAction, threadComposerImageAction(longPress = false))
        assertEquals(ThreadComposerCaptureImageAction, threadComposerImageAction(longPress = true))
    }

    @Test
    fun pickedVideoNeverOpensImageAnnotationEvenFromImagePickerAction() {
        assertEquals(
            ThreadPickedAttachmentComposerAction.UploadToComposer,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachImage",
                fileCount = 1,
                allFilesAreImages = false,
            )
        )
    }

    @Test
    fun threadMentionSuggestionsRankThreadParticipantsBeforeChannelGroups() {
        val suggestions =
            listOf(
                mentionSuggestion("Parent Only", "parent-only", "u-parent-only", inMentionChannel = true),
                mentionSuggestion("Outside Only", "outside-only", "u-outside-only", inMentionChannel = false),
                mentionSuggestion("Thread Parent", "thread-parent", "u-thread-parent", inMentionChannel = true),
                mentionSuggestion("Thread Outside", "thread-outside", "u-thread-outside", inMentionChannel = false),
                mentionSuggestion("Build Agent", "build-agent", "agent-build", inMentionChannel = true, kind = MemberKind.Agent)
            )
        val messages =
            listOf(
                threadMessage("m1", "u-thread-parent", "thread-parent"),
                threadMessage("m2", "u-thread-outside", "thread-outside"),
                threadMessage("m3", "agent-build", "build-agent"),
            )

        val sorted =
            suggestions.sortForThreadMentionParticipants(
                threadMessages = messages,
                prioritizeThreadParticipants = true
            )

        assertEquals(
            listOf("build-agent", "thread-outside", "thread-parent", "parent-only", "outside-only"),
            sorted.map { it.handle }
        )
    }

    @Test
    fun threadMessagePermalinkUsesParentThreadParamForReplyRows() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "c0c0c0c0-0000-4000-8000-000000000001",
                parentMessageId = "a0a0a0a0-0000-4000-8000-000000000004",
                sourceThreadId = "thread-1",
                streamChannelId = "thread-1",
                parentChannelKind = "channel"
            )

        val permalink =
            payload.threadMessagePermalink(
                message = threadMessage("a0a0a0a0-0000-4000-8000-000000000003", "u-artin", "artin"),
                serverSlug = "workspace"
            )

        assertEquals(
            "https://app.raft.build/s/workspace/channel/c0c0c0c0-0000-4000-8000-000000000001?msg=a0a0a0a0-0000-4000-8000-000000000003&thread=c0c0c0c0-0000-4000-8000-000000000001%3Aa0a0a0a0-0000-4000-8000-000000000004",
            permalink
        )
    }

    @Test
    fun compactThreadAuthorSuppressionResetsAfterWindowDayOrAuthorChanges() {
        val base =
            RaftThreadMessageItem(
                id = "m1",
                authorId = "u-artin",
                authorName = "Artin",
                timestampLabel = "2026-06-27T01:00:00Z"
            )

        assertTrue(
            shouldSuppressCompactThreadAuthor(
                previous = base,
                next = base.copy(id = "m2", timestampLabel = "2026-06-27T01:30:00Z"),
                localOffsetMinutes = 0
            )
        )
        assertFalse(
            shouldSuppressCompactThreadAuthor(
                previous = base,
                next = base.copy(id = "m3", timestampLabel = "2026-06-27T01:30:01Z"),
                localOffsetMinutes = 0
            )
        )
        assertFalse(
            shouldSuppressCompactThreadAuthor(
                previous = base.copy(timestampLabel = "2026-06-27T23:50:00Z"),
                next = base.copy(id = "m4", timestampLabel = "2026-06-28T00:05:00Z"),
                localOffsetMinutes = 0
            )
        )
        assertFalse(
            shouldSuppressCompactThreadAuthor(
                previous = base.copy(timestampLabel = "2026-06-27T23:50:00Z"),
                next =
                    base.copy(
                        id = "local-send-1",
                        timestampLabel = "Now",
                        deliveryState = ThreadMessageDeliverySending,
                        pendingSendContext =
                            RaftThreadPendingSendContext(
                                channelId = "channel-1",
                                body = "optimistic"
                            )
                    ),
                localOffsetMinutes = 0
            )
        )
        assertFalse(
            shouldSuppressCompactThreadAuthor(
                previous = base,
                next = base.copy(id = "m5", authorId = "u-cindy", authorName = "Cindy", timestampLabel = "2026-06-27T01:05:00Z"),
                localOffsetMinutes = 0
            )
        )
        val twelveHourRenderConfig =
            ThreadTimelineRenderConfig(
                backgroundRefreshing = false,
                errorCode = null,
                originPreview = null,
                originErrorCode = null,
                timeContext =
                    RaftTimeContext(
                        localOffsetMinutes = 480,
                        todayEpochDay = raftEpochDayForDate(2026, 6, 28),
                        timeFormat = RaftTimeFormat12Hour
                    ),
                members = emptyList()
            )
        val compactTimestamp =
            twelveHourRenderConfig.formatCompactTimestampLabel(
                RaftThreadMessageItem(
                    id = "message-24h",
                    authorId = "user-1",
                    authorName = "User",
                    timestampLabel = "2026-06-27T12:34:00Z"
                )
            )
        val forwardedTimestamp =
            ForwardMessageItem(
                id = "forwarded-message-24h",
                authorId = "user-1",
                authorName = "User",
                timestampLabel = "2026-06-27T12:34:00Z"
            ).formatThreadForwardedTimestampLabel(twelveHourRenderConfig.timeContext)

        assertEquals("20:34", compactTimestamp)
        assertEquals(compactTimestamp, forwardedTimestamp)
        assertFalse(compactTimestamp.contains("Z"))
        assertFalse(compactTimestamp.contains("AM"))
        assertFalse(compactTimestamp.contains("PM"))
        assertEquals(
            "",
            ForwardMessageItem(
                id = "forwarded-message-missing-context",
                authorId = "user-1",
                authorName = "User",
                timestampLabel = "2026-06-27T12:34:00Z"
            ).formatThreadForwardedTimestampLabel(timeContext = null)
        )
    }

    @Test
    fun threadDateDividerLabelAppearsOnlyWhenLocalDayChanges() {
        val timeContext =
            RaftTimeContext(
                localOffsetMinutes = 0,
                todayEpochDay = raftEpochDayForDate(2026, 7, 4)
            )
        val julyOne =
            RaftThreadMessageItem(
                id = "m1",
                authorId = "u-artin",
                authorName = "Artin",
                timestampLabel = "2026-07-01T09:00:00Z"
            )
        val julyOneLater = julyOne.copy(id = "m2", timestampLabel = "2026-07-01T10:00:00Z")
        val julyThree = julyOne.copy(id = "m3", timestampLabel = "2026-07-03T10:00:00Z")
        val julyFour = julyOne.copy(id = "m4", timestampLabel = "2026-07-04T10:00:00Z")

        assertEquals("WEDNESDAY, JULY 1", threadDateDividerLabel(null, julyOne, timeContext))
        assertEquals(null, threadDateDividerLabel(julyOne, julyOneLater, timeContext))
        assertEquals("YESTERDAY", threadDateDividerLabel(julyOneLater, julyThree, timeContext))
        assertEquals("TODAY", threadDateDividerLabel(julyThree, julyFour, timeContext))
        assertEquals(null, threadDateDividerLabel(julyFour, julyFour.copy(id = "local", timestampLabel = "Now"), timeContext))
    }

    @Test
    fun markdownChannelLaunchPayloadResolvesChannelNameToIdentityOnlyRoute() {
        val payload =
            buildMarkdownChannelLaunchPayload(
                currentPayload =
                    RaftThreadScreenPayload(
                        instanceId = "current-thread",
                        alignOwnMessagesRight = true,
                        localOffsetMinutes = 480,
                        timeFormat = RaftTimeFormat12Hour
                    ),
                home =
                    markdownChannelHomeSnapshot(
                        ConversationSummary(
                            threadId = ThreadId("channel-general-id"),
                            channelId = ChannelId("channel-general-id"),
                            channelType = "channel",
                            kind = ConversationKind.Channel,
                            name = "general"
                        )
                    ),
                channelLabel = "#General"
            )

        checkNotNull(payload)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.channelKind)
        assertEquals("channel-general-id", payload.sourceThreadId)
        assertEquals("channel-general-id", payload.streamChannelId)
        assertEquals("chat", payload.selectedTab)
        assertEquals("loading", payload.status)
        assertEquals(emptyList(), payload.messages)
        assertEquals(true, payload.alignOwnMessagesRight)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(RaftTimeFormat12Hour, payload.timeFormat)
    }

    @Test
    fun markdownChannelLaunchPayloadRejectsUnknownOrAmbiguousChannelNames() {
        val home =
            markdownChannelHomeSnapshot(
                ConversationSummary(
                    threadId = ThreadId("channel-a"),
                    channelId = ChannelId("channel-a"),
                    kind = ConversationKind.Channel,
                    name = "design"
                ),
                ConversationSummary(
                    threadId = ThreadId("channel-b"),
                    channelId = ChannelId("channel-b"),
                    kind = ConversationKind.Channel,
                    name = "Design"
                )
            )

        assertEquals(null, buildMarkdownChannelLaunchPayload(RaftThreadScreenPayload(), home, "#missing"))
        assertEquals(null, buildMarkdownChannelLaunchPayload(RaftThreadScreenPayload(), home, "#design"))
    }

    @Test
    fun threadComposerOnlyShowsOnChatTab() {
        assertTrue(shouldShowThreadComposerForTab(joined = true, showComposer = true, selectedTab = "chat"))
        assertFalse(shouldShowThreadComposerForTab(joined = true, showComposer = true, selectedTab = "tasks"))
        assertFalse(shouldShowThreadComposerForTab(joined = true, showComposer = true, selectedTab = "files"))
        assertFalse(shouldShowThreadComposerForTab(joined = false, showComposer = true, selectedTab = "chat"))
        assertFalse(shouldShowThreadComposerForTab(joined = true, showComposer = false, selectedTab = "chat"))
    }

    @Test
    fun consumeComposerSeedForSendRemovesOnlySentAttachments() {
        val payload =
            RaftThreadScreenPayload(
                composer =
                    RaftThreadComposerPayload(
                        seedText = "Diagnostics attached",
                        seedAsTask = true,
                        syncNonce = 7,
                        pendingAttachments =
                            listOf(
                                RaftPendingAttachmentItem(id = "diagnostics-att-1", fileName = "slock-feedback.txt"),
                                RaftPendingAttachmentItem(id = "manual-att-2", fileName = "manual.txt")
                            )
                    )
            )

        val consumed =
            payload.consumeComposerSeedForSend(
                sentBody = "  Diagnostics attached  ",
                sentAttachmentIds = setOf("diagnostics-att-1")
            )

        assertEquals("", consumed.composer.seedText)
        assertEquals(false, consumed.composer.seedAsTask)
        assertEquals(8, consumed.composer.syncNonce)
        assertEquals(listOf("manual-att-2"), consumed.composer.pendingAttachments.map { it.id })
    }

    @Test
    fun threadRowDoesNotUseMessageStatusWhenCanonicalStatusIsUnavailable() {
        val message =
            RaftThreadMessageItem(
                id = "m1",
                authorId = "agent-1",
                authorName = "Agent",
                authorKind = "agent",
                body = "hello"
            )

        assertNull(resolveThreadAgentStatusIndicatorState(message, canonicalState = null))
        assertNull(
            message.toThreadAuthorProfileCandidate(ServerId("server-1"))?.agentStatus,
            "message author snapshots may carry static profile candidates, never runtime status"
        )
        assertEquals(
            AgentStatus.Working,
            resolveThreadAgentStatusIndicatorState(
                message,
                canonicalState = raftAgentStatusIndicatorState(
                    status = AgentStatus.Working,
                    presence = Presence.Active,
                    external = false
                )
            )?.status
        )
    }

    @Test
    fun inlineImageShowsFailureOnlyOnceEverySourceFailed() {
        assertEquals(false, threadInlineImageShowsFailure(sourceCount = 2, failedSources = 1, awaitingRemoteFallback = false))
        assertEquals(true, threadInlineImageShowsFailure(sourceCount = 2, failedSources = 2, awaitingRemoteFallback = false))
        assertEquals(true, threadInlineImageShowsFailure(sourceCount = 0, failedSources = 0, awaitingRemoteFallback = false))
        assertEquals(false, threadInlineImageShowsFailure(sourceCount = 0, failedSources = 0, awaitingRemoteFallback = true))
    }

    @Test
    fun universalMp4UsesInlineVideoPreviewWithoutWideningUnknownFiles() {
        val mp4ByMime =
            RaftThreadAttachmentItem(
                id = "att-video-mime",
                name = "clip.bin",
                mimeType = "video/mp4; codecs=avc1"
            )
        val mp4ByName =
            RaftThreadAttachmentItem(
                id = "att-video-name",
                name = "demo.MP4?download=1",
                mimeType = "application/octet-stream"
            )
        val unknownVideo =
            RaftThreadAttachmentItem(
                id = "att-video-unknown",
                name = "demo.futurevideo",
                mimeType = "video/vnd.future-container"
            )
        val archive =
            RaftThreadAttachmentItem(
                id = "att-archive",
                name = "demo.zip",
                mimeType = "application/zip"
            )

        assertTrue(mp4ByMime.isPreviewableVideoAttachment())
        assertTrue(mp4ByName.isPreviewableVideoAttachment())
        assertFalse(unknownVideo.isPreviewableVideoAttachment())
        assertFalse(archive.isPreviewableVideoAttachment())
    }

    @Test
    fun authSessionSelfMentionNamesIncludeBareHandle() {
        val session =
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-artin",
                        email = "artin@example.com",
                        name = "@artin",
                        displayName = "Artin"
                    ),
                accessToken = "token",
                refreshToken = "refresh"
            )

        assertTrue("artin" in session.threadSelfMentionNames())
    }

    @Test
    fun threadFindMatchesSenderNameAndTextOnlyAndIgnoresBlankQuery() {
        val payload =
            RaftThreadScreenPayload(
                sourceThreadId = "thread-1",
                messages =
                    listOf(
                        testThreadMessage("reply-0").copy(authorName = "Artin", body = "hello"),
                        testThreadMessage("reply-1").copy(authorHandle = "Android-Developer-2", body = "ship it")
                    )
            )
        val searchable = threadFindSearchable(assertNotNull(payload.toThreadTimelineSlice().threadFindWindow()), corpus = emptyList())

        assertEquals(emptyList(), threadFindMatches(searchable, "   "))
        // web buildThreadSearchMatches: the sender's name and the text, any case; not the handle.
        assertEquals(listOf("reply-0"), threadFindMatches(searchable, " ARTIN "))
        assertEquals(emptyList(), threadFindMatches(searchable, "developer-2"))
    }

    @Test
    fun threadOlderAutoLoadRequiresFreshDragAfterEachSettledPrepend() {
        assertFalse(
            shouldThreadAutoLoadOlderMessages(
                boundaryIndex = 0,
                firstVisibleIndex = 0,
                userScrollGeneration = 7,
                lastSettledUserScrollGeneration = 7
            )
        )
        assertTrue(
            shouldThreadAutoLoadOlderMessages(
                boundaryIndex = 0,
                firstVisibleIndex = 0,
                userScrollGeneration = 8,
                lastSettledUserScrollGeneration = 7
            )
        )
    }

    @Test
    fun addMemberSubmissionHighlightsOnlySelectedRowWhileLockingAllClicks() {
        assertEquals(
            ThreadAddMemberRowInteraction(selected = true, enabled = true, clickEnabled = false),
            threadAddMemberRowInteraction(
                memberKey = "human:user-1",
                selectedKey = "human:user-1",
                blocked = false,
                adding = true,
                alreadyJoined = false
            )
        )
        assertEquals(
            ThreadAddMemberRowInteraction(selected = false, enabled = true, clickEnabled = false),
            threadAddMemberRowInteraction(
                memberKey = "human:user-2",
                selectedKey = "human:user-1",
                blocked = false,
                adding = true,
                alreadyJoined = false
            )
        )
    }

    @Test
    fun launchPayloadWithUnknownSurfaceKindReturnsErrorState() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-bad-surface",
                      "surfaceKind": "spaceship",
                      "sourceThreadId": "channel-1",
                      "streamChannelId": "channel-1"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("fallback", payload.instanceId)
        assertEquals("error", payload.status)
        assertEquals("payload_parse_failed", payload.errorCode)
    }

    @Test
    fun launchPayloadParsesDirectChannelKindAsDirectMessage() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-direct",
                      "surfaceKind": "channel",
                      "channelKind": "direct",
                      "sourceThreadId": "dm-1",
                      "streamChannelId": "dm-1"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("thread-direct", payload.instanceId)
        assertEquals("loading", payload.status)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.channelKind)
        assertEquals(true, payload.memberScopedMentions)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
    }

    // task #396 / botiverse/slock #16042: a Slack-bridged channel's header draws the Slack mark in its
    // tile and, while its sync is stopped, the warning Badge after its name (web ChatPanel
    // `ChannelIcon`, `ChannelSlackSyncBadge`); an unbridged channel and a DM never do.
    @Test
    fun appShellChannelHeaderProjectionCarriesSlackBridgeProvider() {
        val bridged =
            ConversationSummary(
                threadId = ThreadId("channel-bridged"),
                channelId = ChannelId("channel-bridged"),
                kind = ConversationKind.Channel,
                name = "slack-sync",
                bridgeProvider = "slack",
                slackSyncProblem = RaftSlackSyncProblem.Paused,
                permissions = ConversationPermissions(canSend = true)
            )
        val plain =
            bridged.copy(
                threadId = ThreadId("channel-plain"),
                channelId = ChannelId("channel-plain"),
                bridgeProvider = null,
                slackSyncProblem = null,
            )

        val bridgedHeader =
            buildAppShellThreadPayloadFromSummary(bridged)
                .threadHeaderDisplayModelFromDirectory(bridged.toConversationHeaderProjection())
        val plainHeader =
            buildAppShellThreadPayloadFromSummary(plain)
                .threadHeaderDisplayModelFromDirectory(plain.toConversationHeaderProjection())

        assertEquals(RaftThreadSurfaceKind.Channel, bridgedHeader.surfaceKind)
        assertEquals("slack", bridgedHeader.bridgeProvider)
        assertEquals(RaftChannelKindGlyph.Slack, bridgedHeader.channelKindGlyph)
        assertEquals(RaftSlackSyncProblem.Paused, bridgedHeader.slackSyncProblem)
        assertEquals(null, plainHeader.bridgeProvider)
        assertEquals(RaftChannelKindGlyph.Hash, plainHeader.channelKindGlyph)
        assertEquals(null, plainHeader.slackSyncProblem)
    }

    @Test
    fun directoryProjectionUpdatesThreadHeaderWithoutChangingRouteIdentity() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "Channel",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1", "stream-channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "private",
                title = "ops-room",
                subtitle = "Operations"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertEquals("ops-room", headerDisplay.title)
        assertEquals("Operations", headerDisplay.subtitle)
        assertEquals("private", headerDisplay.channelKind)
        assertEquals("Channel", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals("channel", payload.channelKind)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
    }

    @Test
    fun mutedIndicatorFollowsCanonicalMuteStateAcrossChannelAndDirectMessageSurfaces() {
        val unmutedChannel =
            RaftThreadScreenPayload(
                instanceId = "channel-1",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                activityMuted = false,
                activityMuteSupported = true
            )
        val mutedDirectMessage =
            RaftThreadScreenPayload(
                instanceId = "dm-1",
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                activityMuted = true,
                activityMuteSupported = true
            )

        assertFalse(unmutedChannel.threadActivityMutedIndicatorVisible(null))
        assertTrue(mutedDirectMessage.threadActivityMutedIndicatorVisible(null))
    }

    /** A removed App bot's DM: mute no longer offered, but a muted one keeps Unmute and the marker. */
    @Test
    fun aMutedDirectMessageWithoutMuteSupportKeepsUnmuteAndTheMarker() {
        fun dm(muted: Boolean) =
            RaftThreadScreenPayload(
                instanceId = "dm-bot",
                sourceThreadId = "dm-bot",
                streamChannelId = "dm-bot",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                activityMuted = muted,
                activityMuteSupported = false
            )

        val mutedState = dm(muted = true).threadActivityMuteState(null)
        assertEquals(true, mutedState?.muted)
        assertEquals(false, mutedState?.supported)
        assertTrue(dm(muted = true).threadActivityMutedIndicatorVisible(null))
        assertEquals(null, dm(muted = false).threadActivityMuteState(null))
        assertFalse(dm(muted = false).threadActivityMutedIndicatorVisible(null))
    }

    @Test
    fun conversationHeaderProjectionDoesNotRenderIdentityAsTitle() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val conversation =
            ConversationSummary(
                threadId = ThreadId(uuid),
                channelId = ChannelId(uuid),
                kind = ConversationKind.Channel,
                name = uuid,
                permissions = ConversationPermissions(canSend = true)
            )
        val payload =
            RaftThreadScreenPayload(
                instanceId = "notification-thread",
                title = "Channel",
                sourceThreadId = uuid,
                streamChannelId = uuid,
                surfaceKind = RaftThreadSurfaceKind.Channel
            )
        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(conversation.toConversationHeaderProjection())

        assertEquals("Channel", headerDisplay.title)
    }

    @Test
    fun jointReplyThreadComposerUsesParentChannelMemberScope() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "joint-channel-1",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "joint",
                memberScopedMentions = true,
                composer =
                    buildThreadSurfaceComposerPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = "joint-parent-1",
                        enabled = true,
                        mentionChannelId = "joint-channel-1",
                        parentMessageId = "joint-parent-1",
                        streamChannelId = "joint-thread-1"
                    )
            )

        assertEquals("joint-channel-1", payload.resolveThreadComposer().mentionChannelId)
        assertEquals(true, payload.threadComposerMemberScopedMentions())
        assertEquals(true, payload.threadComposerMemberSuggestionHeadersVisible())
    }

    @Test
    fun threadSettingsDisabledRecognizedActionsDoNotFallbackToDefaults() {
        val actions =
            listOf(
                RaftThreadSettingsAction(id = "visibility", enabled = false),
                RaftThreadSettingsAction(id = "archive", enabled = true),
                RaftThreadSettingsAction(id = "delete", enabled = false)
            ).normalizedThreadSettingsChannelActions()

        assertEquals(listOf("archive"), actions.map { it.id })
    }

    @Test
    fun conversationAccessFallbackMustMatchRouteIdentityBeforeDisablingComposer() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                canSend = true,
                composer =
                    RaftThreadComposerPayload(
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        enabled = true,
                        streamChannelId = "channel-1"
                    )
            )
        val unrelatedAccessFallback =
            ConversationSummary(
                threadId = ThreadId("channel-2"),
                channelId = ChannelId("channel-2"),
                kind = ConversationKind.Channel,
                name = "unrelated",
                joined = false,
                permissions = ConversationPermissions(canSend = false)
            )

        val result = payload.withConversationAccessFallback(unrelatedAccessFallback.toConversationAccessCandidate())

        assertEquals(true, result.joined)
        assertEquals(true, result.canSend)
        assertEquals(true, result.composer.enabled)
    }

    @Test
    fun sharedShellJointChannelRouteHasRegularChannelTaskSurfaces() {
        fun payloadFor(channelType: String) =
            buildAppShellThreadPayloadFromSummary(
                ConversationSummary(
                    threadId = ThreadId("joint-1"),
                    channelId = ChannelId("joint-1"),
                    channelType = channelType,
                    kind = ConversationKind.Channel,
                    name = "partner",
                    subtitle = "Joint channel",
                    joined = true,
                    memberCount = 3,
                    permissions = ConversationPermissions(canSend = true, canCreateTask = true)
                )
            )
        val payload = payloadFor("joint")
        val regular = payloadFor("channel")

        // Joint task surfaces exist server-side; the client no longer hides them.
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(true, payload.supportsChannelTasks())
        assertEquals(regular.showTaskOpenActions, payload.showTaskOpenActions)
        assertEquals(regular.showTaskStatusActions, payload.showTaskStatusActions)
        assertEquals(regular.composer.showAsTaskToggle, payload.composer.showAsTaskToggle)
        assertEquals(regular.resolveThreadComposer().showAsTaskToggle, payload.resolveThreadComposer().showAsTaskToggle)
        assertEquals(
            shouldStartThreadTasksJoin(regular.copy(status = "content"), null, ChannelId("joint-1")),
            shouldStartThreadTasksJoin(payload.copy(status = "content"), null, ChannelId("joint-1"))
        )
    }

    @Test
    fun replyThreadRealtimeRoomsIncludeParentChannelAndThreadRooms() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "dm-parent-channel",
                sourceThreadId = "thread-channel",
                streamChannelId = "thread-channel"
            )

        assertEquals(
            listOf("dm-parent-channel", "thread-channel"),
            payload.activeRealtimeRoomIds()
        )
    }

    @Test
    fun replyThreadAndIneligibleMessagesCannotConvertToTasks() {
        val replyPayload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentMessageId = "parent-1",
                originParentMessageId = "parent-1",
                showTaskStatusActions = true
            )
        val disabledPayload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                showTaskStatusActions = false
            )

        assertFalse(replyPayload.canConvertMessagesToTasks())
        assertFalse(disabledPayload.canConvertMessagesToTasks())
        assertFalse(
            RaftThreadMessageItem(
                id = "msg-task-id",
                authorId = "u1",
                authorName = "Artin",
                taskId = "task-1"
            ).canConvertToTask()
        )
        assertFalse(
            RaftThreadMessageItem(
                id = "msg-task-number",
                authorId = "u1",
                authorName = "Artin",
                taskNumber = 12
            ).canConvertToTask()
        )
        assertFalse(
            RaftThreadMessageItem(
                id = "msg-system",
                authorId = "system",
                authorName = "System",
                messageType = "System"
            ).canConvertToTask()
        )
        assertFalse(
            RaftThreadMessageItem(
                id = "msg-local",
                authorId = "u1",
                authorName = "Artin",
                deliveryState = ThreadMessageDeliverySending,
                pendingSendContext =
                    RaftThreadPendingSendContext(
                        channelId = "channel-1",
                        body = "pending"
                    )
            ).canConvertToTask()
        )
    }

    @Test
    fun replyThreadOpenParentRequiresCurrentSessionServer() {
        val replyPayload =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-reply-stream",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = "channel",
                sourceThreadId = "reply-stream",
                streamChannelId = "reply-stream",
                parentChannelId = "parent-channel",
                parentMessageId = "parent-message",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "channel",
                showOpenInChannelAction = true
            )

        assertNull(replyPayload.resolveReplyParentConversationOpenRequest("", null))
        assertNull(replyPayload.resolveReplyParentConversationOpenRequest("   ", null))
        assertNull(threadParentNavigationServerId(null))
        assertNull(threadParentNavigationServerId("   "))
        assertEquals("server-source", threadParentNavigationServerId(" server-source "))
    }

    @Test
    fun replyConversationContextRendersDmParentPeerAsAtLabel() {
        // task #399: a reply thread hosted in a DM subtitles the header with
        // the peer's @-name — never a # channel tag.
        val context =
            SharedThreadHeaderContextStore.conversationContext(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("dm-thread-1"),
                        channelId = ChannelId("dm-1"),
                        kind = ConversationKind.Thread,
                        name = "Jiacheng",
                        thread =
                            ThreadConversation(
                                parentChannelId = ChannelId("dm-1"),
                                parentMessageId = MessageId("message-1"),
                                streamChannelId = ChannelId("dm-thread-1"),
                                parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage,
                                parentChannelKind = "dm"
                            )
                    ),
                originMessage = null,
                parentMessageId = "message-1"
            )

        assertEquals("@Jiacheng", context.subtitle)
    }

    @Test
    fun appShellActivityThreadItemOpensReplyThreadChrome() {
        val baseItem =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                parentMessageSenderId = "user-1",
                parentMessageSenderKind = MemberKind.Human,
                unreadCount = 1,
                firstUnreadMessageId = MessageId("reply-message-1"),
                latestActivityMessageId = MessageId("reply-message-1"),
                latestActivityPreview = "Latest reply"
            )
        val request = baseItem.toHomeMessageOpenRequestForTest()
        val conversation = request.conversationIdentity

        val payload =
            requireNotNull(buildAppShellThreadPayload(
                conversation = conversation,
                anchorMessageId = request.anchorMessageId,
                originFallback = request.originFallback
            ))

        assertEquals("reply-message-1", request.anchorMessageId)
        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), conversation.threadId)
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals("", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("reply-message-1", payload.focusedMessageId)
        assertEquals("thread-channel-1", payload.sourceThreadId)
        assertEquals("thread-channel-1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("missing", payload.originStatus)
        assertEquals("Parent message preview", payload.originPreview)
        assertEquals(null, payload.originMessage)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)

        // slock #16908: a read Activity thread row opens at latest replies with no focused message.
        val readRequest = baseItem.copy(unreadCount = 0, firstUnreadMessageId = null).toHomeMessageOpenRequestForTest()
        val readPayload =
            requireNotNull(buildAppShellThreadPayload(
                conversation = readRequest.conversationIdentity,
                anchorMessageId = readRequest.anchorMessageId,
                originFallback = readRequest.originFallback
            ))
        assertEquals(null, readRequest.anchorMessageId)
        assertEquals(null, readPayload.focusedMessageId)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
    }

    @Test
    fun refreshPatchPreservesLoadedOriginWhenNetworkSnapshotOnlyProvesSameParent() {
        val origin =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "user-1",
                authorName = "Artin",
                body = "Original task body"
            )
        val previous =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-thread-channel-1",
                title = ThreadReplyPageTitle,
                subtitle = "#general",
                status = "content",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                originParentMessageId = "message-1",
                originMessage = origin,
                originStatus = "loaded",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "reply-message-1",
                            authorId = "agent-1",
                            authorName = "Android-Developer-4",
                            body = "Reply body"
                        )
                    )
            )
        val refreshPatch =
            previous.copy(
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "reply-message-1",
                            authorId = "agent-1",
                            authorName = "Android-Developer-4",
                            body = "Reply body"
                        ),
                        RaftThreadMessageItem(
                            id = "reply-message-2",
                            authorId = "agent-2",
                            authorName = "KMP-专家",
                            body = "Fresh reply"
                        )
                    ),
                originMessage = null,
                originPreview = null,
                originParentMessageId = "message-1",
                originStatus = "missing"
            )

        val payload = refreshPatch.preserveStableTimelineFrom(previous)

        assertEquals("loaded", payload.originStatus)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("Original task body", payload.originMessage?.body)
        assertEquals(listOf("reply-message-1", "reply-message-2"), payload.messages.map { it.id })
    }

    @Test
    fun primaryRefreshRetainsAcceptedSavedAndTaskFactsUntilAuxiliaryAuthoritiesCommit() {
        val acceptedMessage =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user-artin",
                authorName = "Artin",
                body = "ship task",
                isSaved = true,
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "InReview",
                taskAssigneeKind = "agent",
                taskAssigneeId = "agent-android",
                taskAssigneeName = "Android Developer"
            )
        val previous =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                status = "content",
                sourceThreadId = "thread-channel-1",
                messages = listOf(acceptedMessage)
            )
        val primaryRefresh =
            previous.copy(
                messages =
                    listOf(
                        acceptedMessage.copy(
                            isSaved = false,
                            taskId = null,
                            taskNumber = null,
                            taskStatus = null,
                            taskAssigneeKind = null,
                            taskAssigneeId = null,
                            taskAssigneeName = null
                        )
                    )
            )

        val retained =
            primaryRefresh.preserveStableTimelineFrom(
                previous = previous,
                preserveAuxiliaryProjectionFromPrevious = true
            )

        assertTrue(retained.messages.single().isSaved)
        assertEquals("task-240", retained.messages.single().taskId)
        assertEquals("InReview", retained.messages.single().taskStatus)
        assertEquals("Android Developer", retained.messages.single().taskAssigneeName)

        val savedAuthorityCommitted = retained.withSavedMessageIds(emptySet())
        assertFalse(savedAuthorityCommitted.messages.single().isSaved)

        val authoritativeTask =
            TaskSummary(
                id = TaskId("task-240"),
                title = "ship task",
                status = TaskStatus.Done,
                taskNumber = 240,
                messageId = MessageId("message-240"),
                updatedAtLabel = "now"
            )
        val taskAuthorityCommitted =
            savedAuthorityCommitted
                .toThreadTimelineSlice()
                .withThreadLinkedTasksProjection(
                    ThreadLinkedTasksProjection(
                        authoritative = true,
                        lookup = listOf(authoritativeTask).threadLinkedTasksByMessageLookup()
                    )
                )

        assertEquals("done", taskAuthorityCommitted.messages.single().taskStatus)
    }

    @Test
    fun standaloneReplyThreadLaunchPreservesComposerRoutingIntent() {
        val launch =
            WireThreadLaunchPayload(
                instanceId = "thread-route",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                threadId = "thread-channel-1",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "channel",
                originParentMessageId = "parent-message-1",
                focusedMessageId = "reply-2",
                alignOwnMessagesRight = true,
                localOffsetMinutes = 480,
                todayEpochDay = 20593
            )

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals("thread-route", initialPayload.instanceId)
        assertEquals("loading", initialPayload.status)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals("thread-channel-1", initialPayload.sourceThreadId)
        assertEquals("thread-channel-1", initialPayload.streamChannelId)
        assertEquals("parent-channel-1", initialPayload.parentChannelId)
        assertEquals("parent-message-1", initialPayload.parentMessageId)
        assertEquals("parent-message-1", initialPayload.originParentMessageId)
        assertEquals("reply-2", initialPayload.focusedMessageId)
        assertEquals(false, initialPayload.showModeTabs)
        assertEquals(true, initialPayload.showOpenInChannelAction)

        val composer = initialPayload.resolveThreadComposer()
        assertEquals(RaftThreadSurfaceKind.ReplyThread, composer.surfaceKind)
        assertEquals("thread-channel-1", composer.streamChannelId)
        assertEquals("parent-channel-1", composer.mentionChannelId)
        assertEquals("parent-message-1", composer.parentMessageId)
        assertEquals("Reply in thread", composer.placeholder)
        assertEquals(false, composer.showAsTaskToggle)
        assertEquals(false, initialPayload.threadComposerMemberScopedMentions())
        assertEquals(true, initialPayload.threadComposerMemberSuggestionHeadersVisible())
    }

    @Test
    fun replyThreadTerminalEmptyProjectionCannotRegressToLoadingPresentation() {
        val terminalEmpty =
            RaftThreadScreenPayload(
                status = "empty",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                emptyTitle = "Loading...",
                emptyDetail = "Stale loading detail"
            ).toThreadTimelineSlice()
        val loading =
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                emptyTitle = "Loading..."
            ).toThreadTimelineSlice()
        val error =
            RaftThreadScreenPayload(
                status = "error",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                emptyTitle = "Could not load replies",
                emptyDetail = "Try again"
            ).toThreadTimelineSlice()

        assertEquals(ThreadReplyEmptyTitle, terminalEmpty.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, terminalEmpty.emptyDetail)
        assertEquals("Loading...", loading.emptyTitle)
        assertEquals("Could not load replies", error.emptyTitle)
        assertEquals("Try again", error.emptyDetail)
    }

    @Test
    fun nestedReplyOpenFromReplyThreadUsesExplicitParentChannelInsteadOfReplyStream() {
        val parent =
            RaftThreadMessageItem(
                id = "task-parent-541",
                authorId = "user_artin",
                authorName = "Artin",
                body = "Task parent",
                threadChannelId = "nested-reply-stream-541",
                taskId = "task-541",
                taskNumber = 541
            )
        val payload =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-reply-stream-541",
                title = ThreadReplyPageTitle,
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply-stream-541",
                streamChannelId = "reply-stream-541",
                parentChannelId = "parent-channel",
                parentMessageId = "root-parent-message",
                messages = listOf(parent)
            )
        val timeline = payload.toThreadTimelineSlice()
        val taskParent = timeline.findTaskParentMessage("task-541")
        val loadingPayload =
            payload.replyThreadLoadingPayload(
                parentMessageId = taskParent!!.id,
                parentChannelId = payload.replyParentChannelIdForNestedOpen(),
                threadChannelId = parent.threadChannelId
            )

        assertEquals("parent-channel", payload.replyParentChannelIdForNestedOpen())
        assertEquals("parent-channel", loadingPayload.parentChannelId)
        assertEquals("task-parent-541", loadingPayload.parentMessageId)
        assertEquals("nested-reply-stream-541", loadingPayload.sourceThreadId)
        assertEquals("nested-reply-stream-541", loadingPayload.streamChannelId)
    }

    @Test
    fun buildsSharedThreadPayloadFromBridgeMessagesResponse() {
        val base =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )

        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = base,
                session = session,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "m1",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "hello from shared route",
                                    createdAt = "2026-05-19T16:00:00Z",
                                    threadSummary =
                                        WireThreadSummary(
                                            threadChannelId = "thread-m1",
                                            replyCount = 3,
                                            unreadCount = 2,
                                            firstUnreadMessageId = "reply-2"
                                        ),
                                    taskStatus = "in_review",
                                    taskNumber = 42,
                                    taskAssigneeType = "agent",
                                    taskAssigneeId = "agent-ad2",
                                    claimedByName = "Android-Developer-2",
                                    attachments =
                                        listOf(
                                            WireAttachment(id = "a1", filename = "log.txt", mimeType = "text/plain", sizeBytes = 12),
                                            WireAttachment(
                                                id = "a2",
                                                filename = "screen.png",
                                                mimeType = "image/png",
                                                sizeBytes = 2048,
                                                thumbnailUrl = "https://cdn.example/screen.png"
                                            )
                                        ),
                                    reactions = listOf(WireReaction(emoji = "👍", count = 2, reactorIds = listOf("u1"), reactorNames = listOf("Artin")))
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        assertEquals("content", payload.status)
        assertEquals("m1", payload.messages.first().id)
        assertEquals("hello from shared route", payload.messages.first().body)
        assertEquals("Artin", payload.messages.first().authorName)
        assertEquals(true, payload.messages.first().isOwn)
        assertEquals("thread-m1", payload.messages.first().threadChannelId)
        assertEquals(3, payload.messages.first().replyCount)
        assertEquals(2, payload.messages.first().unreadReplyCount)
        assertEquals("reply-2", payload.messages.first().firstUnreadReplyMessageId)
        assertEquals("m1", payload.messages.first().taskId)
        assertEquals(42, payload.messages.first().taskNumber)
        assertEquals("in_review", payload.messages.first().taskStatus)
        assertEquals("Android-Developer-2", payload.messages.first().taskAssigneeName)
        assertEquals("#42", threadMessageTaskMetaLabel(payload.messages.first(), untitledLabel = "Task"))
        assertEquals("👍", payload.messages.first().reactions.first().emoji)
        assertEquals(2, payload.messages.first().reactions.first().count)
        assertEquals(true, payload.messages.first().reactions.first().selected)
        assertEquals("log.txt", payload.messages.first().attachments.first().name)
        assertEquals("https://cdn.example/screen.png", payload.messages.first().attachments[1].thumbnailUrl)
    }

    @Test
    fun threadWireProjectionRejectsUnknownMessageIdentityAndDropsOnlyInvalidTaskSubprojections() {
        val logs = mutableListOf<String>()
        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload =
                    RaftThreadScreenPayload(
                        sourceThreadId = "channel-1",
                        status = "loading",
                    ),
                session =
                    StoredAuthSession(
                        user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                        accessToken = "access",
                        refreshToken = "refresh",
                    ),
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "secret-invalid-sender-id",
                                    channelId = "channel-1",
                                    senderType = "future-member",
                                    senderId = "secret-sender-id",
                                    senderName = "Secret Sender",
                                    messageType = "chat",
                                    content = "private sender body",
                                ),
                                WireMessage(
                                    id = "invalid-system-sender",
                                    channelId = "channel-1",
                                    senderType = "system",
                                    senderId = "system",
                                    senderName = "System",
                                    messageType = "chat",
                                    content = "invalid frontend sender",
                                ),
                                WireMessage(
                                    id = "secret-invalid-type-id",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "future-message",
                                    content = "private type body",
                                ),
                                WireMessage(
                                    id = "task-invalid-status",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "keep task with a status this build does not know",
                                    taskNumber = 1,
                                    taskStatus = "future-status",
                                ),
                                WireMessage(
                                    id = "task-invalid-assignee",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "keep task without invalid assignee",
                                    taskNumber = 2,
                                    taskStatus = "todo",
                                    taskAssigneeType = "future-member",
                                    taskAssigneeId = "secret-assignee-id",
                                    taskAssigneeName = "Secret Assignee",
                                ),
                                WireMessage(
                                    id = "valid-message",
                                    channelId = "channel-1",
                                    senderType = "agent",
                                    senderId = "agent-1",
                                    senderName = "Agent One",
                                    messageType = "chat",
                                    content = "keep valid message",
                                ),
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = false,
                    ),
                diagnostics = logs::add,
            )

        assertEquals(
            listOf("task-invalid-status", "task-invalid-assignee", "valid-message"),
            payload.messages.map { it.id },
        )
        // A status a newer server added keeps the task, as Unknown (not dropped, not Todo).
        val invalidStatus = payload.messages[0]
        assertEquals("task-invalid-status", invalidStatus.taskId)
        assertEquals(TaskStatus.UNKNOWN_WIRE_VALUE, invalidStatus.taskStatus)
        val invalidAssignee = payload.messages[1]
        assertEquals("task-invalid-assignee", invalidAssignee.taskId)
        assertEquals("todo", invalidAssignee.taskStatus)
        assertNull(invalidAssignee.taskAssigneeId)
        assertNull(invalidAssignee.taskAssigneeKind)
        assertTrue(logs.any { it == "projection_value_rejected source=thread_wire_sender field=member_kind value=future-member" })
        assertTrue(logs.any { it == "projection_value_rejected source=thread_wire_sender field=member_kind value=system" })
        assertTrue(logs.any { it == "projection_value_rejected source=thread_wire_message field=message_type value=future-message" })
        assertTrue(logs.any { it == "projection_value_unknown source=thread_wire_task field=task_status value=future-status" })
        assertTrue(logs.any { it == "projection_value_rejected source=thread_wire_task_assignee field=member_kind value=future-member" })
        assertTrue(
            logs.none {
                "secret-invalid" in it || "secret-sender-id" in it || "secret-assignee-id" in it ||
                    "private sender body" in it || "Secret Assignee" in it
            },
        )
    }

    @Test
    fun taskStatusRoundTripsThroughMessageAndLinkedTaskProjectionForEveryWireValue() {
        val author =
            MemberProfile(
                id = MemberId("user-artin"),
                displayName = "Artin",
                handle = "artin",
                kind = MemberKind.Human,
            )

        TaskStatus.entries.forEachIndexed { index, status ->
            val task =
                TaskSummary(
                    id = TaskId("task-$index"),
                    title = "Task $index",
                    status = status,
                    taskNumber = index + 1,
                    messageId = MessageId("message-$index"),
                    updatedAtLabel = "now",
                )
            val message =
                Message(
                    id = MessageId("message-$index"),
                    threadId = ThreadId("thread-1"),
                    channelId = ChannelId("channel-1"),
                    author = author,
                    timestampLabel = "now",
                    body = "Task $index",
                    task = task,
                )

            val messageItem = message.toRaftThreadMessageItem(currentUserId = null)
            assertEquals(status.toWireValue(), messageItem.taskStatus, status.name)
            assertEquals(
                status,
                messageItem.toMessage(ThreadId("thread-1"), ChannelId("channel-1"))?.task?.status,
                status.name,
            )

            val linkedItem =
                messageItem
                    .copy(taskId = null, taskNumber = null, taskStatus = null)
                    .withLinkedTask(task)
            assertEquals(status.toWireValue(), linkedItem.taskStatus, status.name)
            assertEquals(
                status,
                linkedItem.toMessage(ThreadId("thread-1"), ChannelId("channel-1"))?.task?.status,
                status.name,
            )
        }
    }

    @Test
    fun buildsSharedThreadPayloadPreservingActionCardMetadata() {
        val base =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )

        val payload =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = base,
                session = session,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "m-action",
                                    channelId = "channel-1",
                                    senderType = "agent",
                                    senderId = "agent-cindy",
                                    senderName = "Cindy",
                                    messageType = "chat",
                                    content = "Create agent action",
                                    createdAt = "2026-06-09T03:00:00Z",
                                    actionMetadata =
                                        WireActionCardMetadata(
                                            kind = "action-card",
                                            state = "prepared",
                                            action =
                                                WireActionCardAction(
                                                    type = "agent:create",
                                                    name = "Pi-Frontend-Chrome插件专家",
                                                    description = "负责 Chrome 插件前端工程与视觉对齐。",
                                                    suggestedComputer = "machine-2",
                                                    draftHint = "请创建这个专家 agent"
                                                ).toWireJsonObject()
                                        )
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        val card = actionCardFromMessageMetadata(payload.messages.single().metadata)
        assertEquals("agent:create", card?.type)
        assertEquals("prepared", card?.state)
        assertEquals("Pi-Frontend-Chrome插件专家", card?.name)
        assertEquals("负责 Chrome 插件前端工程与视觉对齐。", card?.description)
        assertEquals("machine-2", card?.suggestedComputer)
        assertEquals("请创建这个专家 agent", card?.draftHint)
    }

    @Test
    fun formatsMessageTimestampWithPayloadTimeFormatPreference() {
        val payload =
            RaftThreadScreenPayload(
                localOffsetMinutes = 480,
                todayEpochDay = raftEpochDayForDate(2026, 5, 20),
                timeFormat = "12h"
            )

        assertEquals("12:00 AM", payload.formatTimestampLabel("2026-05-19T16:00:00Z"))
    }

    @Test
    fun appendsSentMessageFromSendResponseWhenRefreshIsUnavailable() {
        val base =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "m1",
                            authorId = "u2",
                            authorName = "Cindy",
                            body = "existing"
                        )
                    )
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )

        val payload =
            appendSharedThreadSentMessageFromPage(
                basePayload = base,
                session = session,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "m2",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "sent from shared composer",
                                    createdAt = "2026-05-19T16:00:00Z"
                                )
                            ),
                        canLoadMoreBefore = null,
                        canLoadMoreAfter = null
                    )
            )

        assertEquals("content", payload.status)
        assertEquals(listOf("m1", "m2"), payload.messages.map { it.id })
        assertEquals(true, payload.messages.last().isOwn)
        assertEquals("sent from shared composer", payload.messages.last().body)
    }

    @Test
    fun linkedTasksAreResolvedByMessageIdAndHideRawIds() {
        val assigneeId = "9d2a923b-7d46-43c3-bf5f-d12a6df8ef12"
        val message =
            RaftThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "ship task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "InReview",
                taskAssigneeId = assigneeId,
                taskAssigneeName = assigneeId
            )
        val linkedTasks =
            TasksUiState(
                loadState =
                    build.raft.app.data.ScreenLoadState.Content(
                        build.raft.app.data.TasksContent(
                            summaryCounts = emptyMap(),
                            tasks =
                                listOf(
                                    TaskSummary(
                                        id = TaskId("task-240"),
                                        title = "ship task",
                                        status = TaskStatus.InReview,
                                        taskNumber = 240,
                                        messageId = MessageId("message-240"),
                                        assignee =
                                            MemberProfile(
                                                id = MemberId(assigneeId),
                                                displayName = "Android Developer",
                                                handle = "Android-Developer",
                                                kind = MemberKind.Agent
                                            ),
                                        updatedAtLabel = ""
                                    ),
                                    TaskSummary(
                                        id = TaskId("task-uuid-only"),
                                        title = "uuid only",
                                        status = TaskStatus.Todo,
                                        taskNumber = 241,
                                        messageId = MessageId("message-241"),
                                        assignee =
                                            MemberProfile(
                                                id = MemberId(assigneeId),
                                                displayName = assigneeId,
                                                handle = assigneeId,
                                                kind = MemberKind.Human
                                            ),
                                        updatedAtLabel = ""
                                    )
                                )
                        )
                    )
            ).threadLinkedTasksByMessageId()

        val linkedMessage = message.withLinkedTask(linkedTasks[message.id])
        assertEquals("Android Developer", linkedMessage.taskAssigneeName)
        assertEquals("#240 @Android Developer", threadMessageTaskMetaLabel(linkedMessage, linkedMessage.taskAssigneeName, untitledLabel = "Task"))

        val uuidOnlyMessage = message.copy(id = "message-241", taskNumber = 241).withLinkedTask(linkedTasks["message-241"])
        assertEquals(null, uuidOnlyMessage.taskAssigneeName)
        assertEquals("#241", threadMessageTaskMetaLabel(uuidOnlyMessage, uuidOnlyMessage.taskAssigneeName, untitledLabel = "Task"))
    }

    @Test
    fun threadTaskIdentityLookupDoesNotConfuseAnotherTasksMessageId() {
        val target =
            TaskSummary(
                id = TaskId("task-target"),
                title = "target",
                status = TaskStatus.InProgress,
                messageId = MessageId("message-target"),
                updatedAtLabel = ""
            )
        val collision =
            TaskSummary(
                id = TaskId("task-other"),
                title = "other",
                status = TaskStatus.Todo,
                messageId = MessageId("task-target"),
                updatedAtLabel = ""
            )
        val projection =
            ThreadLinkedTasksProjection(
                authoritative = true,
                lookup = listOf(target, collision).threadLinkedTasksByMessageLookup()
            )

        assertEquals(target, projection.taskById("task-target"))
    }

    @Test
    fun threadTaskOpenArbitrationOpensCanonicalOnceWhenParentIsOutsideLoadedTimeline() {
        val task =
            TaskSummary(
                id = TaskId("task-outside-window"),
                title = "outside window",
                status = TaskStatus.InProgress,
                updatedAtLabel = ""
            )
        var loadedParentOpens = 0
        var canonicalOpens = 0
        var failClosed = 0

        arbitrateThreadTaskOpen(
            loadedParentAvailable = false,
            loadedParentProvenanceAccepted = false,
            canonicalTask = task
        ).dispatch(
            onUseLoadedParent = { loadedParentOpens += 1 },
            onOpenCanonical = { openedTask, reason ->
                assertEquals(task, openedTask)
                assertEquals(ThreadTaskOpenRecoveryReason.ParentOutsideLoadedWindow, reason)
                canonicalOpens += 1
            },
            onFailClosed = { failClosed += 1 }
        )

        assertEquals(0, loadedParentOpens)
        assertEquals(1, canonicalOpens)
        assertEquals(0, failClosed)
    }

    @Test
    fun threadTaskOpenArbitrationFailsClosedWithoutCanonicalTaskIdentity() {
        var loadedParentOpens = 0
        var canonicalOpens = 0
        var failClosed = 0
        var failReason: ThreadTaskOpenRecoveryReason? = null

        arbitrateThreadTaskOpen(
            loadedParentAvailable = false,
            loadedParentProvenanceAccepted = false,
            canonicalTask = null
        ).dispatch(
            onUseLoadedParent = { loadedParentOpens += 1 },
            onOpenCanonical = { _, _ -> canonicalOpens += 1 },
            onFailClosed = { reason ->
                failClosed += 1
                failReason = reason
            }
        )

        assertEquals(0, loadedParentOpens)
        assertEquals(0, canonicalOpens)
        assertEquals(1, failClosed)
        assertEquals(ThreadTaskOpenRecoveryReason.TaskIdentityUnavailable, failReason)
    }

    @Test
    fun markdownTaskNumberOpenUsesCurrentChannelNumberEndpoint() = runBlocking {
        val api =
            FakeMarkdownTaskNumberApi(
                channelTaskBody =
                    """{"task":{"id":"task-541","channelId":"parent-channel","threadId":"reply-stream","messageId":"parent-message","taskNumber":541,"title":"Correct channel task","status":"in_progress"}}""",
                serverTasksBody =
                    """{"tasks":[{"id":"wrong-task-541","channelId":"reply-stream","threadId":"reply-stream","messageId":"parent-message","taskNumber":541,"title":"Wrong stream task","status":"in_progress"}]}"""
            )
        val result =
            openMarkdownTaskNumberPayload(
                serverId = ServerId("server-1"),
                taskNumber = 541,
                currentPayload =
                    RaftThreadScreenPayload(
                        status = "content",
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        sourceThreadId = "parent-channel",
                        streamChannelId = "parent-channel"
                    ),
                currentTaskChannelId = ChannelId("parent-channel"),
                tasksDataSource = SharedTasksDataSource(api),
                tasksDataStore = null
            )

        // Task v2: a pre-v2 task without a conversation opens in place, as its empty conversation.
        val opened = assertIs<MarkdownTaskNumberOpenResult.OpensInPlace>(result)
        assertEquals("task-541", opened.task.id.value)
        assertEquals(listOf("taskByNumber:server-1:parent-channel:541"), api.calls)
    }

    /** Removed mobile-only guess: the server task list used to stand in for the conversation. */
    @Test
    fun markdownTaskNumberOpenNeverOpensAnotherChannelsTaskOfThatNumber() = runBlocking {
        val store = SharedTasksDataStore()
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    tasks =
                        listOf(
                            TaskSummary(
                                id = TaskId("task-a-343"),
                                title = "A",
                                status = TaskStatus.Todo,
                                taskNumber = 343,
                                channelId = ChannelId("channel-a"),
                                messageId = MessageId("message-a"),
                                updatedAtLabel = ""
                            ),
                            TaskSummary(
                                id = TaskId("task-b-343"),
                                title = "B",
                                status = TaskStatus.Done,
                                taskNumber = 343,
                                channelId = ChannelId("channel-b"),
                                messageId = MessageId("message-b"),
                                updatedAtLabel = ""
                            )
                        ),
                    summaryCounts = emptyMap()
                )
        )
        // The real answer for a number the channel has no task of: 404 `{"error":"Task not found"}`.
        val api =
            FakeMarkdownTaskNumberApi(
                channelTaskBody = """{"error":"Task not found"}""",
                serverTasksBody = """{"tasks":[]}""",
                channelTaskCode = 404
            )

        val result =
            openMarkdownTaskNumberPayload(
                serverId = ServerId("server-1"),
                taskNumber = 343,
                currentPayload =
                    RaftThreadScreenPayload(
                        status = "content",
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        sourceThreadId = "parent-channel",
                        streamChannelId = "parent-channel"
                    ),
                currentTaskChannelId = ChannelId("current-channel"),
                tasksDataSource = SharedTasksDataSource(api),
                tasksDataStore = store
            )

        // Web `openTaskByChannelNumber`: `#343` is task 343 of the conversation being read and
        // nothing else. Two other channels' #343 are not offered, and no server-wide search runs.
        // The 404 is "no such task" (NotFound: logged, no toast), not a failure to open.
        assertEquals(MarkdownTaskNumberOpenResult.NotFound, result)
        assertEquals(listOf("taskByNumber:server-1:current-channel:343"), api.calls)
    }

    /**
     * Staging regression (task v2 off): `POST /tasks/convert-message` answers the canonical
     * `tasks` row, whose `id` is its own uuid; the converted message is only `messageId` /
     * `hostMessageId` (slock `taskService.enrichTaskRows` + `projectTaskToChannel`). The
     * conversion succeeded but was reported as "Unable to convert message".
     */
    @Test
    fun convertMessageAcceptsRealServerResponseWhoseTaskIdDiffersFromMessageId() = runBlocking {
        var serverLoadCalls = 0
        val dataSource =
            SharedTasksDataSource(
                api =
                    object : RaftTasksApi {
                        override suspend fun serverTasks(
                            serverId: ServerId,
                            priority: DataRequestPriority
                        ): HomeNetworkResponse {
                            serverLoadCalls += 1
                            return HomeNetworkResponse(code = 200, bodyText = """{"tasks":[]}""")
                        }

                        override suspend fun convertMessage(
                            serverId: ServerId,
                            messageId: MessageId
                        ): HomeNetworkResponse =
                            HomeNetworkResponse(code = 200, bodyText = REAL_LEGACY_CONVERT_RESPONSE)

                        override suspend fun updateTaskStatus(
                            taskId: TaskId,
                            status: TaskStatus
                        ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
                    }
            )
        val store = SharedTasksDataStore()

        val result =
            convertMessageToCanonicalTask(
                serverId = ServerId("server-1"),
                messageId = MessageId("message-convert"),
                tasksDataSource = dataSource,
                tasksDataStore = store
            )

        val task = assertIs<DataResult.Content<TaskSummary>>(result).data
        assertEquals("7c0b8f8e-5f7a-4d0e-9b51-3a1d2b6c9e10", task.id.value)
        assertEquals(2, task.taskNumber)
        assertEquals(MessageId("message-convert"), task.messageId)
        assertEquals(0, serverLoadCalls)
        assertEquals(
            listOf("7c0b8f8e-5f7a-4d0e-9b51-3a1d2b6c9e10"),
            store.projection("server-1", SharedTasksScope.Server).tasks.map { it.id.value }
        )
    }

    @Test
    fun convertMessageRejectsResponseTaskLinkedToAnotherMessage() = runBlocking {
        val dataSource =
            SharedTasksDataSource(
                api =
                    object : RaftTasksApi {
                        override suspend fun serverTasks(
                            serverId: ServerId,
                            priority: DataRequestPriority
                        ): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = """{"tasks":[]}""")

                        override suspend fun convertMessage(
                            serverId: ServerId,
                            messageId: MessageId
                        ): HomeNetworkResponse =
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = REAL_LEGACY_CONVERT_RESPONSE.replace("message-convert", "message-other")
                            )

                        override suspend fun updateTaskStatus(
                            taskId: TaskId,
                            status: TaskStatus
                        ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)
                    }
            )

        val result =
            convertMessageToCanonicalTask(
                serverId = ServerId("server-1"),
                messageId = MessageId("message-convert"),
                tasksDataSource = dataSource,
                tasksDataStore = SharedTasksDataStore()
            )

        assertEquals(
            "tasks_convert_message_identity_mismatch",
            assertIs<DataResult.Error>(result).error.code
        )
    }

    @Test
    fun realtimeTaskUpsertEnrichesMessageInLoadedExactChannelProjection() = runBlocking {
        val store = SharedTasksDataStore()
        val message =
            RaftThreadMessageItem(
                id = "message-realtime",
                authorId = "user_artin",
                authorName = "Artin",
                body = "realtime task"
            )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("channel-1")),
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )
        store.upsertTask(
            serverId = "server-1",
            task =
                TaskSummary(
                    id = TaskId("task-realtime"),
                    title = "realtime task",
                    status = TaskStatus.InProgress,
                    taskNumber = 289,
                    channelId = ChannelId("channel-1"),
                    messageId = MessageId("message-realtime"),
                    updatedAtLabel = ""
                )
        )
        val payload = RaftThreadScreenPayload(messages = listOf(message))
        val slice = store.projection("server-1", SharedTasksScope.Channel(ChannelId("channel-1")))

        val projected =
            payload.toThreadTimelineSlice().withThreadLinkedTasksProjection(
                slice.threadLinkedTasksProjection(payload.threadLinkedTaskReferences())
            )

        assertEquals(true, slice.isExactScopeLoaded)
        assertEquals("task-realtime", projected.messages.single().taskId)
        assertEquals(289, projected.messages.single().taskNumber)
        assertEquals("in_progress", projected.messages.single().taskStatus)
    }

    @Test
    fun authoritativeEmptyLinkedTaskProjectionClearsStaleMessageTaskFacts() {
        val payload =
            RaftThreadScreenPayload(
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "message-240",
                            authorId = "user_artin",
                            authorName = "Artin",
                            body = "ship task",
                            taskId = "task-240",
                            taskNumber = 240,
                            taskStatus = "Todo",
                            taskAssigneeId = "agent-android",
                            taskAssigneeName = "Android Developer"
                        )
                    )
            )

        val projected =
            payload.toThreadTimelineSlice().withThreadLinkedTasksProjection(
                ThreadLinkedTasksProjection(authoritative = true, lookup = emptyMap())
            )

        val message = projected.messages.single()
        assertEquals(null, message.taskId)
        assertEquals(null, message.taskNumber)
        assertEquals(null, message.taskStatus)
        assertEquals(null, message.taskAssigneeId)
        assertEquals(null, message.taskAssigneeName)
    }

    @Test
    fun threadTasksJoinStartsForChannelWithNoMessages() {
        // 1.21.0: a channel with no messages loads as "empty"; its Tasks tab sent no request and
        // stayed on Loading.
        val taskChannelId = ChannelId("channel-1")
        val emptyChannel =
            RaftThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "empty",
                selectedTab = "tasks",
                showTaskStatusActions = true
            )

        assertTrue(shouldStartThreadTasksLoad(emptyChannel, taskChannelId = taskChannelId))
        assertTrue(shouldStartThreadTasksJoin(emptyChannel, startedThreadId = null, taskChannelId = taskChannelId))
        assertFalse(shouldStartThreadTasksJoin(emptyChannel, startedThreadId = "channel-1", taskChannelId = taskChannelId))
        assertFalse(shouldStartThreadTasksJoin(emptyChannel.copy(status = "error"), startedThreadId = null, taskChannelId = taskChannelId))
    }

    @Test
    fun threadMembersTargetServerSurvivesDifferentInheritedShellOwnerServer() {
        val pageData =
            RaftThreadScreenPayload(instanceId = "thread-1", sourceThreadId = "channel-1")
                .toThreadMembersPageData(
                    serverId = ServerId("server-target"),
                    channelId = ChannelId("channel-1")
                )
                .withRaftShellStoreScope(
                    RaftShellStoreScope.create("shell-a", "server-owner")
                )

        assertEquals("server-target", pageData.optString("serverId"))
        assertEquals("server-owner", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals(ServerId("server-target"), pageData.toThreadMembersPageRouteOrNull()?.serverId)
        assertEquals("server-owner", pageData.raftShellStoreScopeOrNull()?.initialServerId)
    }

    @Test
    fun threadHeaderSearchOpensChannelAndDmSearchPagesAndScopesInlineSearchToThreads() {
        assertEquals(
            ThreadHeaderSearchAction.ChannelSearch,
            RaftThreadScreenPayload(
                sourceThreadId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel
            ).threadHeaderSearchAction()
        )
        assertEquals(
            ThreadHeaderSearchAction.InlineThreadSearch,
            RaftThreadScreenPayload(
                sourceThreadId = "thread-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread
            ).threadHeaderSearchAction()
        )
        // A DM opens its own search page too (web ChatPanel handleSearchThisChannel).
        assertEquals(
            ThreadHeaderSearchAction.ChannelSearch,
            RaftThreadScreenPayload(
                sourceThreadId = "dm-1",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage
            ).threadHeaderSearchAction()
        )
        // Not before the page knows which DM it is.
        assertEquals(
            null,
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.DirectMessage
            ).threadHeaderSearchAction()
        )
    }

    @Test
    fun parsesSystemMessageMetadataAndBuildsCollapsedSummary() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-1",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "m-system",
                            authorId = "system",
                            authorName = "System",
                            messageType = "System",
                            systemEventType = "member_joined",
                            timestampLabel = "2026-05-09 21:53:33.024426+00",
                            body = "<p>Cindy joined<br>the channel</p>",
                            metadata =
                                mapOf(
                                    "actor" to "Cindy",
                                    "source" to "<b>invite</b>"
                                )
                        )
                    )
            )

        val message = payload.messages.first()
        assertEquals(true, message.isSystemMessage())
        assertEquals("member_joined", message.systemEventType)
        assertEquals("Cindy", message.metadata["actor"])
        assertEquals("invite", message.metadata["source"]?.sanitizeThreadSystemText())
        assertEquals("Cindy joined\nthe channel", message.systemContentSummary())
        assertEquals("Cindy joined\nthe channel\nType: member_joined\nactor: Cindy\nsource: invite", message.expandedSystemContent())
        assertEquals("Type: member_joined\nactor: Cindy\nsource: invite", message.expandedSystemDetails())
    }

    @Test
    fun threadStoreRetryEventsDispatchRealStoreIntentsInsteadOfFallingThroughAsUnsupported() {
        mapOf(
            "retryThreadLoad" to SharedThreadIntent.Refresh::class,
            "retryThreadAccess" to SharedThreadIntent.RetryAccessResolution::class,
            "retryLoadOlder" to SharedThreadIntent.LoadOlder::class,
            "retryLoadNewer" to SharedThreadIntent.LoadNewer::class,
        ).forEach { (action, expectedIntentClass) ->
            val dispatched = mutableListOf<SharedThreadIntent>()

            assertEquals(true, handleThreadStoreRetryEvent(action, dispatched::add), action)
            assertEquals(1, dispatched.size, action)
            assertEquals(expectedIntentClass, dispatched.single()::class, action)
        }

        val dispatched = mutableListOf<SharedThreadIntent>()
        assertEquals(false, handleThreadStoreRetryEvent("openTask", dispatched::add))
        assertEquals(emptyList(), dispatched)
    }

    @Test
    fun sharedShellHidesChannelChromeForReplyThread() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "thread-reply-1",
                title = "Threads",
                subtitle = "Origin: #general · hello",
                showModeTabs = false,
                showMembersAction = true,
                showSettingsAction = true,
                showOpenInChannelAction = true,
                originParentMessageId = "message-1"
            ).withSharedShellEventCapabilities()

        assertEquals(false, payload.showSettingsAction)
        assertEquals(false, payload.showMembersAction)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun parsesUploadedAttachmentsForSharedComposer() {
        val attachments =
            parseSharedThreadUploadedAttachments(
                JSONObject().apply {
                    put(
                        "bodyText",
                        """
                        {
                          "attachments": [
                            {
                              "id": "att-1",
                              "filename": "report.pdf",
                              "mimeType": "application/pdf",
                              "sizeBytes": 12288
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }.toJsonObject()
            )

        assertEquals(1, attachments.size)
        assertEquals("att-1", attachments.first().id)
        assertEquals("report.pdf", attachments.first().fileName)
        assertEquals("application/pdf", attachments.first().mimeType)
        assertEquals(12288L, attachments.first().sizeBytes)
        assertEquals("", attachments.first().sizeLabel)
        assertEquals("12.0 KB", attachments.first().displaySizeLabel())
    }

    @Test
    fun threadMessageSavedBadgeTracksSavedStateIndependentlyOfRowActionPreferences() {
        assertTrue(
            shouldShowThreadMessageSavedBadge(
                showSaveAction = true,
                richContentEnabled = true,
                hasLocalSendState = false,
                isSaved = true
            )
        )
        assertFalse(
            shouldShowThreadMessageSavedBadge(
                showSaveAction = true,
                richContentEnabled = true,
                hasLocalSendState = false,
                isSaved = false
            )
        )
        assertTrue(
            shouldShowThreadMessageSavedBadge(
                showSaveAction = true,
                richContentEnabled = true,
                hasLocalSendState = false,
                isSaved = true
            )
        )
        assertFalse(
            shouldShowThreadMessageSavedBadge(
                showSaveAction = true,
                richContentEnabled = true,
                hasLocalSendState = true,
                isSaved = true
            )
        )
    }

    @Test
    fun threadFollowActionRequiresPersistedMessage() {
        val replyMetadata =
            ThreadReplyMetadata(
                parentMessageId = "message-1",
                threadChannelId = "thread-1",
                replyCount = 2,
                unreadReplyCount = 0,
                firstUnreadReplyMessageId = null,
                isThreadFollowed = false
            )
        val pendingContext =
            RaftThreadPendingSendContext(
                channelId = "channel-1",
                body = "Pending message"
            )

        assertTrue(
            shouldShowThreadFollowAction(
                message = RaftThreadMessageItem(id = "message-1", authorId = "author-1", authorName = "Author"),
                replyMetadata = replyMetadata
            )
        )
        assertFalse(
            shouldShowThreadFollowAction(
                message =
                    RaftThreadMessageItem(
                        id = "local-sending",
                        authorId = "author-1",
                        authorName = "Author",
                        deliveryState = ThreadMessageDeliverySending,
                        pendingSendContext = pendingContext
                    ),
                replyMetadata = replyMetadata.copy(parentMessageId = "local-sending")
            )
        )
        assertFalse(
            shouldShowThreadFollowAction(
                message =
                    RaftThreadMessageItem(
                        id = "local-failed",
                        authorId = "author-1",
                        authorName = "Author",
                        deliveryState = ThreadMessageDeliveryFailed,
                        pendingSendContext = pendingContext
                    ),
                replyMetadata = replyMetadata.copy(parentMessageId = "local-failed")
            )
        )
    }

    private fun markdownChannelHomeSnapshot(vararg channels: ConversationSummary): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Server"),
            currentMember =
                MemberProfile(
                    id = MemberId("member-1"),
                    displayName = "Member",
                    handle = "member",
                    kind = MemberKind.Human
                ),
            channels = channels.map { it.toHomeChannelRow() },
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun mentionSuggestion(
        displayName: String,
        handle: String,
        memberId: String,
        inMentionChannel: Boolean = true,
        kind: MemberKind = MemberKind.Human
    ): SharedMembersMemberSuggestion =
        SharedMembersMemberSuggestion(
            displayName = displayName,
            handle = handle,
            memberId = memberId,
            inMentionChannel = inMentionChannel,
            kind = kind
        )

    private fun threadMessage(
        id: String,
        authorId: String,
        authorHandle: String
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = authorId,
            authorName = authorHandle,
            authorHandle = authorHandle
        )

    private fun testThreadMessage(
        id: String,
        messageType: String = "chat"
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "u-$id",
            authorName = "User $id",
            messageType = messageType,
            body = "body $id"
        )

    private fun buildAppShellThreadPayloadFromSummary(
        conversation: ConversationSummary,
        timeContext: AppShellThreadTimeContext? = null,
        alignOwnMessagesRight: Boolean = false,
        compactConsecutiveMessages: Boolean = false,
        anchorMessageId: String? = null,
        originFallback: ThreadLaunchOriginFallback? = null
    ): RaftThreadScreenPayload =
        requireNotNull(buildAppShellThreadPayload(
            conversation = conversation.toThreadLaunchConversationIdentity(),
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            anchorMessageId = anchorMessageId,
            originFallback = originFallback,
            accessState =
                ThreadLaunchAccessState(
                    joined = conversation.joined,
                    permissions = conversation.permissions
                )
        ))

    private class FakeMarkdownTaskNumberApi(
        private val channelTaskBody: String,
        private val serverTasksBody: String,
        /** Runs inside every request (reads the reference resolver's fetch phase). */
        private val onRequest: (String) -> Unit = {},
        /** HTTP status of `GET /tasks/channel/:channelId/number/:n`. */
        private val channelTaskCode: Int = 200
    ) : RaftTasksApi {
        val calls = mutableListOf<String>()

        override suspend fun openTaskConversation(serverId: ServerId, taskId: TaskId): HomeNetworkResponse {
            calls += "openConversation:${taskId.value}"
            onRequest("openConversation")
            return HomeNetworkResponse(code = 200, bodyText = """{"conversationChannelId":"joint-local-conversation"}""")
        }

        override suspend fun serverTasks(
            serverId: ServerId,
            priority: DataRequestPriority
        ): HomeNetworkResponse {
            calls += "serverTasks:${serverId.value}"
            onRequest("serverTasks")
            return HomeNetworkResponse(code = 200, bodyText = serverTasksBody)
        }

        override suspend fun taskByNumber(
            serverId: ServerId,
            channelId: ChannelId,
            taskNumber: Int,
            priority: DataRequestPriority
        ): HomeNetworkResponse {
            calls += "taskByNumber:${serverId.value}:${channelId.value}:$taskNumber"
            onRequest("taskByNumber")
            return HomeNetworkResponse(code = channelTaskCode, bodyText = channelTaskBody)
        }

        override suspend fun updateTaskStatus(
            taskId: TaskId,
            status: TaskStatus
        ): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)
    }
}

/**
 * `POST /api/tasks/convert-message` body from slock staging (`routes/tasks.ts` ->
 * `taskService.enrichSingleLegacyTask` + `projectTaskToChannel`) for a flag-off server:
 * the task row has its own uuid; the converted message is `messageId` / `hostMessageId`.
 */
private val REAL_LEGACY_CONVERTED_TASK_JSON =
    """{"id":"7c0b8f8e-5f7a-4d0e-9b51-3a1d2b6c9e10","messageId":"message-convert","hostMessageId":"message-convert","sourceMessageId":"message-convert","conversationReplyCount":0,"conversationChannelId":null,"conversationAnchored":false,"channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":2,"title":"convert me","description":null,"status":"todo","createdByType":"user","createdById":"user-1","createdByName":"Artin","createdByHandle":"artin","createdByMembershipStatus":"active","claimedByType":null,"claimedById":null,"claimedByName":null,"claimedAt":null,"completedAt":null,"requiresResourceReceipt":false,"resourceReceipt":null,"resourceReceiptRecordedAt":null,"resourceTeardownOwnerAgentId":null,"resourceExpiryFollowupId":null,"createdAt":"2026-10-28T08:00:00.000Z","updatedAt":"2026-10-28T08:00:00.000Z","revision":1,"isLegacy":false,"readOnlyReason":null}"""

private val REAL_LEGACY_CONVERT_RESPONSE = """{"task":$REAL_LEGACY_CONVERTED_TASK_JSON}"""
