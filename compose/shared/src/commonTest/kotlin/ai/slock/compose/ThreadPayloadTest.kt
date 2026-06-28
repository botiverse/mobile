package ai.slock.compose

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.SlockTimeFormat12Hour
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.slockEpochDayForDate
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.markdown.ThreadMarkdownTag
import ai.slock.compose.markdown.ThreadMarkdownTagKind
import ai.slock.compose.data.InboxItem
import ai.slock.compose.data.HomeSnapshot
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.data.SharedConversationDirectoryProjection
import ai.slock.compose.data.SharedMembersMemberSuggestion
import ai.slock.compose.data.SharedServerMembersEntry
import ai.slock.compose.data.SharedServerMembersState
import ai.slock.compose.data.TasksUiState
import ai.slock.compose.data.ThreadMessagesPage
import ai.slock.compose.data.WireActionCardAction
import ai.slock.compose.data.WireActionCardMetadata
import ai.slock.compose.data.WireAttachment
import ai.slock.compose.data.WireMessage
import ai.slock.compose.data.WireReaction
import ai.slock.compose.data.WireThreadSummary
import ai.slock.compose.data.actionCardFromMessageMetadata
import ai.slock.compose.data.appendSharedThreadSentMessageFromPage
import ai.slock.compose.data.buildSharedThreadPayloadFromMessagesPage
import ai.slock.compose.data.parseSharedThreadPickedAttachmentFiles
import ai.slock.compose.data.parseSharedThreadUploadedAttachments
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ThreadPayloadTest {
    private val launchJson = Json { ignoreUnknownKeys = true }

    @Test
    fun threadMentionSuggestionsRankRecentParticipantsWithinChannelGroups() {
        val suggestions =
            listOf(
                mentionSuggestion("Cindy", "cindy", "u-cindy", inMentionChannel = true),
                mentionSuggestion("Build Agent", "build-agent", "agent-build", inMentionChannel = true, kind = MemberKind.Agent),
                mentionSuggestion("Artin", "artin", "u-artin", inMentionChannel = true),
                mentionSuggestion("Mina", "mina", "u-mina", inMentionChannel = false),
                mentionSuggestion("Guest", "guest", "u-guest", inMentionChannel = false)
            )
        val messages =
            listOf(
                threadMessage("m1", "u-cindy", "cindy"),
                threadMessage("m2", "u-mina", "mina"),
                threadMessage("m3", "agent-build", "build-agent"),
                threadMessage("m4", "u-artin", "artin"),
                threadMessage("m5", "u-guest", "guest")
            )

        val sorted =
            suggestions.sortForThreadMentionParticipants(
                threadMessages = messages,
                prioritizeThreadParticipants = true
            )

        assertEquals(
            listOf("artin", "build-agent", "cindy", "guest", "mina"),
            sorted.map { it.handle }
        )
    }

    @Test
    fun threadMentionSuggestionsKeepOriginalOrderWhenNotThreadRanked() {
        val suggestions =
            listOf(
                mentionSuggestion("Cindy", "cindy", "u-cindy"),
                mentionSuggestion("Artin", "artin", "u-artin")
            )
        val messages = listOf(threadMessage("m1", "u-artin", "artin"))

        val sorted =
            suggestions.sortForThreadMentionParticipants(
                threadMessages = messages,
                prioritizeThreadParticipants = false
            )

        assertEquals(listOf("cindy", "artin"), sorted.map { it.handle })
    }

    @Test
    fun threadMessagePermalinkMatchesReactChannelRoute() {
        val payload =
            KuiklyThreadScreenPayload(
                sourceThreadId = "channel-1",
                channelKind = "channel"
            )

        val permalink =
            payload.threadMessagePermalink(
                message = threadMessage("message-1", "u-artin", "artin"),
                serverSlug = "workspace"
            )

        assertEquals(
            "https://app.raft.build/s/workspace/channel/channel-1?msg=message-1",
            permalink
        )
    }

    @Test
    fun threadMessagePermalinkUsesParentThreadParamForReplyRows() {
        val payload =
            KuiklyThreadScreenPayload(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "parent-1",
                sourceThreadId = "thread-1",
                streamChannelId = "thread-1",
                parentChannelKind = "channel"
            )

        val permalink =
            payload.threadMessagePermalink(
                message = threadMessage("reply-1", "u-artin", "artin"),
                serverSlug = "workspace"
            )

        assertEquals(
            "https://app.raft.build/s/workspace/channel/channel-1?msg=reply-1&thread=channel-1%3Aparent-1",
            permalink
        )
    }

    @Test
    fun threadMessageRowPreservesFollowedThreadState() {
        val row =
            Message(
                id = MessageId("parent-1"),
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                threadChannelId = ChannelId("thread-1"),
                author = MemberProfile(
                    id = MemberId("u-artin"),
                    displayName = "Artin",
                    handle = "artin",
                    kind = MemberKind.Human
                ),
                timestampLabel = "Now",
                body = "Parent",
                replyCount = 4,
                isThreadFollowed = true
            ).toKuiklyThreadMessageItem(currentUserId = null)

        assertEquals("thread-1", row.threadChannelId)
        assertEquals(4, row.replyCount)
        assertTrue(row.isThreadFollowed)
    }

    @Test
    fun compactThreadAuthorSuppressionResetsAfterWindowDayOrAuthorChanges() {
        val base =
            KuiklyThreadMessageItem(
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
                previous = base,
                next = base.copy(id = "m5", authorId = "u-cindy", authorName = "Cindy", timestampLabel = "2026-06-27T01:05:00Z"),
                localOffsetMinutes = 0
            )
        )
        assertEquals(
            "23:50",
            ThreadTimelineRenderConfig(
                backgroundRefreshing = false,
                errorCode = null,
                originPreview = null,
                originErrorCode = null,
                timeContext =
                    SlockTimeContext(
                        localOffsetMinutes = 0,
                        todayEpochDay = slockEpochDayForDate(2026, 6, 28)
                    ),
                members = emptyList()
            ).formatCompactTimestampLabel("2026-06-27T23:50:00Z")
        )
    }

    @Test
    fun markdownChannelLaunchPayloadResolvesChannelNameToIdentityOnlyRoute() {
        val payload =
            buildMarkdownChannelLaunchPayload(
                currentPayload =
                    KuiklyThreadScreenPayload(
                        instanceId = "current-thread",
                        alignOwnMessagesRight = true,
                        localOffsetMinutes = 480,
                        timeFormat = SlockTimeFormat12Hour
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
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.channelKind)
        assertEquals("channel-general-id", payload.sourceThreadId)
        assertEquals("channel-general-id", payload.streamChannelId)
        assertEquals("chat", payload.selectedTab)
        assertEquals("loading", payload.status)
        assertEquals(emptyList(), payload.messages)
        assertEquals(true, payload.alignOwnMessagesRight)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(SlockTimeFormat12Hour, payload.timeFormat)
    }

    @Test
    fun markdownChannelLaunchPayloadPreservesJointChannelKind() {
        val payload =
            buildMarkdownChannelLaunchPayload(
                currentPayload = KuiklyThreadScreenPayload(),
                home =
                    markdownChannelHomeSnapshot(
                        ConversationSummary(
                            threadId = ThreadId("joint-local-id"),
                            channelId = ChannelId("joint-local-id"),
                            channelType = "joint",
                            kind = ConversationKind.Joint,
                            name = "members专修"
                        )
                    ),
                channelLabel = "members专修"
            )

        checkNotNull(payload)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("joint", payload.channelKind)
        assertEquals("joint-local-id", payload.streamChannelId)
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

        assertEquals(null, buildMarkdownChannelLaunchPayload(KuiklyThreadScreenPayload(), home, "#missing"))
        assertEquals(null, buildMarkdownChannelLaunchPayload(KuiklyThreadScreenPayload(), home, "#design"))
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
    fun threadComposerDoesNotWaitForContentExtrasOrBottomAnchorReadiness() {
        assertTrue(
            shouldShowThreadComposerForReadyContent(
                joined = true,
                showComposer = true,
                selectedTab = "chat"
            )
        )
    }

    @Test
    fun threadComposerCanRenderWhileLoadingWhenCachedContentIsVisible() {
        assertFalse(KuiklyThreadScreenPayload(status = "loading").hasRenderableContentForComposer())
        assertTrue(
            KuiklyThreadScreenPayload(
                status = "loading",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "cached"
                        )
                    )
            ).hasRenderableContentForComposer()
        )
        assertTrue(KuiklyThreadScreenPayload(status = "content").hasRenderableContentForComposer())
        assertFalse(KuiklyThreadScreenPayload(status = "error").hasRenderableContentForComposer())
        assertFalse(KuiklyThreadScreenPayload(status = "no_permission").hasRenderableContentForComposer())
    }

    @Test
    fun consumeComposerSeedForSendRemovesOnlySentAttachments() {
        val payload =
            KuiklyThreadScreenPayload(
                composer =
                    KuiklyThreadComposerPayload(
                        seedText = "Diagnostics attached",
                        seedAsTask = true,
                        syncNonce = 7,
                        pendingAttachments =
                            listOf(
                                KuiklyPendingAttachmentItem(id = "diagnostics-att-1", fileName = "slock-feedback.txt"),
                                KuiklyPendingAttachmentItem(id = "manual-att-2", fileName = "manual.txt")
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
    fun consumeComposerSeedForSendPreservesUnmatchedSeedText() {
        val payload =
            KuiklyThreadScreenPayload(
                composer =
                    KuiklyThreadComposerPayload(
                        seedText = "Keep this draft",
                        syncNonce = 2,
                        pendingAttachments =
                            listOf(
                                KuiklyPendingAttachmentItem(id = "diagnostics-att-1", fileName = "slock-feedback.txt")
                            )
                    )
            )

        val consumed =
            payload.consumeComposerSeedForSend(
                sentBody = "Different body",
                sentAttachmentIds = setOf("diagnostics-att-1")
            )

        assertEquals("Keep this draft", consumed.composer.seedText)
        assertEquals(3, consumed.composer.syncNonce)
        assertEquals(emptyList(), consumed.composer.pendingAttachments)
    }

    @Test
    fun serverMembersRosterResolvesAgentStatusWithoutMutatingThreadMessages() {
        val payload =
            KuiklyThreadScreenPayload(
                originMessage =
                    KuiklyThreadMessageItem(
                        id = "origin",
                        authorId = "agent-1",
                        authorName = "Agent",
                        authorKind = "agent",
                        authorAgentStatus = AgentStatus.Online.name,
                        body = "origin"
                    ),
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "agent-1",
                            authorName = "Agent",
                            authorKind = "agent",
                            authorAgentStatus = AgentStatus.Online.name,
                            body = "hello"
                        ),
                        KuiklyThreadMessageItem(
                            id = "m2",
                            authorId = "u1",
                            authorName = "Artin",
                            authorKind = "human",
                            body = "human"
                        )
                    )
            )
        val serverMembersState =
            SharedServerMembersState(
                entriesByServerId =
                    mapOf(
                        "server-1" to
                            SharedServerMembersEntry(
                                serverId = "server-1",
                                members =
                                    listOf(
                                        MemberProfile(
                                            id = MemberId("agent-1"),
                                            displayName = "Agent",
                                            handle = "agent",
                                            kind = MemberKind.Agent,
                                            presence = AgentStatus.Thinking.presence,
                                            agentStatus = AgentStatus.Thinking
                                        )
                                    )
                            )
                    )
            )

        val lookup =
            ThreadAgentStatusLookup.from(
                serverMembersState = serverMembersState,
                serverId = "server-1"
            )

        val originMessage = payload.originMessage!!
        assertEquals(AgentStatus.Thinking, lookup.indicatorStateForMemberId(originMessage.authorId)?.status)
        assertEquals(AgentStatus.Thinking, lookup.indicatorStateForMemberId(payload.messages[0].authorId)?.status)
        assertEquals(null, lookup.indicatorStateForMemberId(payload.messages[1].authorId))
        assertEquals(AgentStatus.Online.name, originMessage.authorAgentStatus)
        assertEquals(AgentStatus.Online.name, payload.messages[0].authorAgentStatus)
    }

    @Test
    fun serverMembersPresenceFactResolvesAgentStatusWithoutLoadedRoster() {
        val payload =
            KuiklyThreadScreenPayload(
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "agent-1",
                            authorName = "Agent",
                            authorKind = "agent",
                            authorAgentStatus = AgentStatus.Online.name,
                            body = "hello"
                        )
                    )
            )
        val serverMembersState =
            SharedServerMembersState(
                presenceByMemberId =
                    mapOf(
                        "agent-1" to
                            MemberPresenceUpdated(
                                memberId = MemberId("agent-1"),
                                presence = AgentStatus.Working.presence,
                                kind = MemberKind.Agent,
                                agentStatus = AgentStatus.Working
                            )
                    )
            )

        val lookup =
            ThreadAgentStatusLookup.from(
                serverMembersState = serverMembersState,
                serverId = "server-1"
            )

        assertEquals(AgentStatus.Working, lookup.indicatorStateForMemberId(payload.messages[0].authorId)?.status)
        assertEquals(AgentStatus.Online.name, payload.messages[0].authorAgentStatus)
    }

    @Test
    fun threadQuickReactionsIncludeServerCheckMarkEmoji() {
        assertTrue("✅" in ThreadQuickReactionEmojis)
    }

    @Test
    fun defaultThreadMessageVisualFixtureMatchesReactProviderSeed() {
        val payload = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT, "visual-thread-message-row")
        val message = payload.messages.single()

        assertEquals("visual-thread-message-row", payload.instanceId)
        assertEquals("channel-design", payload.streamChannelId)
        assertFalse(payload.showReplyActions)
        assertFalse(payload.showTaskOpenActions)
        assertFalse(message.isOwn)
        assertEquals("agent-cindy", message.authorId)
        assertEquals("Cindy", message.authorName)
        assertEquals("agent", message.authorKind)
        assertEquals("Captured the Android visual artifact for #product:f8e569cb and queued React parity.", message.body)
        assertEquals("thread-msg-agent-reply", message.threadChannelId)
        assertEquals(null, message.taskNumber)
        assertEquals(0, message.replyCount)
        assertEquals(1, message.reactions.size)
        assertEquals("👍", message.reactions.single().emoji)
        assertEquals(2, message.reactions.single().count)
        assertFalse(message.reactions.single().selected)
    }

    @Test
    fun longInlineCodeVisualFixtureContainsSingleCodeSpan() {
        val payload = threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_LONG_INLINE_CODE, "visual-thread-message-row-long-inline-code")
        val message = payload.messages.single()

        assertEquals("visual-thread-message-row-long-inline-code", payload.instanceId)
        assertEquals("channel-markdown", payload.streamChannelId)
        assertFalse(payload.showReplyActions)
        assertFalse(payload.showTaskOpenActions)
        assertEquals("Cindy", message.authorName)
        assertFalse(message.body.contains("```"))
        assertEquals(6, message.body.count { it == '`' })
        assertTrue(message.body.contains("reply/channel-markdown/msg-inline-visual"))
        assertTrue(message.body.contains("owner-key-consumes-thread-route-identity-only"))
        assertTrue(message.body.contains("./gradlew :shared:testDebugUnitTest --tests ai.slock.compose.data.InboxUiStateTest"))
        assertTrue(message.body.contains("compose/shared/src/commonTest/kotlin/ai/slock/compose/data/InboxUiStateTest.kt"))
    }

    @Test
    fun genericAttachmentNoPreviewMetaLineKeepsSizeAndMimeType() {
        val attachment =
            KuiklyThreadAttachmentItem(
                id = "att-1",
                name = "archive.zip",
                sizeLabel = "42 KB",
                mimeType = "application/zip"
            )

        assertEquals("42 KB · application/zip", attachment.genericAttachmentMetaLine())
    }

    @Test
    fun attachmentChipMetaMatchesReactPreviewLabels() {
        val pdf =
            KuiklyThreadAttachmentItem(
                id = "att-pdf",
                name = "visual-parity-notes.pdf",
                sizeLabel = "184 KB",
                mimeType = "application/pdf"
            )
        val html =
            KuiklyThreadAttachmentItem(
                id = "att-html",
                name = "thread-screen-preview.html",
                sizeLabel = "32 KB",
                mimeType = "text/html"
            )

        assertEquals("application/pdf · 184 KB", pdf.threadAttachmentChipMetaLine())
        assertEquals("Document preview", pdf.threadAttachmentSummaryLine())
        assertEquals("HTML preview · 32 KB", html.threadAttachmentChipMetaLine())
        assertEquals(null, html.threadAttachmentSummaryLine())
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
    fun standaloneThreadAuthRequiredPreservesExistingSession() {
        val session =
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-artin",
                        email = "artin@example.com"
                    ),
                accessToken = "access-token",
                refreshToken = "refresh-token"
            )

        assertEquals(
            StandaloneThreadAuthRequiredAction.PreserveSession,
            standaloneThreadAuthRequiredAction(session)
        )
        assertEquals(
            StandaloneThreadAuthRequiredAction.OpenLogin,
            standaloneThreadAuthRequiredAction(null)
        )
    }

    @Test
    fun threadMessagesLazyItemCountTracksScrollableRows() {
        assertEquals(
            4,
            threadMessagesLazyItemCount(
                originStatus = "none",
                showHistoryBoundary = true,
                messagesCount = 3,
                canLoadMoreAfter = false
            )
        )
        assertEquals(
            6,
            threadMessagesLazyItemCount(
                originStatus = "content",
                showHistoryBoundary = true,
                messagesCount = 3,
                canLoadMoreAfter = true
            )
        )
        assertEquals(
            7,
            threadMessagesLazyItemCount(
                originStatus = "content",
                showHistoryBoundary = true,
                messagesCount = 3,
                canLoadMoreAfter = true,
                showBottomScrollPadding = true
            )
        )
        assertEquals(
            3,
            threadMessagesLazyItemCount(
                originStatus = "content",
                showHistoryBoundary = false,
                messagesCount = 0,
                canLoadMoreAfter = false,
                showReplyEmptyState = true,
                showBottomScrollPadding = true
            )
        )
        assertEquals(
            3,
            sharedListBeyondBoundsItemCount(
                threadMessagesLazyItemCount(
                    originStatus = "none",
                    showHistoryBoundary = true,
                    messagesCount = 1,
                    canLoadMoreAfter = false
                )
            )
        )
    }

    @Test
    fun threadOriginCountsAsScrollableListRowAgain() {
        assertEquals(
            51,
            threadMessagesLazyItemCount(
                originStatus = "content",
                showHistoryBoundary = true,
                messagesCount = 49,
                canLoadMoreAfter = false
            )
        )
        assertEquals(
            4,
            KuiklyThreadScreenPayload(
                originStatus = "content",
                messages = (0 until 3).map { index -> testThreadMessage("reply-$index") }
            ).latestMessageListItemIndex()
        )
    }

    @Test
    fun threadBottomScrollPaddingAnchorsToLastLazyRow() {
        val payload =
            KuiklyThreadScreenPayload(
                originStatus = "content",
                messages = (0 until 3).map { index -> testThreadMessage("reply-$index") },
                canLoadMoreAfter = true
            )
        val lazyItemCount =
            threadMessagesLazyItemCount(
                originStatus = payload.originStatus,
                showHistoryBoundary = payload.messages.isNotEmpty(),
                messagesCount = payload.messages.size,
                canLoadMoreAfter = payload.canLoadMoreAfter,
                showBottomScrollPadding = true
            )

        assertEquals(4, payload.latestMessageListItemIndex())
        assertEquals(6, payload.toThreadTimelineSlice().bottomScrollPaddingListItemIndex(lazyItemCount))
    }

    @Test
    fun threadRealOlderBoundaryRendersWithFullMessageList() {
        val fullPayload =
            KuiklyThreadScreenPayload(
                messages = listOf(testThreadMessage("reply-0"), testThreadMessage("reply-1")),
                canLoadMoreBefore = false,
                olderMessagesStatus = "idle"
            )

        assertEquals(2, fullPayload.latestMessageListItemIndex())
        assertEquals(
            3,
            threadMessagesLazyItemCount(
                originStatus = fullPayload.originStatus,
                showHistoryBoundary = true,
                messagesCount = fullPayload.messages.size,
                canLoadMoreAfter = fullPayload.canLoadMoreAfter
            )
        )
    }

    @Test
    fun threadBottomAnchorOffsetBottomAlignsShortLatestItemInLongLists() {
        assertEquals(
            0,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 120,
                viewportHeight = 640,
                visibleItemCount = 3,
                totalItemCount = 3
            )
        )
        assertEquals(
            -520,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 120,
                viewportHeight = 640,
                visibleItemCount = 3,
                totalItemCount = 12
            )
        )
        assertEquals(
            160,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 800,
                viewportHeight = 640,
                visibleItemCount = 1,
                totalItemCount = 3
            )
        )
    }

    @Test
    fun threadOriginContentInitializesAtLatestItem() {
        assertEquals(8, threadInitialFirstVisibleItemIndex(latestItemIndex = 8))
    }

    @Test
    fun focusedMessageScrollOffsetCentersShortItemsAndTopAlignsTallItems() {
        assertEquals(
            -260,
            threadFocusedItemScrollOffset(
                itemSize = 120,
                viewportHeight = 640
            )
        )
        assertEquals(
            0,
            threadFocusedItemScrollOffset(
                itemSize = 800,
                viewportHeight = 640
            )
        )
        assertEquals(
            0,
            threadFocusedItemScrollOffset(
                itemSize = 640,
                viewportHeight = 640
            )
        )
        assertEquals(
            0,
            threadFocusedItemScrollOffset(
                itemSize = 0,
                viewportHeight = 640
            )
        )
    }

    @Test
    fun floatingOverlayPositionHandlesCrampedAndBottomAnchors() {
        val cramped = SlockFloatingOverlayLayout(size = Size(width = 200f, height = 100f))
        assertEquals(
            Offset(8f, 8f),
            cramped.positionContent(
                anchor = Offset(120f, 120f),
                contentSize = Size(width = 320f, height = 240f),
                viewportGap = 8f
            )
        )

        val roomy = SlockFloatingOverlayLayout(size = Size(width = 420f, height = 560f))
        assertEquals(
            Offset(200f, 338f),
            roomy.positionContent(
                anchor = Offset(360f, 1260f),
                contentSize = Size(width = 212f, height = 214f),
                viewportGap = 8f,
                horizontalOffset = -16f,
                verticalOffset = 8f,
                verticalPlacement = SlockFloatingVerticalPlacement.Auto
            )
        )

        val rightEdgeFilter = SlockFloatingOverlayLayout(size = Size(width = 390f, height = 844f))
        assertEquals(
            Offset(162f, 108f),
            rightEdgeFilter.positionContent(
                anchor = Offset(318f, 100f),
                contentSize = Size(width = 220f, height = 360f),
                viewportGap = 8f,
                verticalOffset = 8f,
                verticalPlacement = SlockFloatingVerticalPlacement.Auto,
                horizontalAutoFlip = false
            )
        )
    }

    @Test
    fun typedPayloadKeepsMembersAndSettingsActions() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                title = "对话流专修",
                membersCount = 2,
                canAddMembers = false,
                addMemberStatus = "failed",
                addMemberErrorCode = "member_exists",
                removeMemberStatus = "failed",
                removingMemberId = "a1",
                removeMemberErrorCode = "member_remove_failed",
                members =
                    listOf(
                        KuiklyThreadMemberItem(id = "u1", displayName = "Artin", handle = "artin", roleLabel = "Owner"),
                        KuiklyThreadMemberItem(id = "a1", displayName = "Android Bot", kind = "agent", avatarLabel = "AB")
                    ),
                memberCandidates =
                    listOf(
                        KuiklyThreadMemberItem(id = "u2", displayName = "Cindy", handle = "cindy"),
                        KuiklyThreadMemberItem(id = "a2", displayName = "QA Bot", kind = "agent")
                    ),
                settingsActions =
                    listOf(
                        KuiklyThreadSettingsAction(id = "visibility"),
                        KuiklyThreadSettingsAction(id = "archive"),
                        KuiklyThreadSettingsAction(id = "delete")
                    )
            )

        assertEquals("thread-1", payload.instanceId)
        assertEquals("对话流专修", payload.title)
        assertEquals(2, payload.membersCount)
        assertEquals(false, payload.canAddMembers)
        assertEquals("failed", payload.addMemberStatus)
        assertEquals("member_exists", payload.addMemberErrorCode)
        assertEquals("failed", payload.removeMemberStatus)
        assertEquals("a1", payload.removingMemberId)
        assertEquals("member_remove_failed", payload.removeMemberErrorCode)
        assertEquals("Artin", payload.members.first().displayName)
        assertEquals("agent", payload.members[1].kind)
        assertEquals("Cindy", payload.memberCandidates.first().displayName)
        assertEquals("agent", payload.memberCandidates[1].kind)
        assertEquals("visibility", payload.settingsActions.first().id)
        assertEquals("archive", payload.settingsActions[1].id)
        assertEquals("delete", payload.settingsActions[2].id)
        assertEquals(true, payload.settingsActions.normalizedThreadSettingsChannelActions().all { it.destructive })
    }

    @Test
    fun invalidPayloadReturnsErrorState() {
        val payload = parseThreadScreenPayload("{not-json", "bad-1")

        assertEquals("bad-1", payload.instanceId)
        assertEquals("error", payload.status)
        assertEquals("payload_parse_failed", payload.errorCode)
    }

    @Test
    fun fullPayloadWithoutLaunchIdentityReturnsErrorState() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-legacy",
                      "title": "Legacy full payload",
                      "messages": [{"id": "m1", "body": "must not load"}],
                      "members": [{"id": "u1", "displayName": "Artin"}],
                      "filesPayload": "{\"status\":\"content\"}",
                      "composer": {"seedText": "must not load"}
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("fallback", payload.instanceId)
        assertEquals("error", payload.status)
        assertEquals("payload_parse_failed", payload.errorCode)
        assertEquals(emptyList(), payload.messages)
        assertEquals(emptyList(), payload.members)
        assertEquals("", payload.resolveThreadComposer().seedText)
    }

    @Test
    fun sourceThreadLaunchPayloadStartsStoreBackedLoadingState() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-launch-1",
                      "title": "general",
                      "status": "content",
                      "sourceThreadId": "channel-1",
                      "streamChannelId": "channel-1",
                      "membersCount": 2,
                      "joined": false,
                      "canSend": false,
                      "settingsActions": [
                        {"id": "archive", "enabled": true}
                      ]
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("thread-launch-1", payload.instanceId)
        assertEquals("Channel", payload.title)
        assertEquals("loading", payload.status)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(0, payload.membersCount)
        assertEquals(true, payload.joined)
        assertEquals(true, payload.canSend)
        assertEquals(emptyList<KuiklyThreadMessageItem>(), payload.messages)
        assertEquals(emptyList<KuiklyThreadTaskItem>(), payload.tasks)
        assertEquals(emptyList<KuiklyThreadMemberItem>(), payload.members)
        assertEquals(emptyList<KuiklyThreadMemberItem>(), payload.memberCandidates)
        assertEquals("", payload.resolveThreadComposer().seedText)
        assertEquals(false, payload.resolveThreadComposer().seedAsTask)
        assertEquals(emptyList(), payload.settingsActions)
    }

    @Test
    fun threadFilesRouteStartPayloadIgnoresLegacyFilesPayloadSeed() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-launch-1",
                      "sourceThreadId": "channel-1",
                      "streamChannelId": "channel-1",
                      "filesPayload": "{\"instanceId\":\"seed-instance\",\"status\":\"content\",\"items\":[{\"attachmentId\":\"seed-file\",\"name\":\"must-not-seed.pdf\"}]}"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        val filesPayload = payload.initialThreadFilesRoutePayload()

        assertEquals("thread-launch-1", filesPayload.instanceId)
        assertEquals("loading", filesPayload.status)
        assertEquals(emptyList(), filesPayload.items)
        assertEquals(false, filesPayload.hasMore)
        assertEquals(null, filesPayload.nextCursor)
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun launchWireSchemaExcludesBigThreadStateFields() {
        val descriptor = WireThreadLaunchPayload.serializer().descriptor
        val names = (0 until descriptor.elementsCount)
            .map { index -> descriptor.getElementName(index) }
            .toSet()

        listOf(
            "messages",
            "tasks",
            "members",
            "memberCandidates",
            "filesPayload",
            "composer",
            "composerPendingAttachments",
            "pendingAttachments",
            "title",
            "subtitle",
            "memberScopedMentions",
            "showModeTabs",
            "showReplyActions",
            "showComposer",
            "joined",
            "canSend",
            "membersCount",
            "canAddMembers",
            "settingsActions",
            "showMembersAction",
            "showSettingsAction",
            "showOpenInChannelAction",
            "showMessageSaveAction",
            "showTaskOpenActions",
            "showTaskStatusActions",
            "emptyTitle",
            "emptyDetail",
            "originMessage",
            "originPreview",
            "originStatus",
            "joinLabel",
            "dmPeerName",
            "dmPeerAvatarUrl",
            "dmPeerAvatarLabel",
            "dmPeerGravatarUrl"
        ).forEach { forbidden ->
            assertFalse(forbidden in names, "$forbidden must stay outside compose_thread launch pageData")
        }
    }

    @Test
    fun launchPayloadIgnoresLegacyPendingAttachmentRouteState() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-channel-1",
                      "surfaceKind": "channel",
                      "sourceThreadId": "channel-1",
                      "streamChannelId": "channel-1",
                      "composerSeedText": "draft from route",
                      "composerPendingAttachments": [
                        {
                          "id": "att-route",
                          "fileName": "route.txt",
                          "status": "ready"
                        }
                      ]
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("draft from route", payload.composer.seedText)
        assertEquals(emptyList(), payload.composer.pendingAttachments)
    }

    @Test
    fun launchPayloadCanUseThreadIdAliasFromLaunchSource() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "page-1",
                      "threadId": "dm-1",
                      "title": "@cindy",
                      "surfaceKind": "dm",
                      "selectedTab": "files",
                      "canSend": false
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("page-1", payload.instanceId)
        assertEquals("Direct Message", payload.title)
        assertEquals("dm-1", payload.sourceThreadId)
        assertEquals("dm-1", payload.streamChannelId)
        assertEquals("files", payload.selectedTab)
        assertEquals(true, payload.canSend)
        assertEquals(true, payload.memberScopedMentions)
        assertEquals(true, payload.resolveThreadComposer().enabled)
    }

    @Test
    fun launchPayloadDmPeerHintsAreIgnoredUntilStoreProjectionArrives() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "page-dm",
                      "threadId": "dm-artin",
                      "surfaceKind": "dm",
                      "dmPeerName": "artin",
                      "dmPeerAvatarUrl": "https://cdn.slock.ai/avatars/artin.webp",
                      "dmPeerAvatarLabel": "AR",
                      "dmPeerGravatarUrl": "https://www.gravatar.com/avatar/artin"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("page-dm", payload.instanceId)
        assertEquals("Direct Message", payload.title)
        assertEquals("dm-artin", payload.sourceThreadId)
        assertEquals("dm-artin", payload.streamChannelId)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals(null, payload.dmPeerAvatarLabel)
        assertEquals(null, payload.dmPeerGravatarUrl)
    }

    @Test
    fun launchPayloadDmWithoutPeerNameFallsBackToDirectMessage() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "page-dm",
                      "threadId": "dm-1",
                      "surfaceKind": "dm"
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
    }

    @Test
    fun launchPayloadDmEmptyPeerNameFallsBackToDirectMessage() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "page-dm",
                      "threadId": "dm-1",
                      "surfaceKind": "dm",
                      "dmPeerName": "   ",
                      "dmPeerAvatarUrl": ""
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
    }

    @Test
    fun appShellDmPayloadUsesConversationHeaderProjectionForPeerHeaderFields() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("dm-artin"),
                channelId = ChannelId("dm-artin"),
                kind = ConversationKind.DirectMessage,
                name = "artin",
                permissions = ConversationPermissions(canSend = true)
            )
        val payload =
            buildAppShellThreadPayload(conversation)
        val headerDisplay =
            payload.threadHeaderDisplayModel(payload.conversationHeaderEntryFrom(conversation))

        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals("artin", headerDisplay.title)
        assertEquals(null, headerDisplay.dmPeerAvatarWireValue)
        assertEquals("AR", headerDisplay.dmPeerAvatarLabel)
        assertEquals(null, headerDisplay.dmPeerGravatarUrl)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
    }

    @Test
    fun appShellAgentDmHeaderProjectionRejectsGravatarAvatar() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("dm-agent-1"),
                channelId = ChannelId("dm-agent-1"),
                kind = ConversationKind.DirectMessage,
                name = "Product-UX-Designer",
                directMessagePeerType = "agent",
                permissions = ConversationPermissions(canSend = true)
            )
        val payload = buildAppShellThreadPayload(conversation)
        val headerDisplay = payload.threadHeaderDisplayModel(payload.conversationHeaderEntryFrom(conversation))

        assertEquals("Product-UX-Designer", headerDisplay.title)
        assertEquals(null, headerDisplay.dmPeerAvatarWireValue)
        assertEquals("PR", headerDisplay.dmPeerAvatarLabel)
        assertEquals(null, headerDisplay.dmPeerGravatarUrl)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals(null, payload.dmPeerGravatarUrl)
    }

    @Test
    fun appShellChannelPayloadUsesConversationHeaderProjectionForTitleOnly() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "首页专修",
                subtitle = "Home repair",
                permissions = ConversationPermissions(canSend = true)
            )
        val payload = buildAppShellThreadPayload(conversation)
        val headerDisplay = payload.threadHeaderDisplayModel(payload.conversationHeaderEntryFrom(conversation))

        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("Channel", payload.title)
        assertEquals("首页专修", headerDisplay.title)
        assertEquals("Home repair", headerDisplay.subtitle)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals("Message #首页专修", payload.resolveThreadComposer(displayName = headerDisplay.title).placeholder)
    }

    @Test
    fun directoryProjectionUpdatesThreadHeaderWithoutChangingRouteIdentity() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "Channel",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = KuiklyThreadSurfaceKind.Channel
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1", "stream-channel-1"),
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
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
    fun channelHeaderIgnoresPayloadTitleWithoutDirectoryProjection() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "wrong-network-title",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "joint"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(null)

        assertEquals("Channel", headerDisplay.title)
        assertEquals("joint", headerDisplay.channelKind)
    }

    @Test
    fun contentSliceDoesNotOverwriteHeaderTitleFromNetworkPayload() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "Channel",
                subtitle = null,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "joint"
            )
        val networkPatch =
            payload.copy(
                title = "1fe17699-4cc4-4331-bb98-f7a327b947ac",
                subtitle = "network subtitle",
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "user-1",
                            authorName = "Artin",
                            body = "body"
                        )
                    )
            )

        val merged = payload.withContentSliceFrom(networkPatch)

        assertEquals("Channel", merged.title)
        assertEquals(null, merged.subtitle)
        assertEquals("content", merged.status)
        assertEquals(listOf("m1"), merged.messages.map { it.id })
    }

    @Test
    fun threadHeaderProjectionInputKeepsLastAcceptedProjectionWhenCurrentIsEmpty() {
        val routePayload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "joint"
            )
        val input = routePayload.threadHeaderProjectionInput()
        val acceptedProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "joint",
                title = "raft-mobile",
                subtitle = "Mobile work"
            )

        val stableProjection = input.stableProjection(current = null, previous = acceptedProjection)
        val headerDisplay = input.displayModel(stableProjection)

        assertEquals(acceptedProjection, stableProjection)
        assertEquals("raft-mobile", headerDisplay.title)
        assertEquals("joint", headerDisplay.channelKind)
    }

    @Test
    fun dmHeaderProjectionInputKeepsLastAcceptedAvatarWhenCurrentIsEmpty() {
        val routePayload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-dm-1",
                title = "",
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                channelKind = "dm"
            )
        val input = routePayload.threadHeaderProjectionInput()
        val acceptedProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "dm-1",
                aliases = setOf("dm-1"),
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                title = "artin",
                avatarWireValue = "pixel:crown",
                avatarLabel = "AR",
                peerMemberId = "member-artin"
            )

        val stableProjection = input.stableProjection(current = null, previous = acceptedProjection)
        val headerDisplay = input.displayModel(stableProjection)

        assertEquals(acceptedProjection, stableProjection)
        assertEquals("artin", headerDisplay.title)
        assertEquals("pixel:crown", headerDisplay.dmPeerAvatarWireValue)
        assertEquals("AR", headerDisplay.dmPeerAvatarLabel)
        assertEquals("member-artin", headerDisplay.dmPeerMemberId)
    }

    @Test
    fun jointPayloadIgnoresGenericChannelDirectoryProjection() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-joint-1",
                title = "partner",
                sourceThreadId = "joint-1",
                streamChannelId = "joint-1",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "joint"
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "joint-1",
                aliases = setOf("joint-1"),
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "partner",
                subtitle = "Generic projection"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertFalse(payload.acceptsConversationHeaderProjection(projection))
        assertEquals("joint", headerDisplay.channelKind)
        assertEquals(SlockLucideIconName.GitBranch, headerDisplay.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun genericChannelPayloadAcceptsSpecificDirectoryChannelKind() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-private-1",
                title = "ops",
                sourceThreadId = "private-1",
                streamChannelId = "private-1",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "channel"
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "private-1",
                aliases = setOf("private-1"),
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "private",
                title = "ops"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertTrue(payload.acceptsConversationHeaderProjection(projection))
        assertEquals("private", headerDisplay.channelKind)
        assertEquals(SlockLucideIconName.Lock, headerDisplay.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun directoryProjectionDrivesDmHeaderAvatarWithoutChangingRouteIdentity() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "dm-1",
                title = "Direct Message",
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                dmPeerMemberId = "agent-1",
                dmPeerName = null,
                dmPeerAvatarUrl = null,
                dmPeerGravatarUrl = "https://www.gravatar.com/avatar/agent"
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "dm-1",
                aliases = setOf("dm-1", "agent-1"),
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                title = "Product-UX-Designer",
                avatarWireValue = "pixel:robot",
                avatarLabel = "PR",
                peerMemberId = "agent-1"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertEquals("Product-UX-Designer", headerDisplay.title)
        assertEquals("pixel:robot", headerDisplay.dmPeerAvatarWireValue)
        assertEquals("PR", headerDisplay.dmPeerAvatarLabel)
        assertEquals("agent-1", headerDisplay.dmPeerMemberId)
        assertEquals("https://www.gravatar.com/avatar/agent", headerDisplay.dmPeerGravatarUrl)
        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals("dm-1", payload.sourceThreadId)
        assertEquals("dm-1", payload.streamChannelId)
    }

    @Test
    fun directoryProjectionDoesNotChangeReplyThreadRequestIdentity() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "reply-message-1",
                title = ThreadReplyPageTitle,
                sourceThreadId = "reply-thread-1",
                streamChannelId = "reply-thread-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general"
            )
        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertEquals(ThreadReplyPageTitle, headerDisplay.title)
        assertEquals("#general", headerDisplay.subtitle)
        assertEquals(null, payload.subtitle)
        assertEquals("reply-thread-1", payload.sourceThreadId)
        assertEquals("reply-thread-1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
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
            KuiklyThreadScreenPayload(
                instanceId = "notification-thread",
                title = "Channel",
                sourceThreadId = uuid,
                streamChannelId = uuid,
                surfaceKind = KuiklyThreadSurfaceKind.Channel
            )
        val headerDisplay = payload.threadHeaderDisplayModel(payload.conversationHeaderEntryFrom(conversation))

        assertEquals("Channel", headerDisplay.title)
    }

    @Test
    fun replyThreadHeaderUsesMatchedParentDirectoryProjectionForSubtitle() {
        val payload =
            KuiklyThreadScreenPayload(
                title = ThreadReplyPageTitle,
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                originParentMessageId = "message-1",
                subtitle = null
            )
        val projection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general",
                subtitle = "General updates"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(projection)

        assertEquals(ThreadReplyPageTitle, headerDisplay.title)
        assertEquals("#general", headerDisplay.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, headerDisplay.surfaceKind)
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun replyThreadHeaderKeepsExistingSafeChannelSubtitleWithoutDirectoryProjection() {
        val payload =
            KuiklyThreadScreenPayload(
                title = ThreadReplyPageTitle,
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                subtitle = "#product"
            )

        val headerDisplay = payload.threadHeaderDisplayModelFromDirectory(null)

        assertEquals("#product", headerDisplay.subtitle)
    }

    @Test
    fun appShellPrivateChannelPayloadUsesLockHeaderKind() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("private-1"),
                channelId = ChannelId("private-1"),
                channelType = "private",
                kind = ConversationKind.Channel,
                name = "onboarding-slocktest",
                permissions = ConversationPermissions(canSend = true)
            )
        val payload = buildAppShellThreadPayload(conversation)
        val headerDisplay = payload.threadHeaderDisplayModel(payload.conversationHeaderEntryFrom(conversation))

        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("private", payload.channelKind)
        assertEquals("private", headerDisplay.channelKind)
        assertEquals(SlockLucideIconName.Lock, headerDisplay.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun appShellJointChannelPayloadUsesBranchHeaderKindWhenRawTypeIsGenericChannel() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("joint-1"),
                channelId = ChannelId("joint-1"),
                channelType = "channel",
                kind = ConversationKind.Joint,
                name = "partner",
                permissions = ConversationPermissions(canSend = true)
            )
        val payload = buildAppShellThreadPayload(conversation)
        val headerDisplay = payload.threadHeaderDisplayModel(payload.conversationHeaderEntryFrom(conversation))

        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("joint", payload.channelKind)
        assertEquals("joint", headerDisplay.channelKind)
        assertEquals(SlockLucideIconName.GitBranch, headerDisplay.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun appShellPayloadMarksJointChannelsAsMemberScopedMentions() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("joint-1"),
                    channelId = ChannelId("joint-1"),
                    channelType = "joint",
                    kind = ConversationKind.Joint,
                    name = "partner",
                    permissions = ConversationPermissions(canSend = true)
                )
            )

        assertEquals(true, payload.memberScopedMentions)
        assertEquals("joint-1", payload.resolveThreadComposer().streamChannelId)
    }

    @Test
    fun wireThreadLaunchPayloadCarriesChannelKindIntoInitialPayload() {
        val payload =
            WireThreadLaunchPayload(
                instanceId = "joint-thread",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "joint",
                sourceThreadId = "joint-1",
                streamChannelId = "joint-1"
            ).toInitialPayload("fallback")

        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("joint", payload.channelKind)
        assertEquals(SlockLucideIconName.GitBranch, payload.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun memberSuggestionRealtimeRefreshOnlyTracksRelevantMemberScopes() {
        assertTrue(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.ChannelMembers,
                reason = "channel:members-updated",
                channelId = ChannelId("thread-1")
            ).refreshesThreadMemberSuggestions("thread-1")
        )
        assertFalse(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.ChannelMembers,
                reason = "channel:members-updated",
                channelId = ChannelId("other")
            ).refreshesThreadMemberSuggestions("thread-1")
        )
        assertFalse(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.ChannelMembers,
                reason = "channel:members-updated",
                channelId = ChannelId("thread-1")
            ).refreshesThreadMemberSuggestions(null)
        )
        assertTrue(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.ServerMembers,
                reason = "server:member-updated"
            ).refreshesThreadMemberSuggestions("thread-1")
        )
        assertFalse(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.Home,
                reason = "channel:updated",
                channelId = ChannelId("thread-1")
            ).refreshesThreadMemberSuggestions("thread-1")
        )
    }

    @Test
    fun threadSettingsActionsDropLegacyLeaveFallback() {
        val actions =
            listOf(
                KuiklyThreadSettingsAction(
                    id = "leave"
                )
            ).normalizedThreadSettingsChannelActions()

        assertEquals(
            listOf("visibility", "archive", "delete"),
            actions.map { it.id }
        )
        assertEquals(
            listOf("Make Private", "Archive Channel", "Delete Channel"),
            actions.map { it.title }
        )
    }

    @Test
    fun threadSettingsDisabledRecognizedActionsDoNotFallbackToDefaults() {
        val actions =
            listOf(
                KuiklyThreadSettingsAction(id = "visibility", enabled = false),
                KuiklyThreadSettingsAction(id = "archive", enabled = true),
                KuiklyThreadSettingsAction(id = "delete", enabled = false)
            ).normalizedThreadSettingsChannelActions()

        assertEquals(listOf("archive"), actions.map { it.id })
    }

    @Test
    fun threadSettingsKnownActionsUseCanonicalUiModel() {
        val actions =
            listOf(
                KuiklyThreadSettingsAction(id = "visibility"),
                KuiklyThreadSettingsAction(id = "archive"),
                KuiklyThreadSettingsAction(id = "delete")
            ).normalizedThreadSettingsChannelActions()

        assertEquals(
            listOf("visibility", "archive", "delete"),
            actions.map { it.id }
        )
        assertEquals(
            listOf("Make Private", "Archive Channel", "Delete Channel"),
            actions.map { it.title }
        )
        assertEquals(
            listOf(
                "Restrict this channel to selected members",
                "Freeze writes and hide from sidebars",
                "Permanently remove this channel"
            ),
            actions.map { it.subtitle }
        )
        assertEquals(
            listOf(SlockLucideIconName.Lock, SlockLucideIconName.Archive, SlockLucideIconName.Trash),
            actions.map { it.icon }
        )
        assertEquals(true, actions.all { it.destructive })
    }

    @Test
    fun launchSettingsActionsAreIgnoredAtBoundary() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-settings-boundary",
                      "sourceThreadId": "channel-1",
                      "settingsActions": [
                        {
                          "id": "visibility",
                          "enabled": false,
                          "title": "Wrong label",
                          "subtitle": "Wrong subtitle",
                          "icon": "trash",
                          "destructive": false
                        }
                      ]
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals(emptyList(), payload.settingsActions)
        assertEquals(
            emptyList(),
            payload.settingsActions.normalizedThreadSettingsChannelActions(useDefaultWhenEmpty = false)
        )
    }

    @Test
    fun buildsSharedShellThreadRoutePayloadFromConversation() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    kind = ConversationKind.Channel,
                    name = "general",
                    subtitle = "Team channel",
                    joined = true,
                    memberCount = 7,
                    permissions = ConversationPermissions(canSend = true)
                ),
                AppShellThreadTimeContext(
                    localOffsetMinutes = 480,
                    todayEpochDay = 20593
                ),
                alignOwnMessagesRight = true
            )

        assertEquals("shared-shell-thread-channel-1", payload.instanceId)
        assertEquals("Channel", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals("loading", payload.status)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals(true, payload.canSend)
        assertEquals(7, payload.membersCount)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(20593, payload.todayEpochDay)
        assertEquals(true, payload.showReplyActions)
        assertEquals(true, payload.alignOwnMessagesRight)
        assertEquals(true, payload.showSettingsAction)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(
            listOf("visibility", "archive", "delete"),
            payload.settingsActions.map { it.id }
        )
        val composer = payload.resolveThreadComposer()
        assertEquals("Message #Channel", composer.placeholder)
        assertEquals(KuiklyThreadSurfaceKind.Channel, composer.surfaceKind)
        assertEquals("channel-1", composer.streamChannelId)
        assertEquals("idle", composer.composerMode)
    }

    @Test
    fun sharedShellJointChannelRouteDisablesTaskSurfaces() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("joint-1"),
                    channelId = ChannelId("joint-1"),
                    channelType = "joint",
                    kind = ConversationKind.Channel,
                    name = "partner",
                    subtitle = "Joint channel",
                    joined = true,
                    memberCount = 3,
                    permissions = ConversationPermissions(canSend = true, canCreateTask = true)
                )
            )

        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(false, payload.showTaskOpenActions)
        assertEquals(false, payload.showTaskStatusActions)
        assertEquals(false, payload.composer.showAsTaskToggle)
        assertEquals(false, payload.resolveThreadComposer().showAsTaskToggle)
        assertEquals(false, payload.supportsChannelTasks())
        assertEquals(false, shouldStartThreadTasksJoin(payload.copy(status = "content"), null, ChannelId("joint-1")))
    }

    @Test
    fun sharedShellReplyThreadWithJointParentDisablesTaskSurfaces() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = threadIdentity(ChannelId("joint-1"), MessageId("message-1")),
                    channelId = ChannelId("joint-1"),
                    kind = ConversationKind.Thread,
                    name = "Reply thread",
                    joined = true,
                    permissions = ConversationPermissions(canSend = true, canCreateTask = true),
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("joint-1"),
                            parentMessageId = MessageId("message-1"),
                            streamChannelId = ChannelId("thread-message-1"),
                            parentChannelKind = "joint"
                        )
                )
            )

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("joint", payload.parentChannelKind)
        assertEquals(false, payload.showTaskOpenActions)
        assertEquals(false, payload.showTaskStatusActions)
        assertEquals(false, payload.composer.showAsTaskToggle)
        assertEquals(false, payload.resolveThreadComposer().showAsTaskToggle)
        assertEquals(false, payload.supportsChannelTasks())
    }

    @Test
    fun topLevelThreadMessagesCanConvertToTasksWhenTaskActionsAreEnabled() {
        val payload =
            KuiklyThreadScreenPayload(
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                showTaskStatusActions = true
            )

        assertTrue(payload.canConvertMessagesToTasks())
        assertTrue(
            KuiklyThreadMessageItem(
                id = "msg-1",
                authorId = "u1",
                authorName = "Artin",
                body = "ship it"
            ).canConvertToTask()
        )
    }

    @Test
    fun replyThreadAndIneligibleMessagesCannotConvertToTasks() {
        val replyPayload =
            KuiklyThreadScreenPayload(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                parentMessageId = "parent-1",
                originParentMessageId = "parent-1",
                showTaskStatusActions = true
            )
        val disabledPayload =
            KuiklyThreadScreenPayload(
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                showTaskStatusActions = false
            )

        assertFalse(replyPayload.canConvertMessagesToTasks())
        assertFalse(disabledPayload.canConvertMessagesToTasks())
        assertFalse(
            KuiklyThreadMessageItem(
                id = "msg-task-id",
                authorId = "u1",
                authorName = "Artin",
                taskId = "task-1"
            ).canConvertToTask()
        )
        assertFalse(
            KuiklyThreadMessageItem(
                id = "msg-task-number",
                authorId = "u1",
                authorName = "Artin",
                taskNumber = 12
            ).canConvertToTask()
        )
        assertFalse(
            KuiklyThreadMessageItem(
                id = "msg-system",
                authorId = "system",
                authorName = "System",
                messageType = "System"
            ).canConvertToTask()
        )
        assertFalse(
            KuiklyThreadMessageItem(
                id = "msg-local",
                authorId = "u1",
                authorName = "Artin",
                deliveryState = ThreadMessageDeliverySending,
                pendingSendContext =
                    KuiklyThreadPendingSendContext(
                        channelId = "channel-1",
                        body = "pending"
                    )
            ).canConvertToTask()
        )
    }

    @Test
    fun sharedShellDirectMessageRouteKeepsDmSurfaceForComposerProps() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("dm-agent-1"),
                    channelId = ChannelId("dm-agent-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "Product-UX-Designer",
                    subtitle = "online",
                    joined = true,
                    permissions = ConversationPermissions(canSend = true)
                )
            )

        val headerDisplay =
            payload.threadHeaderDisplayModel(
                payload.conversationHeaderEntryFrom(
                    ConversationSummary(
                        threadId = ThreadId("dm-agent-1"),
                        channelId = ChannelId("dm-agent-1"),
                        kind = ConversationKind.DirectMessage,
                        name = "Product-UX-Designer",
                        subtitle = "online",
                        joined = true,
                        permissions = ConversationPermissions(canSend = true)
                    )
                )
            )

        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("dm-agent-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(null, payload.dmPeerName)
        assertEquals("Product-UX-Designer", headerDisplay.title)
        val composer = payload.resolveThreadComposer(displayName = headerDisplay.title)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, composer.surfaceKind)
        assertEquals("dm-agent-1", composer.streamChannelId)
        assertEquals("Message @Product-UX-Designer", composer.placeholder)
        assertEquals("idle", composer.composerMode)
    }

    @Test
    fun directMessageReplyThreadLaunchKeepsParentSurfaceKind() {
        val dmPayload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("dm-agent-1"),
                    channelId = ChannelId("dm-agent-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "Product-UX-Designer",
                    permissions = ConversationPermissions(canSend = true)
                )
            )
        val parentMessage =
            KuiklyThreadMessageItem(
                id = "dm-parent-1",
                authorId = "agent-1",
                authorName = "Product-UX-Designer",
                body = "Can you check this?",
                threadChannelId = "thread-dm-parent-1"
            )

        val replyPayload = dmPayload.replyThreadLoadingPayload(parentMessage)
        val launchPayload = replyPayload.toWireThreadLaunchPayload()
        val parsedPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(WireThreadLaunchPayload.serializer(), launchPayload),
                instanceId = "fallback"
            )
        val parentPayload = parsedPayload.parentMessageContextLoadingPayload("dm-parent-1")

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, parsedPayload.surfaceKind)
        assertEquals("previous", replyPayload.parentNavigationHint)
        assertEquals("previous", launchPayload.parentNavigationHint)
        assertEquals("previous", parsedPayload.parentNavigationHint)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, launchPayload.parentSurfaceKind)
        assertEquals("dm-agent-1", parsedPayload.parentChannelId)
        assertEquals("dm-parent-1", parsedPayload.parentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, parsedPayload.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, parentPayload.surfaceKind)
        assertEquals("dm-agent-1", parentPayload.sourceThreadId)
        assertEquals("dm-agent-1", parentPayload.streamChannelId)
        assertEquals("Direct Message", parentPayload.title)
    }

    @Test
    fun jointReplyThreadLaunchKeepsParentChannelKind() {
        val parentMessage =
            KuiklyThreadMessageItem(
                id = "joint-parent-1",
                authorId = "u1",
                authorName = "Artin",
                body = "Can you check this?",
                threadChannelId = "thread-joint-parent-1"
            )
        val jointPayload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-joint-1",
                title = "partner",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "joint",
                sourceThreadId = "joint-channel-1",
                streamChannelId = "joint-channel-1",
                status = "content",
                messages = listOf(parentMessage)
            )

        val replyPayload = jointPayload.replyThreadLoadingPayload(parentMessage)
        val launchPayload = replyPayload.toWireThreadLaunchPayload()
        val parsedPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(WireThreadLaunchPayload.serializer(), launchPayload),
                instanceId = "fallback"
            )
        val parentPayload = parsedPayload.parentMessageContextLoadingPayload("joint-parent-1")

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, parsedPayload.surfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, launchPayload.parentSurfaceKind)
        assertEquals("joint", replyPayload.parentChannelKind)
        assertEquals("joint", launchPayload.parentChannelKind)
        assertEquals("joint", parsedPayload.parentChannelKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, parentPayload.surfaceKind)
        assertEquals("joint-channel-1", parentPayload.sourceThreadId)
        assertEquals("joint", parentPayload.channelKind)
        assertEquals(SlockLucideIconName.GitBranch, parentPayload.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun jointReplyThreadLaunchDisablesInitialTaskSurfaces() {
        val initialPayload =
            WireThreadLaunchPayload(
                instanceId = "thread-route",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "joint-1",
                parentMessageId = "parent-message-1",
                parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
                parentChannelKind = "joint",
                originParentMessageId = "parent-message-1"
            ).toInitialPayload("fallback")

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals("joint", initialPayload.parentChannelKind)
        assertEquals(false, initialPayload.showTaskOpenActions)
        assertEquals(false, initialPayload.showTaskStatusActions)
        assertEquals(false, initialPayload.composer.showAsTaskToggle)
        assertEquals(false, initialPayload.resolveThreadComposer().showAsTaskToggle)
        assertEquals(false, initialPayload.supportsChannelTasks())
    }

    @Test
    fun sharedShellReplyThreadRouteUsesConversationChannelSubtitle() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = threadIdentity(ChannelId("channel-1"), MessageId("message-1")),
                    channelId = ChannelId("channel-1"),
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId("message-1"),
                            streamChannelId = ChannelId("thread-1")
                        ),
                    kind = ConversationKind.Thread,
                    name = "general",
                    subtitle = "Parent message preview",
                    joined = true,
                    memberCount = 7,
                    permissions = ConversationPermissions(canSend = true)
                )
            )

        assertEquals("", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(null, payload.originMessage)
        assertEquals(null, payload.originPreview)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.parentSurfaceKind)
        assertEquals(null, payload.parentNavigationHint)
        assertEquals(null, payload.toWireThreadLaunchPayload().parentNavigationHint)
        assertEquals("missing", payload.originStatus)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
    }

    @Test
    fun threadHeaderDisplayTitleDropsLeadingChannelHash() {
        assertEquals("首页专修", threadHeaderDisplayTitle("#首页专修"))
        assertEquals("design", threadHeaderDisplayTitle("design"))
        assertEquals("Channel", threadHeaderDisplayTitle("#"))
    }

    @Test
    fun threadHeaderDisplayTitleDoesNotRenderIdentityFallbacks() {
        assertEquals("Channel", threadHeaderDisplayTitle("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals("Channel", threadHeaderDisplayTitle("deadbeef"))
        assertEquals("Direct Message", threadHeaderDisplayTitle("123e4567-e89b-12d3-a456-426614174000", KuiklyThreadSurfaceKind.DirectMessage))
    }

    @Test
    fun canonicalConversationContextDoesNotRenderIdentityAsTitle() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val context =
            SharedThreadHeaderContextStore.conversationContext(
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                conversation =
                    ConversationSummary(
                        threadId = ThreadId(uuid),
                        channelId = ChannelId(uuid),
                        kind = ConversationKind.Channel,
                        name = uuid
                    ),
                originMessage = null,
                parentMessageId = null
            )

        assertEquals("", context.title)
        assertEquals("Channel", threadHeaderDisplayTitle(context.title, KuiklyThreadSurfaceKind.Channel))
    }

    @Test
    fun canonicalConversationContextUsesNormalizedChannelTitle() {
        val context =
            SharedThreadHeaderContextStore.conversationContext(
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "#对话流专修"
                    ),
                originMessage = null,
                parentMessageId = null
            )

        assertEquals("对话流专修", context.title)
    }

    @Test
    fun appShellActivityThreadItemOpensReplyThreadChrome() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                parentMessageSenderId = "user-1",
                parentMessageSenderKind = MemberKind.Human,
                latestActivityMessageId = MessageId("reply-message-1"),
                latestActivityPreview = "Latest reply"
            ).toHomeMessageOpenRequest()
        val conversation = request.conversation

        val payload =
            buildAppShellThreadPayload(
                conversation = conversation,
                anchorMessageId = request.anchorMessageId,
                originFallback = request.originFallback
            )

        assertEquals("reply-message-1", request.anchorMessageId)
        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), conversation.threadId)
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals("", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
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
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
    }

    @Test
    fun appShellThreadOriginFallbackIsCarriedOutsideConversationSummary() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                parentMessageSenderId = "user-1",
                parentMessageSenderKind = MemberKind.Human,
                latestActivityMessageId = MessageId("reply-message-1")
            ).toHomeMessageOpenRequest()

        val payload =
            buildAppShellThreadPayload(
                conversation = request.conversation,
                anchorMessageId = request.anchorMessageId,
                originFallback = request.originFallback
            )

        assertEquals("Parent message preview", request.originFallback?.preview)
        assertEquals("user-1", request.originFallback?.senderId)
        assertEquals(MemberKind.Human, request.originFallback?.senderKind)
        assertEquals("channel-1:message-1", request.conversation.resolveThreadRouteIdentity().parentKey)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("missing", payload.originStatus)
        assertEquals("Parent message preview", payload.originPreview)
    }

    @Test
    fun appShellThreadOriginFallbackMustMatchParentIdentity() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                latestActivityMessageId = MessageId("reply-message-1")
            ).toHomeMessageOpenRequest()
        val wrongFallback =
            ThreadLaunchOriginFallback(
                parentChannelId = "channel-2",
                parentMessageId = "message-1",
                preview = "Wrong parent preview",
                senderId = null,
                senderKind = null
            )

        val payload =
            buildAppShellThreadPayload(
                conversation = request.conversation,
                anchorMessageId = request.anchorMessageId,
                originFallback = wrongFallback
            )

        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("missing", payload.originStatus)
        assertEquals(null, payload.originPreview)
    }

    @Test
    fun contentSliceUsesPatchHeaderContextInsteadOfSeededReplyOrigin() {
        val origin =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "user-1",
                authorName = "Artin",
                body = "Parent message preview"
            )
        val reply =
            KuiklyThreadMessageItem(
                id = "reply-message-1",
                authorId = "agent-1",
                authorName = "Android-Developer-4",
                body = "Latest reply"
            )
        val current =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1-message-1",
                title = ThreadReplyPageTitle,
                subtitle = "#seeded",
                status = "loading",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                originParentMessageId = "message-1",
                originMessage = origin,
                originPreview = "Parent message preview",
                originStatus = "loaded"
            )
        val patch =
            current.copy(
                title = ThreadReplyPageTitle,
                subtitle = "#general",
                status = "content",
                messages = listOf(reply),
                originMessage = null,
                originPreview = null,
                originParentMessageId = null,
                originStatus = "none"
            )

        val payload = current.withContentSliceFrom(patch)

        assertEquals("content", payload.status)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#seeded", payload.subtitle)
        assertEquals(listOf("reply-message-1"), payload.messages.map { it.id })
        assertEquals(null, payload.originParentMessageId)
        assertEquals("none", payload.originStatus)
        assertEquals(null, payload.originMessage)
        assertEquals(null, payload.originPreview)
    }

    @Test
    fun contentSliceDoesNotReplaceThreadChromeWhenPatchChangesRoute() {
        val current =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-reply-stream-1",
                title = ThreadReplyPageTitle,
                subtitle = "#general",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply-stream-1",
                streamChannelId = "reply-stream-1",
                parentChannelId = "channel-1",
                parentMessageId = "parent-message-1",
                originParentMessageId = "parent-message-1",
                showModeTabs = false,
                showReplyActions = false,
                showMembersAction = false,
                showSettingsAction = false,
                showOpenInChannelAction = true,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "reply-1",
                            authorId = "agent-1",
                            authorName = "Agent",
                            body = "reply"
                        )
                    ),
                originStatus = "loaded",
                originMessage =
                    KuiklyThreadMessageItem(
                        id = "parent-message-1",
                        authorId = "user-1",
                        authorName = "Artin",
                        body = "parent"
                    )
            )
        val patch =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1",
                title = "general",
                subtitle = null,
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "public",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                parentChannelId = null,
                parentMessageId = null,
                originParentMessageId = null,
                showModeTabs = true,
                showReplyActions = true,
                showMembersAction = true,
                showSettingsAction = true,
                showOpenInChannelAction = false,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "channel-message-1",
                            authorId = "user-1",
                            authorName = "Artin",
                            body = "channel message"
                        )
                    ),
                originStatus = "none",
                originMessage = null
            )

        val payload = current.withContentSliceFrom(patch)

        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel", payload.channelKind)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(null, payload.originParentMessageId)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showReplyActions)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals("none", payload.originStatus)
        assertEquals(null, payload.originMessage)
        assertEquals(listOf("channel-message-1"), payload.messages.map { it.id })
    }

    @Test
    fun refreshPatchPreservesLoadedOriginWhenNetworkSnapshotOnlyProvesSameParent() {
        val origin =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "user-1",
                authorName = "Artin",
                body = "Original task body"
            )
        val previous =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-thread-channel-1",
                title = ThreadReplyPageTitle,
                subtitle = "#general",
                status = "content",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "channel-1",
                parentMessageId = "message-1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                originParentMessageId = "message-1",
                originMessage = origin,
                originStatus = "loaded",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
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
                        KuiklyThreadMessageItem(
                            id = "reply-message-1",
                            authorId = "agent-1",
                            authorName = "Android-Developer-4",
                            body = "Reply body"
                        ),
                        KuiklyThreadMessageItem(
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
    fun refreshPatchPreservesResolvedLinkedTaskForVisibleTaskMessage() {
        val previous =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                status = "content",
                sourceThreadId = "thread-channel-1",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "message-240",
                            authorId = "user-artin",
                            authorName = "Artin",
                            body = "ship task",
                            taskId = "task-240",
                            taskNumber = 240,
                            taskStatus = "InReview",
                            taskAssigneeId = "agent-android",
                            taskAssigneeName = "Android Developer"
                        )
                    ),
                tasks =
                    listOf(
                        KuiklyThreadTaskItem(
                            id = "task-240",
                            title = "ship task",
                            status = "InReview",
                            taskNumber = 240,
                            messageId = "message-240",
                            assigneeId = "agent-android",
                            assigneeName = "Android Developer"
                        )
                    )
            )
        val refreshPatch =
            previous.copy(
                messages =
                    listOf(
                        previous.messages.first().copy(
                            taskStatus = null,
                            taskAssigneeName = "agent-android"
                        )
                    ),
                tasks = emptyList()
            )

        val payload = refreshPatch.preserveStableTimelineFrom(previous)

        assertEquals(listOf("task-240"), payload.tasks.map { it.id })
        val retainedTask = payload.tasks.single()
        val linkedTasks =
            listOf(
                TaskSummary(
                    id = TaskId(retainedTask.id),
                    title = retainedTask.title,
                    status = TaskStatus.InReview,
                    taskNumber = retainedTask.taskNumber,
                    messageId = MessageId(retainedTask.messageId ?: ""),
                    assignee =
                        MemberProfile(
                            id = MemberId(retainedTask.assigneeId ?: ""),
                            displayName = retainedTask.assigneeName ?: "",
                            handle = retainedTask.assigneeName ?: "",
                            kind = MemberKind.Agent
                        ),
                    updatedAtLabel = retainedTask.updatedAtLabel
                )
            ).threadLinkedTasksByMessageId()
        val linkedMessage = payload.messages.first().withLinkedTask(linkedTasks["message-240"])
        assertEquals("InReview", linkedMessage.taskStatus)
        assertEquals("Android Developer", linkedMessage.taskAssigneeName)
        assertEquals("#240 @Android Developer", threadMessageTaskMetaLabel(linkedMessage, linkedMessage.taskAssigneeName))
    }

    @Test
    fun refreshPatchDropsResolvedLinkedTaskWhenVisibleMessagesNoLongerReferenceIt() {
        val previous =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                status = "content",
                sourceThreadId = "thread-channel-1",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "message-240",
                            authorId = "user-artin",
                            authorName = "Artin",
                            body = "ship task",
                            taskId = "task-240",
                            taskNumber = 240
                        )
                    ),
                tasks =
                    listOf(
                        KuiklyThreadTaskItem(
                            id = "task-240",
                            title = "ship task",
                            status = "InReview",
                            taskNumber = 240,
                            messageId = "message-240",
                            assigneeId = "agent-android",
                            assigneeName = "Android Developer"
                        )
                    )
            )
        val refreshPatch =
            previous.copy(
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "message-241",
                            authorId = "user-artin",
                            authorName = "Artin",
                            body = "plain message"
                        )
                    ),
                tasks = emptyList()
            )

        val payload = refreshPatch.preserveStableTimelineFrom(previous)

        assertEquals(emptyList(), payload.tasks)
    }

    @Test
    fun appShellActivityThreadItemShowsMissingOriginFallbackWhenPreviewIsUnavailable() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("message-1"),
                latestActivityPreview = "Latest reply"
            ).toHomeMessageOpenRequest()

        val payload = buildAppShellThreadPayload(request.conversation, anchorMessageId = request.anchorMessageId)

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("missing", payload.originStatus)
        assertEquals(null, payload.originMessage)
        assertEquals(null, payload.originPreview)
    }

    @Test
    fun appShellActivityThreadItemKeepsStreamUnresolvedWhenThreadStreamIsMissing() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-compat"),
                channelName = "general",
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                latestActivityPreview = "Latest reply"
            ).toHomeMessageOpenRequest()
        val conversation = request.conversation

        val payload = buildAppShellThreadPayload(conversation, anchorMessageId = request.anchorMessageId)

        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), conversation.threadId)
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals("", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(null, payload.sourceThreadId)
        assertEquals(null, payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals(null, conversation.thread?.streamChannelId)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
    }

    @Test
    fun appShellActivityChannelItemKeepsChannelChrome() {
        val request =
            InboxItem(
                key = "channel:general",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                lastMessagePreview = "Latest channel message",
                lastMessageId = MessageId("latest-channel-message"),
                unreadCount = 2
            ).toHomeMessageOpenRequest()
        val conversation = request.conversation

        val payload = buildAppShellThreadPayload(conversation, anchorMessageId = request.anchorMessageId)

        assertEquals(ThreadId("channel-1"), conversation.threadId)
        assertEquals(ConversationKind.Channel, conversation.kind)
        assertEquals("", payload.title)
        assertEquals("Channel", threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(null, payload.originParentMessageId)
        assertEquals("latest-channel-message", payload.focusedMessageId)
        assertEquals(true, payload.showModeTabs)
        assertEquals(true, payload.showMembersAction)
        assertEquals(true, payload.showSettingsAction)
    }

    @Test
    fun sharedShellThreadRouteOmitsSubtitleWhenChannelDescriptionIsMissing() {
        val payload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    kind = ConversationKind.Channel,
                    name = "general",
                    subtitle = null,
                    joined = true,
                    memberCount = 7,
                    permissions = ConversationPermissions(canSend = true)
                )
            )

        assertEquals("", payload.title)
        assertEquals("Channel", threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun standaloneConversationThreadLaunchCarriesOnlyIdentityAndIntent() {
        val launch =
            buildStandaloneConversationThreadLaunchPayload(
                ConversationSummary(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    channelType = "private",
                    kind = ConversationKind.Channel,
                    name = "release-room",
                    subtitle = "Product state must not be in PagerData",
                    joined = false,
                    memberCount = 12,
                    permissions =
                        ConversationPermissions(
                            canRead = true,
                            canSend = false,
                            canCreateTask = false
                        )
                ),
                AppShellThreadTimeContext(localOffsetMinutes = 480, todayEpochDay = 20593),
                alignOwnMessagesRight = true,
                anchorMessageId = "message-focus"
            )

        assertEquals("shared-shell-thread-channel-1", launch.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, launch.surfaceKind)
        assertEquals(null, launch.threadId)
        assertEquals("channel-1", launch.sourceThreadId)
        assertEquals("channel-1", launch.streamChannelId)
        assertEquals(null, launch.parentChannelId)
        assertEquals(null, launch.parentMessageId)
        assertEquals("message-focus", launch.focusedMessageId)
        assertEquals(480, launch.localOffsetMinutes)
        assertEquals(20593, launch.todayEpochDay)
        assertEquals(true, launch.alignOwnMessagesRight)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals("shared-shell-thread-channel-1", initialPayload.instanceId)
        assertEquals("loading", initialPayload.status)
        assertEquals("Channel", initialPayload.title)
        assertEquals(KuiklyThreadSurfaceKind.Channel, initialPayload.surfaceKind)
        assertEquals("channel-1", initialPayload.sourceThreadId)
        assertEquals("message-focus", initialPayload.focusedMessageId)
        assertEquals(emptyList<KuiklyThreadMessageItem>(), initialPayload.messages)
        assertEquals(emptyList<KuiklyThreadTaskItem>(), initialPayload.tasks)
        assertEquals(emptyList(), initialPayload.settingsActions)
        assertEquals(0, initialPayload.membersCount)
        assertEquals(true, initialPayload.joined)
        assertEquals(true, initialPayload.canSend)
        assertEquals(null, initialPayload.dmPeerName)
        assertEquals(null, initialPayload.dmPeerAvatarUrl)

        val composer = initialPayload.resolveThreadComposer()
        assertEquals(KuiklyThreadSurfaceKind.Channel, composer.surfaceKind)
        assertEquals("channel-1", composer.streamChannelId)
        assertEquals(null, composer.mentionChannelId)
        assertEquals(null, composer.parentMessageId)
        assertEquals(true, composer.showAsTaskToggle)
    }

    @Test
    fun standaloneChannelLaunchIgnoresReplyThreadMetadataOnSummary() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                channelType = "channel",
                kind = ConversationKind.Channel,
                name = "general",
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("parent-1"),
                        streamChannelId = ChannelId("reply-thread-1")
                    )
            )
        val launch =
            buildStandaloneConversationThreadLaunchPayload(
                conversation,
                timeContext = null
            )
        val routeIdentity = conversation.resolveThreadRouteIdentity()

        assertEquals(KuiklyThreadSurfaceKind.Channel, routeIdentity.surfaceKind)
        assertEquals("channel-1", routeIdentity.timelineChannelId)
        assertEquals("channel:channel-1", routeIdentity.instanceKey)
        assertEquals(KuiklyThreadSurfaceKind.Channel, launch.surfaceKind)
        assertEquals(null, launch.threadId)
        assertEquals("channel-1", launch.sourceThreadId)
        assertEquals("channel-1", launch.streamChannelId)
        assertEquals(null, launch.parentChannelId)
        assertEquals(null, launch.parentMessageId)
        assertEquals(null, launch.originParentMessageId)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals(KuiklyThreadSurfaceKind.Channel, initialPayload.surfaceKind)
        assertEquals("channel-1", initialPayload.sourceThreadId)
        assertEquals("channel-1", initialPayload.streamChannelId)
        assertEquals(null, initialPayload.parentChannelId)
        assertEquals(null, initialPayload.parentMessageId)
        assertEquals(null, initialPayload.originParentMessageId)

        val appShellPayload = buildAppShellThreadPayload(conversation)
        val appShellComposer = appShellPayload.resolveThreadComposer()
        assertEquals(KuiklyThreadSurfaceKind.Channel, appShellPayload.surfaceKind)
        assertEquals("channel-1", appShellPayload.sourceThreadId)
        assertEquals("channel-1", appShellPayload.streamChannelId)
        assertEquals(null, appShellPayload.parentChannelId)
        assertEquals(null, appShellPayload.parentMessageId)
        assertEquals(null, appShellPayload.originParentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, appShellComposer.surfaceKind)
        assertEquals("channel-1", appShellComposer.streamChannelId)
        assertEquals(null, appShellComposer.mentionChannelId)
        assertEquals(null, appShellComposer.parentMessageId)
    }

    @Test
    fun standaloneUnresolvedReplyLaunchCarriesOnlyParentPairUntilResolved() {
        val launch =
            buildStandaloneConversationThreadLaunchPayload(
                ConversationSummary(
                    threadId = ThreadId("thread-summary-id"),
                    channelId = null,
                    channelType = "channel",
                    kind = ConversationKind.Thread,
                    name = "parent reply",
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId("m1"),
                            streamChannelId = null
                        )
                ),
                timeContext = null
            )

        assertEquals("shared-shell-thread-thread-summary-id", launch.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals(null, launch.threadId)
        assertEquals(null, launch.sourceThreadId)
        assertEquals(null, launch.streamChannelId)
        assertEquals("channel-1", launch.parentChannelId)
        assertEquals("m1", launch.parentMessageId)
        assertEquals("m1", launch.originParentMessageId)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals(null, initialPayload.sourceThreadId)
        assertEquals(null, initialPayload.streamChannelId)
        assertEquals("channel-1", initialPayload.parentChannelId)
        assertEquals("m1", initialPayload.parentMessageId)
        assertEquals(null, initialPayload.resolveThreadRouteIdentity().timelineKey)
    }

    @Test
    fun standaloneThreadRoutePageDataCarriesHostPageName() {
        val launch =
            WireThreadLaunchPayload(
                instanceId = "shared-shell-thread-channel-1",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                selectedTab = "chat",
            )

        val pageData = threadLaunchPageData(launch)
        val parsed =
            parseThreadScreenPayload(
                payload = pageData.optString("payload"),
                instanceId = pageData.optString("instanceId")
            )

        assertEquals(THREAD_PAGE, pageData.optString("pageName"))
        assertEquals("shared-shell-thread-channel-1", pageData.optString("instanceId"))
        assertEquals("shared-shell-thread-channel-1", parsed.instanceId)
        assertEquals("channel-1", parsed.sourceThreadId)
        assertEquals("channel-1", parsed.streamChannelId)
    }

    @Test
    fun standaloneThreadRoutePageDataPayloadIsIdentityOnly() {
        val launch =
            WireThreadLaunchPayload(
                instanceId = "shared-shell-thread-channel-1",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                selectedTab = "chat"
            )

        val encodedPayload = threadLaunchPageData(launch).optString("payload")

        listOf(
            "\"messages\"",
            "\"tasks\"",
            "\"members\"",
            "\"memberCandidates\"",
            "\"filesPayload\"",
            "\"settingsActions\""
        ).forEach { forbidden ->
            assertFalse(encodedPayload.contains(forbidden), "$forbidden must stay outside compose_thread pageData")
        }
    }

    @Test
    fun standaloneActivityThreadLaunchShowsOpenInConversationAction() {
        val request =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-channel-1"),
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                latestActivityMessageId = MessageId("reply-message-1"),
                latestActivityPreview = "Latest reply"
            ).toHomeMessageOpenRequest()

        val launch =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = request.conversation,
                timeContext = AppShellThreadTimeContext(localOffsetMinutes = 480),
                anchorMessageId = request.anchorMessageId
            )
        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("thread-channel-1", launch.threadId)
        assertEquals("thread-channel-1", launch.streamChannelId)
        assertEquals("channel-1", launch.parentChannelId)
        assertEquals("message-1", launch.parentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, launch.parentSurfaceKind)
        assertEquals("message-1", launch.originParentMessageId)
        assertEquals("reply-message-1", launch.focusedMessageId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, initialPayload.parentSurfaceKind)
        assertEquals("message-1", initialPayload.originParentMessageId)
        assertEquals("reply-message-1", initialPayload.focusedMessageId)
        assertEquals(false, initialPayload.showModeTabs)
        assertEquals(false, initialPayload.showMembersAction)
        assertEquals(false, initialPayload.showSettingsAction)
        assertEquals(true, initialPayload.showOpenInChannelAction)
    }

    @Test
    fun standaloneSavedDmThreadLaunchKeepsDirectMessageParentSurface() {
        val request =
            InboxItem(
                key = "saved:dm-reply-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-dm-parent-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-dm-parent-1"),
                parentChannelId = ChannelId("dm-agent-1"),
                parentChannelName = "Product-UX-Designer",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("dm-parent-1"),
                parentMessagePreview = "Can you check this?",
                latestActivityMessageId = MessageId("dm-reply-1"),
                latestActivityPreview = "Sure"
            ).toHomeMessageOpenRequest()

        val launch =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = request.conversation,
                timeContext = AppShellThreadTimeContext(localOffsetMinutes = 480),
                anchorMessageId = request.anchorMessageId
            )
        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )
        val parentPayload = initialPayload.parentMessageContextLoadingPayload("dm-parent-1")

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("thread-dm-parent-1", launch.threadId)
        assertEquals("dm-agent-1", launch.parentChannelId)
        assertEquals("dm-parent-1", launch.parentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, request.conversation.thread?.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, launch.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, initialPayload.parentSurfaceKind)
        assertEquals(true, initialPayload.showOpenInChannelAction)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, parentPayload.surfaceKind)
        assertEquals("dm-agent-1", parentPayload.sourceThreadId)
        assertEquals("dm-agent-1", parentPayload.streamChannelId)
    }

    @Test
    fun screenPayloadWireLaunchDropsDisplayAndProductState() {
        val launch =
            KuiklyThreadScreenPayload(
                instanceId = "display-route",
                title = "Display title",
                subtitle = "Display subtitle",
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1",
                originParentMessageId = "parent-message-1",
                focusedMessageId = "reply-2",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "reply-1",
                            authorId = "agent-1",
                            authorName = "Android Dev",
                            timestampLabel = "now",
                            body = "This must not enter route payload"
                        )
                    ),
                joined = false,
                canSend = false,
                membersCount = 7,
                dmPeerName = "Display peer",
                dmPeerAvatarUrl = "https://example.com/avatar.png"
            ).toWireThreadLaunchPayload()

        assertEquals("display-route", launch.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("thread-channel-1", launch.threadId)
        assertEquals("thread-channel-1", launch.sourceThreadId)
        assertEquals("thread-channel-1", launch.streamChannelId)
        assertEquals("parent-channel-1", launch.parentChannelId)
        assertEquals("parent-message-1", launch.parentMessageId)
        assertEquals("reply-2", launch.focusedMessageId)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals("loading", initialPayload.status)
        assertEquals(ThreadReplyPageTitle, initialPayload.title)
        assertEquals(emptyList<KuiklyThreadMessageItem>(), initialPayload.messages)
        assertEquals(emptyList<KuiklyThreadTaskItem>(), initialPayload.tasks)
        assertEquals(emptyList(), initialPayload.settingsActions)
        assertEquals(0, initialPayload.membersCount)
        assertEquals(true, initialPayload.joined)
        assertEquals(true, initialPayload.canSend)
        assertEquals(null, initialPayload.dmPeerName)
        assertEquals(null, initialPayload.dmPeerAvatarUrl)
    }

    @Test
    fun standaloneReplyThreadLaunchPreservesComposerRoutingIntent() {
        val launch =
            WireThreadLaunchPayload(
                instanceId = "thread-route",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                threadId = "thread-channel-1",
                sourceThreadId = "thread-channel-1",
                streamChannelId = "thread-channel-1",
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-message-1",
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
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals("thread-channel-1", initialPayload.sourceThreadId)
        assertEquals("thread-channel-1", initialPayload.streamChannelId)
        assertEquals("parent-channel-1", initialPayload.parentChannelId)
        assertEquals("parent-message-1", initialPayload.parentMessageId)
        assertEquals("parent-message-1", initialPayload.originParentMessageId)
        assertEquals("reply-2", initialPayload.focusedMessageId)
        assertEquals(false, initialPayload.showModeTabs)
        assertEquals(false, initialPayload.showOpenInChannelAction)

        val composer = initialPayload.resolveThreadComposer()
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, composer.surfaceKind)
        assertEquals("thread-channel-1", composer.streamChannelId)
        assertEquals("parent-channel-1", composer.mentionChannelId)
        assertEquals("parent-message-1", composer.parentMessageId)
        assertEquals("Reply in thread", composer.placeholder)
        assertEquals(false, composer.showAsTaskToggle)
    }

    @Test
    fun standaloneTaskThreadLaunchCarriesOnlyIdentityAndIntent() {
        val launch =
            buildStandaloneTaskThreadLaunchPayload(
                TaskSummary(
                    id = TaskId("task-1"),
                    title = "Fix slow route",
                    status = TaskStatus.InProgress,
                    taskNumber = 42,
                    messageId = MessageId("message-1"),
                    parentMessageId = MessageId("parent-message-1"),
                    channelId = ChannelId("parent-channel-1"),
                    threadId = ThreadId("thread-channel-1"),
                    assignee =
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Android Dev",
                            handle = "Android-Dev",
                            kind = MemberKind.Agent
                        ),
                    creator =
                        MemberProfile(
                            id = MemberId("user-1"),
                            displayName = "Artin",
                            handle = "artin",
                            kind = MemberKind.Human
                        ),
                    channelName = "bug triage",
                    updatedAtLabel = "now"
                ),
                AppShellThreadTimeContext(localOffsetMinutes = 480, todayEpochDay = 20593),
                alignOwnMessagesRight = true
            )

        assertEquals("task-thread-task-1", launch.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("thread-channel-1", launch.threadId)
        assertEquals("thread-channel-1", launch.sourceThreadId)
        assertEquals("thread-channel-1", launch.streamChannelId)
        assertEquals("parent-channel-1", launch.parentChannelId)
        assertEquals("parent-message-1", launch.parentMessageId)
        assertEquals("parent-message-1", launch.originParentMessageId)
        assertEquals("parent-message-1", launch.focusedMessageId)
        assertEquals(480, launch.localOffsetMinutes)
        assertEquals(20593, launch.todayEpochDay)
        assertEquals(true, launch.alignOwnMessagesRight)

        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )

        assertEquals("task-thread-task-1", initialPayload.instanceId)
        assertEquals("loading", initialPayload.status)
        assertEquals(ThreadReplyPageTitle, initialPayload.title)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, initialPayload.surfaceKind)
        assertEquals("thread-channel-1", initialPayload.sourceThreadId)
        assertEquals("parent-channel-1", initialPayload.parentChannelId)
        assertEquals("parent-message-1", initialPayload.parentMessageId)
        assertEquals("parent-message-1", initialPayload.focusedMessageId)
        assertEquals(emptyList<KuiklyThreadMessageItem>(), initialPayload.messages)
        assertEquals(emptyList<KuiklyThreadTaskItem>(), initialPayload.tasks)
        assertEquals(emptyList(), initialPayload.settingsActions)
        assertEquals(0, initialPayload.membersCount)
        assertEquals(null, initialPayload.dmPeerName)
        assertEquals(null, initialPayload.dmPeerAvatarUrl)
    }

    @Test
    fun buildsReplyThreadLoadingPayloadWithoutFetchingParentChannel() {
        val parent =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "u1",
                authorName = "Artin",
                body = "Parent message"
            )
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                messages = listOf(parent)
            ).replyThreadLoadingPayload(parent)

        assertEquals("shared-shell-thread-pending-message-1", payload.instanceId)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals("loading", payload.status)
        assertEquals(null, payload.sourceThreadId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        val composer = payload.resolveThreadComposer()
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, composer.surfaceKind)
        assertEquals("channel-1", composer.mentionChannelId)
        assertEquals("message-1", composer.parentMessageId)
        assertEquals("Reply in thread", composer.placeholder)
        assertEquals("loaded", payload.originStatus)
        assertEquals(false, payload.showModeTabs)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals("Loading...", payload.emptyTitle)
    }

    @Test
    fun replyThreadLoadingPayloadUsesKnownThreadChannelAsRouteIdentityOnly() {
        val parent =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "u1",
                authorName = "Artin",
                body = "Parent message",
                threadChannelId = "thread-message-1"
            )
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                messages = listOf(parent)
            ).replyThreadLoadingPayload(parent)

        assertEquals("shared-shell-thread-thread-message-1", payload.instanceId)
        assertEquals("thread-message-1", payload.sourceThreadId)
        assertEquals("thread-message-1", payload.streamChannelId)
        assertEquals("thread-message-1", payload.resolveThreadComposer().streamChannelId)
        assertEquals("Parent message", payload.originMessage?.body)

        val launch = payload.toWireThreadLaunchPayload()
        assertEquals("thread-message-1", launch.threadId)
        assertEquals("thread-message-1", launch.sourceThreadId)
        assertEquals("thread-message-1", launch.streamChannelId)
        assertEquals("channel-1", launch.parentChannelId)
        assertEquals("message-1", launch.parentMessageId)
        assertEquals("message-1", launch.originParentMessageId)
    }

    @Test
    fun replyThreadLoadingPayloadOmitsUnreadableChannelSubtitle() {
        val parent =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "u1",
                authorName = "Artin",
                body = "Parent message"
            )
        val payload =
            KuiklyThreadScreenPayload(
                title = "76c3c868",
                sourceThreadId = "channel-1",
                status = "content",
                messages = listOf(parent)
            ).replyThreadLoadingPayload(parent)

        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun parentMessageContextLoadingPayloadTargetsParentConversation() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-replies-1",
                title = ThreadReplyPageTitle,
                status = "content",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply-stream-1",
                streamChannelId = "reply-stream-1",
                parentChannelId = "channel-1",
                parentMessageId = "parent-message-1",
                originParentMessageId = "parent-message-1",
                showOpenInChannelAction = true
            ).parentMessageContextLoadingPayload("parent-message-1")

        assertEquals("shared-shell-thread-channel-1", payload.instanceId)
        assertEquals("loading", payload.status)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(null, payload.originParentMessageId)
        assertEquals("parent-message-1", payload.focusedMessageId)
        assertEquals(false, payload.showOpenInChannelAction)
        assertEquals(true, payload.showModeTabs)
        assertEquals("Loading...", payload.emptyTitle)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.resolveThreadComposer().surfaceKind)
    }

    @Test
    fun standaloneInboxJointReplyThreadCarriesParentChannelKind() {
        val request =
            InboxItem(
                key = "thread:thread-joint-parent-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-joint-parent-1"),
                channelName = "thread-stream-name",
                threadChannelId = ChannelId("thread-joint-parent-1"),
                parentChannelId = ChannelId("joint-channel-1"),
                parentChannelName = "Partner",
                parentChannelKind = ConversationKind.Joint,
                parentMessageId = MessageId("joint-parent-1"),
                parentMessagePreview = "Can you check this?",
                latestActivityMessageId = MessageId("joint-reply-1"),
                latestActivityPreview = "Sure"
            ).toHomeMessageOpenRequest()

        val launch =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = request.conversation,
                timeContext = AppShellThreadTimeContext(localOffsetMinutes = 480),
                anchorMessageId = request.anchorMessageId
            )
        val initialPayload =
            parseThreadScreenPayload(
                payload = launchJson.encodeToString(launch),
                instanceId = "fallback"
            )
        val parentPayload = initialPayload.parentMessageContextLoadingPayload("joint-parent-1")

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, launch.surfaceKind)
        assertEquals("joint", request.conversation.thread?.parentChannelKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, launch.parentSurfaceKind)
        assertEquals("joint", launch.parentChannelKind)
        assertEquals("joint", initialPayload.parentChannelKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, parentPayload.surfaceKind)
        assertEquals("joint", parentPayload.channelKind)
        assertEquals(SlockLucideIconName.GitBranch, parentPayload.channelKind.threadChannelHeaderIcon())
    }

    @Test
    fun buildsSharedThreadPayloadFromBridgeMessagesResponse() {
        val base =
            KuiklyThreadScreenPayload(
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
        assertEquals("#42", threadMessageTaskMetaLabel(payload.messages.first()))
        assertEquals("3 replies · 2 new", threadMessageRepliesMetaLabel(payload.messages.first().toThreadReplyMetadata()))
        assertEquals("👍", payload.messages.first().reactions.first().emoji)
        assertEquals(2, payload.messages.first().reactions.first().count)
        assertEquals(true, payload.messages.first().reactions.first().selected)
        assertEquals("log.txt", payload.messages.first().attachments.first().name)
        assertEquals("https://cdn.example/screen.png", payload.messages.first().attachments[1].thumbnailUrl)
    }

    @Test
    fun replyThreadMessagesPageRequiresResolvedThreadMembership() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "reply-thread-m1",
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
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
                                    id = "parent",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    content = "parent row",
                                    threadSummary = WireThreadSummary(threadChannelId = "thread-m1")
                                ),
                                WireMessage(
                                    id = "legacy-unscoped",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    content = "missing row identity"
                                ),
                                WireMessage(
                                    id = "reply-1",
                                    channelId = "thread-m1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    content = "reply row"
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = false
                    )
            )

        assertEquals("content", payload.status)
        assertEquals(listOf("reply row"), payload.messages.map { it.body })
    }

    @Test
    fun buildsSharedThreadPayloadPreservingActionCardMetadata() {
        val base =
            KuiklyThreadScreenPayload(
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
                                                )
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
    fun buildsSharedThreadPayloadPreservingChannelActionCardMetadata() {
        val base =
            KuiklyThreadScreenPayload(
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
                                    id = "m-channel-create",
                                    channelId = "channel-1",
                                    senderType = "agent",
                                    senderId = "agent-cindy",
                                    senderName = "Cindy",
                                    messageType = "chat",
                                    content = "Create channel action",
                                    createdAt = "2026-06-09T03:00:00Z",
                                    actionMetadata =
                                        WireActionCardMetadata(
                                            kind = "action-card",
                                            state = "prepared",
                                            action =
                                                WireActionCardAction(
                                                    type = "channel:create",
                                                    name = "design-review",
                                                    description = "Coordinate visual review.",
                                                    visibility = "private",
                                                    initialHumans = listOf("u2"),
                                                    initialAgents = listOf("agent-2")
                                                )
                                        )
                                ),
                                WireMessage(
                                    id = "m-channel-members",
                                    channelId = "channel-1",
                                    senderType = "agent",
                                    senderId = "agent-cindy",
                                    senderName = "Cindy",
                                    messageType = "chat",
                                    content = "Add members action",
                                    createdAt = "2026-06-09T03:01:00Z",
                                    actionMetadata =
                                        WireActionCardMetadata(
                                            kind = "action-card",
                                            state = "prepared",
                                            action =
                                                WireActionCardAction(
                                                    type = "channel:add_member",
                                                    channel = "channel-2",
                                                    humans = listOf("u3"),
                                                    agents = listOf("agent-3"),
                                                    draftHint = "Bring the review owners in."
                                                )
                                        )
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        val createCard = actionCardFromMessageMetadata(payload.messages.first().metadata)
        val addMembersCard = actionCardFromMessageMetadata(payload.messages.last().metadata)

        assertEquals("channel:create", createCard?.type)
        assertEquals("private", createCard?.visibility)
        assertEquals(listOf("u2"), createCard?.initialHumans)
        assertEquals(listOf("agent-2"), createCard?.initialAgents)
        assertEquals("channel:add_member", addMembersCard?.type)
        assertEquals("channel-2", addMembersCard?.channel)
        assertEquals(listOf("u3"), addMembersCard?.humans)
        assertEquals(listOf("agent-3"), addMembersCard?.agents)
        assertEquals("Bring the review owners in.", addMembersCard?.draftHint)
    }

    @Test
    fun parsesCanonicalAuthorAndTimestampForOwnUserRow() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading",
                localOffsetMinutes = 480,
                todayEpochDay = 20593
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
                                    content = "directory author",
                                    createdAt = "2026-05-19T16:00:00Z"
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        val message = payload.messages.single()
        assertEquals("Artin", message.authorName)
        assertEquals("human", message.authorKind)
        assertEquals(true, message.isOwn)
        assertEquals("2026-05-19T16:00:00Z", message.timestampLabel)
        assertEquals("00:00", payload.formatTimestampLabel(message.timestampLabel))
    }

    @Test
    fun formatsMessageTimestampWithPayloadTimeFormatPreference() {
        val payload =
            KuiklyThreadScreenPayload(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 5, 20),
                timeFormat = "12h"
            )

        assertEquals("12:00 AM", payload.formatTimestampLabel("2026-05-19T16:00:00Z"))
    }

    @Test
    fun formatsMessageTimestampWhenHostOmitsTodayEpochDay() {
        val payload =
            KuiklyThreadScreenPayload(
                localOffsetMinutes = 480,
                nowEpochMillis = 1779206400000L
            )

        assertEquals("00:00", payload.formatTimestampLabel("2026-05-19T16:00:00Z"))
    }

    @Test
    fun parsesCanonicalAgentSenderRow() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "design",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u-artin", email = "artin@slock.ai"),
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
                                    senderType = "agent",
                                    senderId = "agent-android",
                                    senderName = "Android Developer",
                                    content = "agent visual row",
                                    createdAt = "2026-05-19T16:00:00Z"
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    )
            )

        val message = payload.messages.single()
        assertEquals("agent", message.authorKind)
        assertEquals("Android Developer", message.authorName)
        assertEquals("agent-android", message.authorId)
        assertEquals(false, message.isOwn)
    }

    @Test
    fun appendsSentMessageFromSendResponseWhenRefreshIsUnavailable() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
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
    fun picksCurrentServerSummaryForSharedHomeShellLoadingState() {
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-2",
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", name = "First", slug = "first"),
                        StoredAuthServer(id = "server-2", name = "Second", slug = "second")
                    )
            )

        val current = session.currentServerSummary()

        assertEquals("server-2", current?.id?.value)
        assertEquals("Second", current?.name)
    }

    @Test
    fun parsesMessageFooterTaskMetadata() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            isOwn = true,
                            body = "please ship #163",
                            replyCount = 2,
                            taskId = "task-1",
                            taskNumber = 163,
                            taskStatus = "InReview",
                            taskAssigneeName = "Android-Developer-2"
                        )
                    )
            )

        val message = payload.messages.first()
        assertEquals(true, message.isOwn)
        assertEquals("task-1", message.taskId)
        assertEquals(163, message.taskNumber)
        assertEquals("InReview", message.taskStatus)
        assertEquals("Android-Developer-2", message.taskAssigneeName)
        assertEquals(2, message.replyCount)
        assertEquals("#163", threadMessageTaskMetaLabel(message))
        assertEquals("2 replies", threadMessageRepliesMetaLabel(message.toThreadReplyMetadata()))
    }

    @Test
    fun resolvesThreadMentionsFromMembersButTaskFooterUsesLinkedTaskAssignee() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                members =
                    listOf(
                        KuiklyThreadMemberItem(
                            id = "agent_android",
                            displayName = "Android Developer",
                            handle = "Android-Developer",
                            kind = "agent"
                        ),
                        KuiklyThreadMemberItem(
                            id = "user_artin",
                            displayName = "Artin",
                            handle = "artin",
                            kind = "human"
                        )
                    ),
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "agent_android",
                            authorName = "Android Developer",
                            body = "Ping @agent_android and @user_artin",
                            replyCount = 5,
                            taskId = "task-1",
                            taskNumber = 10,
                            taskStatus = "InReview",
                            taskAssigneeName = "agent_android"
                        )
                    )
            )

        val message = payload.messages.first()
        assertEquals(
            "@Android Developer",
            payload.resolveMarkdownTagDisplayLabel(
                ThreadMarkdownTag("@agent_android", ThreadMarkdownTagKind.UserMention)
            )
        )
        assertEquals("Android Developer", payload.resolveTaskAssigneeDisplayName(message.taskAssigneeName))
        assertEquals(
            "#10",
            threadMessageTaskMetaLabel(
                message,
                resolvedTaskAssigneeName = null
            )
        )
        assertEquals(
            "#10 @Tasks Claimed Name",
            threadMessageTaskMetaLabel(
                message.withLinkedTask(
                    TaskSummary(
                        id = TaskId("task-1"),
                        title = "ship task",
                        status = TaskStatus.InReview,
                        taskNumber = 10,
                        messageId = MessageId("m1"),
                        assignee =
                            MemberProfile(
                                id = MemberId("agent_android"),
                                displayName = "Tasks Claimed Name",
                                handle = "Tasks-Claimed-Name",
                                kind = MemberKind.Agent
                            ),
                        updatedAtLabel = ""
                    )
                ),
                "Tasks Claimed Name"
            )
        )
    }

    @Test
    fun linkedTasksAreResolvedByMessageIdAndHideRawIds() {
        val assigneeId = "9d2a923b-7d46-43c3-bf5f-d12a6df8ef12"
        val message =
            KuiklyThreadMessageItem(
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
                    ai.slock.compose.data.ScreenLoadState.Content(
                        ai.slock.compose.data.TasksContent(
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
        assertEquals("#240 @Android Developer", threadMessageTaskMetaLabel(linkedMessage, linkedMessage.taskAssigneeName))

        val uuidOnlyMessage = message.copy(id = "message-241", taskNumber = 241).withLinkedTask(linkedTasks["message-241"])
        assertEquals(null, uuidOnlyMessage.taskAssigneeName)
        assertEquals("#241", threadMessageTaskMetaLabel(uuidOnlyMessage, uuidOnlyMessage.taskAssigneeName))
    }

    @Test
    fun linkedTasksFallbackToTaskIdAndTaskNumberWhenMessageIdIsMissing() {
        val taskWithoutMessageId =
            TaskSummary(
                id = TaskId("task-240"),
                title = "ship task",
                status = TaskStatus.InReview,
                taskNumber = 240,
                messageId = null,
                assignee =
                    MemberProfile(
                        id = MemberId("agent-android"),
                        displayName = "Android Developer",
                        handle = "Android-Developer",
                        kind = MemberKind.Agent
                    ),
                updatedAtLabel = ""
            )
        val linkedTasks = listOf(taskWithoutMessageId).threadLinkedTasksByMessageLookup()
        val taskIdMessage =
            KuiklyThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "ship task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "Todo"
            )
        val taskNumberMessage = taskIdMessage.copy(id = "message-241", taskId = null)

        val byTaskId = taskIdMessage.withLinkedTask(linkedTasks.threadLinkedTaskForMessage(taskIdMessage))
        val byTaskNumber = taskNumberMessage.withLinkedTask(linkedTasks.threadLinkedTaskForMessage(taskNumberMessage))

        assertEquals("InReview", byTaskId.taskStatus)
        assertEquals("Android Developer", byTaskId.taskAssigneeName)
        assertEquals("InReview", byTaskNumber.taskStatus)
        assertEquals("Android Developer", byTaskNumber.taskAssigneeName)
    }

    @Test
    fun threadActionMessageUsesLinkedTaskProjectionForHistoricalTasks() {
        val message =
            KuiklyThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "historical task"
            )
        val task =
            TaskSummary(
                id = TaskId("message-240"),
                title = "historical task",
                status = TaskStatus.InReview,
                taskNumber = 240,
                messageId = null,
                assignee = null,
                updatedAtLabel = ""
            )
        val payload = KuiklyThreadScreenPayload(messages = listOf(message))
        val linkedTasks = listOf(task).threadLinkedTasksByMessageLookup(payload.threadLinkedTaskReferences())

        val actionMessage =
            payload.threadActionMessage(
                "message-240",
                ThreadLinkedTasksProjection(authoritative = true, lookup = linkedTasks)
            )

        assertEquals("message-240", actionMessage?.taskId)
        assertEquals("InReview", actionMessage?.taskStatus)
        assertEquals(240, actionMessage?.taskNumber)
    }

    @Test
    fun threadActionMessageClearsStaleTaskFactsWhenProjectionIsAuthoritativeEmpty() {
        val staleMessage =
            KuiklyThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "historical task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "Todo",
                taskAssigneeId = "agent-android",
                taskAssigneeName = "Android Developer"
            )
        val payload = KuiklyThreadScreenPayload(messages = listOf(staleMessage))

        val actionMessage =
            payload.threadActionMessage(
                "message-240",
                ThreadLinkedTasksProjection(authoritative = true, lookup = emptyMap())
            )

        assertEquals(null, actionMessage?.taskId)
        assertEquals(null, actionMessage?.taskNumber)
        assertEquals(null, actionMessage?.taskStatus)
        assertEquals(null, actionMessage?.taskAssigneeId)
        assertEquals(null, actionMessage?.taskAssigneeName)
        assertEquals("task-240", staleMessage.taskId)
    }

    @Test
    fun findTaskParentMessageUsesLinkedTaskProjectionWithoutProjectedTimeline() {
        val message =
            KuiklyThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "historical task"
            )
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "historical task",
                status = TaskStatus.Done,
                taskNumber = 240,
                messageId = MessageId("message-240"),
                assignee = null,
                updatedAtLabel = ""
            )
        val payload = KuiklyThreadScreenPayload(messages = listOf(message))
        val timeline = payload.toThreadTimelineSlice()
        val projection =
            ThreadLinkedTasksProjection(
                authoritative = true,
                lookup = listOf(task).threadLinkedTasksByMessageLookup(payload.threadLinkedTaskReferences())
            )

        val parent = timeline.findTaskParentMessage("task-240", projection)

        assertEquals("message-240", parent?.id)
        assertEquals(null, timeline.messages.single().taskId)
    }

    @Test
    fun findMarkdownTaskIdUsesLinkedTaskProjectionWithoutProjectedTimeline() {
        val message =
            KuiklyThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "historical task"
            )
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "historical task",
                status = TaskStatus.InProgress,
                taskNumber = 240,
                messageId = MessageId("message-240"),
                assignee = null,
                updatedAtLabel = ""
            )
        val payload = KuiklyThreadScreenPayload(messages = listOf(message))
        val timeline = payload.toThreadTimelineSlice()
        val projection =
            ThreadLinkedTasksProjection(
                authoritative = true,
                lookup = listOf(task).threadLinkedTasksByMessageLookup(payload.threadLinkedTaskReferences())
            )

        val taskId =
            timeline.findMarkdownTaskId(
                ThreadMarkdownTag("#240", ThreadMarkdownTagKind.Task),
                projection
            )

        assertEquals("task-240", taskId)
        assertEquals(null, timeline.messages.single().taskId)
    }

    @Test
    fun markdownTaskNumberLaunchPayloadUsesTaskIdentityOnly() {
        val payload =
            KuiklyThreadScreenPayload(
                alignOwnMessagesRight = true,
                compactConsecutiveMessages = true,
                localOffsetMinutes = 480,
                todayEpochDay = 20260628,
                nowEpochMillis = 1_782_690_000_000,
                timeFormat = SlockTimeFormat12Hour
            )
        val task =
            TaskSummary(
                id = TaskId("task-482"),
                title = "markdown clicks",
                status = TaskStatus.InProgress,
                taskNumber = 482,
                messageId = MessageId("message-482"),
                parentMessageId = MessageId("message-482"),
                channelId = ChannelId("channel-dialog"),
                threadId = ThreadId("thread-482"),
                updatedAtLabel = "now"
            )

        val launchPayload = payload.markdownTaskNumberLaunchPayload(task)

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, launchPayload?.surfaceKind)
        assertEquals("channel-dialog", launchPayload?.parentChannelId)
        assertEquals("message-482", launchPayload?.parentMessageId)
        assertEquals("thread-482", launchPayload?.streamChannelId)
        assertEquals("thread-482", launchPayload?.sourceThreadId)
        assertEquals(true, launchPayload?.alignOwnMessagesRight)
        assertEquals(true, launchPayload?.compactConsecutiveMessages)
        assertEquals(SlockTimeFormat12Hour, launchPayload?.timeFormat)
        assertEquals(emptyList<KuiklyThreadMessageItem>(), launchPayload?.messages)
        assertEquals(emptyList<KuiklyThreadMemberItem>(), launchPayload?.members)
        assertEquals("loading", launchPayload?.status)
    }

    @Test
    fun linkedTaskProjectionIsOnlyAuthoritativeAfterTasksLoadSettles() {
        val references =
            KuiklyThreadScreenPayload(
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "message-240",
                            authorId = "user_artin",
                            authorName = "Artin",
                            body = "ship task",
                            taskId = "task-240",
                            taskNumber = 240
                        )
                    )
            ).threadLinkedTaskReferences()
        val loadingProjection =
            TasksUiState(
                loadState = ai.slock.compose.data.ScreenLoadState.Loading
            ).threadLinkedTasksProjection(references)
        val emptyProjection =
            TasksUiState(
                loadState = ai.slock.compose.data.ScreenLoadState.Empty(ai.slock.compose.data.EmptyReason.NoTasks)
            ).threadLinkedTasksProjection(references)

        assertEquals(false, loadingProjection.authoritative)
        assertEquals(emptyMap(), loadingProjection.lookup)
        assertEquals(true, emptyProjection.authoritative)
        assertEquals(emptyMap(), emptyProjection.lookup)
    }

    @Test
    fun linkedTaskProjectionMatchesMessagesByTaskIdentity() {
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "ship task",
                status = TaskStatus.Done,
                taskNumber = 240,
                messageId = null,
                assignee =
                    MemberProfile(
                        id = MemberId("agent-android"),
                        displayName = "Android Developer",
                        handle = "Android-Developer",
                        kind = MemberKind.Agent
                    ),
                updatedAtLabel = ""
            )
        val unrelatedSameNumberTask =
            task.copy(
                id = TaskId("other-task"),
                status = TaskStatus.Todo,
                taskNumber = 241,
                assignee = null
            )
        val message =
            KuiklyThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "ship task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "Todo"
            )
        val references =
            KuiklyThreadScreenPayload(messages = listOf(message)).threadLinkedTaskReferences()
        val projection =
            TasksUiState(
                loadState =
                    ai.slock.compose.data.ScreenLoadState.Content(
                        ai.slock.compose.data.TasksContent(
                            summaryCounts = emptyMap(),
                            tasks = listOf(task, unrelatedSameNumberTask)
                        )
                    )
            ).threadLinkedTasksProjection(references)
        val linkedMessage = message.withLinkedTask(projection.lookup.threadLinkedTaskForMessage(message))

        assertEquals(true, projection.authoritative)
        assertEquals(null, projection.lookup.threadLinkedTaskForMessage(message.copy(taskId = null, taskNumber = 241)))
        assertEquals("Done", linkedMessage.taskStatus)
        assertEquals("Android Developer", linkedMessage.taskAssigneeName)
    }

    @Test
    fun linkedTaskLookupKeepsWarmSharedProjectionWhenScopedStateIsEmpty() {
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "ship task",
                status = TaskStatus.InReview,
                taskNumber = 240,
                assignee =
                    MemberProfile(
                        id = MemberId("agent-android"),
                        displayName = "Android Developer",
                        handle = "Android-Developer",
                        kind = MemberKind.Agent
                    ),
                updatedAtLabel = ""
            )
        val warmSharedLookup = listOf(task).threadLinkedTasksByMessageLookup()
        val fallbackTask = task.copy(status = TaskStatus.Todo, assignee = null)
        val selectedLookup =
            threadLinkedTasksLookup(
                scopedProjection = ThreadLinkedTasksProjection(authoritative = true, lookup = emptyMap()),
                sharedStoreProjection = ThreadLinkedTasksProjection(authoritative = true, lookup = warmSharedLookup)
            )
        val fallbackLookup = listOf(fallbackTask).threadLinkedTasksByMessageLookup()
        val message =
            KuiklyThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "ship task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "Todo"
            )
        val linkedMessage = message.withLinkedTask(selectedLookup.lookup.threadLinkedTaskForMessage(message))

        assertEquals(warmSharedLookup, selectedLookup.lookup)
        assertNotEquals(fallbackLookup, selectedLookup.lookup)
        assertEquals("InReview", linkedMessage.taskStatus)
        assertEquals("Android Developer", linkedMessage.taskAssigneeName)
    }

    @Test
    fun threadPayloadProjectsMessageTaskStatusFromLinkedTasksStoreWithoutRewritingPayloadTasks() {
        val message =
            KuiklyThreadMessageItem(
                id = "message-240",
                authorId = "user_artin",
                authorName = "Artin",
                body = "ship task",
                taskId = "task-240",
                taskNumber = 240,
                taskStatus = "Todo"
            )
        val staleTask =
            KuiklyThreadTaskItem(
                id = "task-240",
                title = "ship task",
                status = "Todo",
                taskNumber = 240,
                messageId = "message-240"
            )
        val task =
            TaskSummary(
                id = TaskId("task-240"),
                title = "ship task",
                status = TaskStatus.Done,
                taskNumber = 240,
                messageId = MessageId("message-240"),
                updatedAtLabel = "now"
            )
        val payload =
            KuiklyThreadScreenPayload(
                messages = listOf(message),
                tasks = listOf(staleTask)
            )

        val projected =
            payload.toThreadTimelineSlice().withThreadLinkedTasksProjection(
                ThreadLinkedTasksProjection(
                    authoritative = true,
                    lookup = listOf(task).threadLinkedTasksByMessageLookup()
                )
            )

        assertEquals("Done", projected.messages.single().taskStatus)
        assertEquals("Todo", payload.tasks.single().status)
    }

    @Test
    fun authoritativeEmptyLinkedTaskProjectionClearsStaleMessageTaskFacts() {
        val payload =
            KuiklyThreadScreenPayload(
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
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
    fun threadTasksJoinStartsOnlyAfterContentGate() {
        val taskChannelId = ChannelId("channel-1")
        val loadingPayload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading",
                showTaskStatusActions = true
            )
        val contentPayload = loadingPayload.copy(status = "content", selectedTab = "chat")

        assertFalse(shouldStartThreadTasksJoin(loadingPayload, startedThreadId = null, taskChannelId = taskChannelId))
        assertTrue(shouldStartThreadTasksJoin(contentPayload, startedThreadId = null, taskChannelId = taskChannelId))
        assertFalse(shouldStartThreadTasksJoin(contentPayload, startedThreadId = "channel-1", taskChannelId = taskChannelId))
        assertTrue(
            shouldStartThreadTasksJoin(
                contentPayload.copy(selectedTab = "tasks"),
                startedThreadId = null,
                taskChannelId = taskChannelId
            )
        )
        assertFalse(
            shouldStartThreadTasksJoin(
                contentPayload.copy(sourceThreadId = null),
                startedThreadId = null,
                taskChannelId = taskChannelId
            )
        )
    }

    @Test
    fun resolvedAuxiliaryThreadIdUsesCurrentPayloadIdentityAfterLoading() {
        val launchPayload =
            KuiklyThreadScreenPayload(
                instanceId = "activity-thread",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "wrong-thread",
                streamChannelId = "wrong-thread",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                status = "loading"
            )

        assertEquals(null, launchPayload.resolvedAuxiliaryThreadId())
        assertEquals(
            "thread-m1",
            launchPayload
                .copy(
                    sourceThreadId = "thread-m1",
                    streamChannelId = "thread-m1",
                    status = "content"
                )
                .resolvedAuxiliaryThreadId()
        )
        assertEquals(
            "wrong-thread",
            launchPayload
                .copy(status = "content")
                .resolvedAuxiliaryThreadId()
        )
    }

    @Test
    fun resolvedAuxiliaryThreadIdAllowsEmptyReplyThreadAfterLoad() {
        val launchPayload =
            KuiklyThreadScreenPayload(
                instanceId = "task-thread-task-1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                status = "loading"
            )

        val loadedEmptyPayload =
            launchPayload.copy(
                status = "empty",
                messages = emptyList()
            )
        val resolvedThreadId = loadedEmptyPayload.resolvedAuxiliaryThreadId()
        val composer =
            loadedEmptyPayload
                .resolveThreadComposer()
                .copy(streamChannelId = resolvedThreadId, enabled = resolvedThreadId != null)
        val surface =
            composer.toThreadComposerSurface(
                channelId = resolvedThreadId.orEmpty(),
                parentChannelId = loadedEmptyPayload.parentChannelId
            )

        assertEquals(null, launchPayload.resolvedAuxiliaryThreadId())
        assertEquals("thread-m1", resolvedThreadId)
        assertEquals("thread-m1", surface.sendChannelId)
    }

    @Test
    fun threadTasksJoinUsesParentChannelForReplyThreadTaskStatusHydration() {
        val launchPayload =
            KuiklyThreadScreenPayload(
                instanceId = "activity-thread",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "wrong-thread",
                streamChannelId = "wrong-thread",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                status = "loading",
                showTaskStatusActions = true
            )
        val resolvedPayload =
            launchPayload.copy(
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                status = "content",
                selectedTab = "chat"
            )
        val resolvedTaskChannelId = resolvedPayload.resolvedTaskLookupChannelId()?.let(::ChannelId)

        assertEquals("thread-m1", resolvedPayload.resolvedAuxiliaryThreadId())
        assertEquals("channel-1", resolvedPayload.resolvedTaskLookupChannelId())
        assertFalse(shouldStartThreadTasksJoin(launchPayload, startedThreadId = null, taskChannelId = ChannelId("channel-1")))
        assertTrue(shouldStartThreadTasksJoin(resolvedPayload, startedThreadId = null, taskChannelId = resolvedTaskChannelId))
        assertFalse(shouldStartThreadTasksJoin(resolvedPayload, startedThreadId = null, taskChannelId = ChannelId("thread-m1")))
    }

    @Test
    fun hidesRawUuidTaskAssigneeUntilMemberNameCanBeResolved() {
        val assigneeId = "9d2a923b-7d46-43c3-bf5f-d12a6df8ef12"
        val base =
            KuiklyThreadScreenPayload(
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
                                    content = "please ship #236",
                                    taskNumber = 236,
                                    taskStatus = "InReview",
                                    taskAssigneeId = assigneeId
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = false
                    )
            )

        val message = payload.messages.first()
        assertEquals(assigneeId, message.taskAssigneeId)
        assertEquals(null, message.taskAssigneeName)
        assertEquals("#236", threadMessageTaskMetaLabel(message))
    }

    @Test
    fun resolvesUuidTaskAssigneeIdFromThreadMembers() {
        val assigneeId = "9d2a923b-7d46-43c3-bf5f-d12a6df8ef12"
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                members =
                    listOf(
                        KuiklyThreadMemberItem(
                            id = assigneeId,
                            displayName = "Android Developer",
                            handle = "Android-Developer",
                            kind = "agent"
                        )
                    ),
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "user_artin",
                            authorName = "Artin",
                            body = "ship task",
                            taskId = "task-236",
                            taskNumber = 236,
                            taskStatus = "InReview",
                            taskAssigneeId = assigneeId,
                            taskAssigneeName = assigneeId
                        )
                    )
            )

        val message = payload.messages.first()
        assertEquals(assigneeId, message.taskAssigneeId)
        assertEquals("Android Developer", payload.resolveTaskAssigneeDisplayName(message.taskAssigneeName, message.taskAssigneeId))
        assertEquals(
            "#236",
            threadMessageTaskMetaLabel(
                message,
                resolvedTaskAssigneeName = null
            )
        )
    }

    @Test
    fun threadMemberDetailPageDataCarriesMemberIdentityAndExpectedKindOnly() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                sourceThreadId = "thread-source-1",
                localOffsetMinutes = 480,
                todayEpochDay = 20623,
                nowEpochMillis = 1782200000000,
                timeFormat = SlockTimeFormat12Hour
            )

        val pageData = payload.toThreadMemberDetailPageData("human-artin", MemberKind.Human)!!

        assertEquals(MEMBER_DETAIL_PAGE, pageData.optString("pageName"))
        assertEquals("human-artin", pageData.optString("memberId"))
        assertEquals("human", pageData.optString("memberKind"))
        assertEquals("thread-member-detail-thread-1-human-artin", pageData.optString("instanceId"))
        assertEquals("", pageData.optString("members"))
        assertEquals("", pageData.optString("profile"))
        assertEquals("480", pageData.optString("localOffsetMinutes"))
        assertEquals("20623", pageData.optString("todayEpochDay"))
        assertEquals("1782200000000", pageData.optString("nowEpochMillis"))
        assertEquals(SlockTimeFormat12Hour, pageData.optString("timeFormat"))
    }

    @Test
    fun threadListBoundsUseMeasuredItemsForBottomOffset() {
        assertEquals(0, threadMessagesBeyondBoundsItemCount(-1))
        assertEquals(1, threadMessagesBeyondBoundsItemCount(1))
        assertEquals(2, threadMessagesBeyondBoundsItemCount(80))
        assertEquals(
            -568,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 72,
                viewportHeight = 640,
                visibleItemCount = 2,
                totalItemCount = 8
            )
        )
        assertEquals(
            160,
            threadLatestItemBottomAnchorScrollOffset(
                itemSize = 800,
                viewportHeight = 640,
                visibleItemCount = 2,
                totalItemCount = 8
            )
        )
    }

    @Test
    fun parsesSystemMessageMetadataAndBuildsCollapsedSummary() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
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
        assertEquals("10:00 Cindy joined\nthe channel", message.collapsedSystemSummary("10:00"))
        assertEquals("Cindy joined\nthe channel", message.systemContentSummary())
        assertEquals("Cindy joined\nthe channel\nType: member_joined\nactor: Cindy\nsource: invite", message.expandedSystemContent())
        assertEquals("Type: member_joined\nactor: Cindy\nsource: invite", message.expandedSystemDetails())
    }

    @Test
    fun sanitizesSystemMessageInlineWhitespaceWithoutNativeRegexEscapes() {
        assertEquals(
            "one two three four five",
            "one\t two\u000Bthree\u000Cfour\rfive".sanitizeThreadSystemText()
        )
    }

    @Test
    fun formatsRawTimestampInSharedThreadPayload() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                localOffsetMinutes = 480,
                todayEpochDay = 20583,
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            timestampLabel = "2026-05-09 21:53:33.024426+00",
                            body = "raw utc timestamp"
                        )
                    )
            )

        assertEquals("2026-05-09 21:53:33.024426+00", payload.messages.first().timestampLabel)
        assertEquals("05:53", payload.formatTimestampLabel(payload.messages.first().timestampLabel))
    }

    @Test
    fun parsesReplyContextFields() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-2",
                title = "Threads",
                subtitle = "Origin: #general · hello",
                navigationLabel = "Close",
                showModeTabs = false,
                showReplyActions = false,
                showOpenInChannelAction = true,
                originStatus = "message",
                originParentMessageId = "m-parent",
                originMessage =
                    KuiklyThreadMessageItem(
                        id = "m-parent",
                        authorId = "u1",
                        authorName = "Artin",
                        body = "hello from parent"
                    )
            )

        assertEquals("thread-2", payload.instanceId)
        assertEquals("Close", payload.navigationLabel)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showReplyActions)
        assertEquals(true, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals("message", payload.originStatus)
        assertEquals("m-parent", payload.originParentMessageId)
        assertEquals("hello from parent", payload.originMessage?.body)
    }

    @Test
    fun sharedShellKeepsSupportedThreadEventAffordances() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                showReplyActions = true,
                showOpenInChannelAction = true,
                showMessageSaveAction = true,
                showTaskOpenActions = true,
                showTaskStatusActions = true,
                canLoadMoreBefore = true,
                canLoadMoreAfter = true,
                canAddMembers = true,
                showSettingsAction = true
            ).withSharedShellEventCapabilities()

        assertEquals(true, payload.showReplyActions)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals(true, payload.showMessageSaveAction)
        assertEquals(true, payload.showTaskOpenActions)
        assertEquals(true, payload.showTaskStatusActions)
        assertEquals(true, payload.canLoadMoreBefore)
        assertEquals(true, payload.canLoadMoreAfter)
        assertEquals(true, payload.canAddMembers)
        assertEquals(true, payload.showSettingsAction)
        assertEquals(true, payload.showMembersAction)
    }

    @Test
    fun sharedShellHidesChannelChromeForReplyThread() {
        val payload =
            KuiklyThreadScreenPayload(
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
                }
            )

        assertEquals(1, attachments.size)
        assertEquals("att-1", attachments.first().id)
        assertEquals("report.pdf", attachments.first().fileName)
        assertEquals("application/pdf", attachments.first().mimeType)
    }

    @Test
    fun parsesPickedAttachmentFileRefsForSharedUpload() {
        val files =
            parseSharedThreadPickedAttachmentFiles(
                JSONObject().apply {
                    put(
                        "files",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("id", "picked-1")
                                    put("fileName", "report.txt")
                                    put("mimeType", "text/plain")
                                    put("sizeBytes", 5L)
                                    put("previewUri", "content://picked/report.txt")
                                    put("fileRef", "content://picked/report.txt")
                                }
                            )
                        }
                    )
                }
            )

        assertEquals(1, files.size)
        assertEquals("picked-1", files.first().id)
        assertEquals("report.txt", files.first().fileName)
        assertEquals("text/plain", files.first().mimeType)
        assertEquals(5L, files.first().sizeBytes)
        assertEquals("content://picked/report.txt", files.first().previewUri)
        assertEquals("content://picked/report.txt", files.first().fileRef)
        assertEquals(0, files.first().bytes.size)
    }

    @Test
    fun parsesPickedAttachmentFileRefsFromBodyTextForSharedUpload() {
        val files =
            parseSharedThreadPickedAttachmentFiles(
                JSONObject().apply {
                    put(
                        "bodyText",
                        JSONObject()
                            .put(
                                "files",
                                JSONArray().apply {
                                    put(
                                        JSONObject().apply {
                                            put("id", "picked-1")
                                            put("fileName", "report.txt")
                                            put("mimeType", "text/plain")
                                            put("sizeBytes", 5L)
                                            put("previewUri", "content://picked/report.txt")
                                            put("fileRef", "content://picked/report.txt")
                                        }
                                    )
                                }
                            )
                            .toString()
                    )
                }
            )

        assertEquals(1, files.size)
        assertEquals("picked-1", files.first().id)
        assertEquals("report.txt", files.first().fileName)
        assertEquals("text/plain", files.first().mimeType)
        assertEquals(5L, files.first().sizeBytes)
        assertEquals("content://picked/report.txt", files.first().previewUri)
        assertEquals("content://picked/report.txt", files.first().fileRef)
        assertEquals(0, files.first().bytes.size)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun parsesPickedAttachmentBase64FallbackForSharedUpload() {
        val files =
            parseSharedThreadPickedAttachmentFiles(
                JSONObject().apply {
                    put(
                        "files",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("id", "picked-1")
                                    put("fileName", "report.txt")
                                    put("bodyBase64", Base64.encode("hello".encodeToByteArray()))
                                }
                            )
                        }
                    )
                }
            )

        assertEquals(1, files.size)
        assertEquals("hello", files.first().bytes.decodeToString())
        assertEquals(null, files.first().fileRef)
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
            channels = channels.toList(),
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
    ): KuiklyThreadMessageItem =
        KuiklyThreadMessageItem(
            id = id,
            authorId = authorId,
            authorName = authorHandle,
            authorHandle = authorHandle
        )

    private fun testThreadMessage(
        id: String,
        messageType: String = "chat"
    ): KuiklyThreadMessageItem =
        KuiklyThreadMessageItem(
            id = id,
            authorId = "u-$id",
            authorName = "User $id",
            messageType = messageType,
            body = "body $id"
        )
}
