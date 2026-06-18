package ai.slock.compose

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
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
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
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
import kotlin.test.assertTrue

class ThreadPayloadTest {
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
            5,
            threadMessagesLazyItemCount(
                originStatus = "content",
                showHistoryBoundary = true,
                messagesCount = 3,
                canLoadMoreAfter = true
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
    fun threadBottomAnchorOffsetDoesNotOverscrollFullyMeasuredShortLists() {
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
            0,
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
    }

    @Test
    fun parsesMembersAndSettingsActions() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-1",
                      "title": "对话流专修",
                      "membersCount": 2,
                      "canAddMembers": false,
                      "addMemberStatus": "failed",
                      "addMemberErrorCode": "member_exists",
                      "removeMemberStatus": "failed",
                      "removingMemberId": "a1",
                      "removeMemberErrorCode": "member_remove_failed",
                      "members": [
                        {"id": "u1", "displayName": "Artin", "handle": "artin", "roleLabel": "Owner"},
                        {"id": "a1", "displayName": "Android Bot", "kind": "agent", "avatarLabel": "AB"}
                      ],
                      "memberCandidates": [
                        {"id": "u2", "displayName": "Cindy", "handle": "cindy"},
                        {"id": "a2", "displayName": "QA Bot", "kind": "agent"}
                      ],
                      "settingsActions": [
                        {"id": "visibility", "title": "Make Private", "icon": "lock", "destructive": true},
                        {"id": "archive", "title": "Archive Channel", "icon": "archive", "destructive": true},
                        {"id": "delete", "title": "Delete Channel", "icon": "trash", "destructive": true}
                      ]
                    }
                    """.trimIndent(),
                fallbackPageData = "{}",
                instanceId = "fallback"
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
        assertEquals(true, payload.settingsActions.all { it.destructive })
    }

    @Test
    fun invalidPayloadReturnsErrorState() {
        val payload = parseThreadScreenPayload("{not-json", "{}", "bad-1")

        assertEquals("bad-1", payload.instanceId)
        assertEquals("error", payload.status)
        assertEquals("payload_parse_failed", payload.errorCode)
    }

    @Test
    fun threadSettingsActionsDropLegacyLeaveFallback() {
        val actions =
            listOf(
                KuiklyThreadSettingsAction(
                    id = "leave",
                    title = "Leave Channel",
                    icon = "log-out",
                    destructive = true
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
        assertEquals("general", payload.title)
        assertEquals("Team channel", payload.subtitle)
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
        assertEquals("Message #general", composer.placeholder)
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

        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("dm-agent-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        val composer = payload.resolveThreadComposer()
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, composer.surfaceKind)
        assertEquals("dm-agent-1", composer.streamChannelId)
        assertEquals("Message @Product-UX-Designer", composer.placeholder)
        assertEquals("idle", composer.composerMode)
    }

    @Test
    fun sharedShellReplyThreadRouteUsesThreadTitleAndChannelSubtitle() {
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

        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
    }

    @Test
    fun appShellActivityThreadItemOpensReplyThreadChrome() {
        val conversation =
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
                latestActivityPreview = "Latest reply"
            ).toAppShellConversationSummary()

        val payload = buildAppShellThreadPayload(conversation)

        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), conversation.threadId)
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("thread-channel-1", payload.sourceThreadId)
        assertEquals("thread-channel-1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
    }

    @Test
    fun appShellActivityThreadItemFallsBackToParentChannelWhenThreadStreamIsMissing() {
        val conversation =
            InboxItem(
                key = "thread:message-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("message-1"),
                parentMessagePreview = "Parent message preview",
                latestActivityPreview = "Latest reply"
            ).toAppShellConversationSummary()

        val payload = buildAppShellThreadPayload(conversation)

        assertEquals(threadIdentity(ChannelId("channel-1"), MessageId("message-1")), conversation.threadId)
        assertEquals(ConversationKind.Thread, conversation.kind)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
    }

    @Test
    fun appShellActivityChannelItemKeepsChannelChrome() {
        val conversation =
            InboxItem(
                key = "channel:general",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-1"),
                channelName = "general",
                lastMessagePreview = "Latest channel message",
                unreadCount = 2
            ).toAppShellConversationSummary()

        val payload = buildAppShellThreadPayload(conversation)

        assertEquals(ThreadId("channel-1"), conversation.threadId)
        assertEquals(ConversationKind.Channel, conversation.kind)
        assertEquals("general", payload.title)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
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

        assertEquals("general", payload.title)
        assertEquals(null, payload.subtitle)
    }

    @Test
    fun buildsTaskThreadLoadingPayloadBeforeOpeningReplyThread() {
        val payload =
            buildAppShellTaskThreadLoadingPayload(
                TaskSummary(
                    id = TaskId("task-1"),
                    title = "Fix slow route",
                    status = TaskStatus.InProgress,
                    taskNumber = 42,
                    messageId = MessageId("message-1"),
                    channelId = ChannelId("channel-1"),
                    channelName = "general",
                    updatedAtLabel = "now"
                ),
                AppShellThreadTimeContext(localOffsetMinutes = 480, todayEpochDay = 20593),
                alignOwnMessagesRight = true
            )

        assertEquals("shared-shell-task-thread-task-1", payload.instanceId)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals("loading", payload.status)
        assertEquals(null, payload.sourceThreadId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals("Opening task", payload.emptyTitle)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(20593, payload.todayEpochDay)
        assertEquals(true, payload.alignOwnMessagesRight)
    }

    @Test
    fun threadTaskBuildsContainingThreadPayloadInsteadOfReplyThreadPayload() {
        val payload =
            buildAppShellTaskContainingThreadPayload(
                TaskSummary(
                    id = TaskId("task-1"),
                    title = "Fix slow route",
                    status = TaskStatus.InProgress,
                    taskNumber = 42,
                    messageId = MessageId("message-1"),
                    channelId = ChannelId("thread-channel-1"),
                    isThreadTask = true,
                    channelName = "bug triage",
                    updatedAtLabel = "now"
                ),
                AppShellThreadTimeContext(localOffsetMinutes = 480, todayEpochDay = 20593),
                alignOwnMessagesRight = true
            )

        requireNotNull(payload)
        assertEquals("shared-shell-task-thread-thread-channel-1", payload.instanceId)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#bug triage", payload.subtitle)
        assertEquals("loading", payload.status)
        assertEquals("thread-channel-1", payload.sourceThreadId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("thread-channel-1", payload.streamChannelId)
        assertEquals("message-1", payload.parentMessageId)
        assertEquals(false, payload.showReplyActions)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals(true, payload.alignOwnMessagesRight)
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
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
        assertEquals("Opening replies", payload.emptyTitle)
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
        assertEquals("3 replies · 2 new", threadMessageRepliesMetaLabel(payload.messages.first()))
        assertEquals("👍", payload.messages.first().reactions.first().emoji)
        assertEquals(2, payload.messages.first().reactions.first().count)
        assertEquals(true, payload.messages.first().reactions.first().selected)
        assertEquals("log.txt", payload.messages.first().attachments.first().name)
        assertEquals("https://cdn.example/screen.png", payload.messages.first().attachments[1].thumbnailUrl)
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
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-1",
                      "messages": [
                        {
                          "id": "m1",
                          "authorId": "u1",
                          "authorName": "Artin",
                          "isOwn": true,
                          "body": "please ship #163",
                          "replyCount": 2,
                          "taskId": "task-1",
                          "taskNumber": 163,
                          "taskStatus": "InReview",
                          "taskAssigneeName": "Android-Developer-2"
                        }
                      ]
                    }
                    """.trimIndent(),
                fallbackPageData = "{}",
                instanceId = "fallback"
            )

        val message = payload.messages.first()
        assertEquals(true, message.isOwn)
        assertEquals("task-1", message.taskId)
        assertEquals(163, message.taskNumber)
        assertEquals("InReview", message.taskStatus)
        assertEquals("Android-Developer-2", message.taskAssigneeName)
        assertEquals(2, message.replyCount)
        assertEquals("#163", threadMessageTaskMetaLabel(message))
        assertEquals("2 replies", threadMessageRepliesMetaLabel(message))
    }

    @Test
    fun resolvesThreadMentionsFromMembersButTaskFooterUsesLinkedTaskAssignee() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-1",
                      "members": [
                        {
                          "id": "agent_android",
                          "displayName": "Android Developer",
                          "handle": "Android-Developer",
                          "kind": "agent"
                        },
                        {
                          "id": "user_artin",
                          "displayName": "Artin",
                          "handle": "artin",
                          "kind": "human"
                        }
                      ],
                      "messages": [
                        {
                          "id": "m1",
                          "authorId": "agent_android",
                          "authorName": "Android Developer",
                          "body": "Ping @agent_android and @user_artin",
                          "replyCount": 5,
                          "taskId": "task-1",
                          "taskNumber": 10,
                          "taskStatus": "InReview",
                          "taskAssigneeName": "agent_android"
                        }
                      ]
                    }
                    """.trimIndent(),
                fallbackPageData = "{}",
                instanceId = "fallback"
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
    fun threadTasksJoinStartsOnlyAfterContentGate() {
        val taskChannelId = ChannelId("channel-1")
        val loadingPayload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val contentPayload = loadingPayload.copy(status = "content")

        assertFalse(shouldStartThreadTasksJoin(loadingPayload, startedThreadId = null, taskChannelId = taskChannelId))
        assertTrue(shouldStartThreadTasksJoin(contentPayload, startedThreadId = null, taskChannelId = taskChannelId))
        assertFalse(shouldStartThreadTasksJoin(contentPayload, startedThreadId = "channel-1", taskChannelId = taskChannelId))
        assertFalse(
            shouldStartThreadTasksJoin(
                contentPayload.copy(sourceThreadId = null),
                startedThreadId = null,
                taskChannelId = taskChannelId
            )
        )
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
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-1",
                      "members": [
                        {
                          "id": "$assigneeId",
                          "displayName": "Android Developer",
                          "handle": "Android-Developer",
                          "kind": "agent"
                        }
                      ],
                      "messages": [
                        {
                          "id": "m1",
                          "authorId": "user_artin",
                          "authorName": "Artin",
                          "body": "ship task",
                          "taskId": "task-236",
                          "taskNumber": 236,
                          "taskStatus": "InReview",
                          "taskAssigneeId": "$assigneeId",
                          "taskAssigneeName": "$assigneeId"
                        }
                      ]
                    }
                    """.trimIndent(),
                fallbackPageData = "{}",
                instanceId = "fallback"
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
    fun threadListBoundsUseMeasuredItemsAndNeverNegativeBottomOffset() {
        assertEquals(0, threadMessagesBeyondBoundsItemCount(-1))
        assertEquals(1, threadMessagesBeyondBoundsItemCount(1))
        assertEquals(8, threadMessagesBeyondBoundsItemCount(80))
        assertEquals(
            0,
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
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-1",
                      "messages": [
                        {
                          "id": "m-system",
                          "authorId": "system",
                          "authorName": "System",
                          "messageType": "System",
                          "systemEventType": "member_joined",
                          "timestampLabel": "2026-05-09 21:53:33.024426+00",
                          "body": "<p>Cindy joined<br>the channel</p>",
                          "metadata": {
                            "actor": "Cindy",
                            "source": "<b>invite</b>"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                fallbackPageData = "{}",
                instanceId = "fallback"
            )

        val message = payload.messages.first()
        assertEquals(true, message.isSystemMessage())
        assertEquals("member_joined", message.systemEventType)
        assertEquals("Cindy", message.metadata["actor"])
        assertEquals("invite", message.metadata["source"]?.sanitizeThreadSystemText())
        assertEquals("10:00 Cindy joined\nthe channel", message.collapsedSystemSummary("10:00"))
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
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-1",
                      "localOffsetMinutes": 480,
                      "todayEpochDay": 20583,
                      "messages": [
                        {
                          "id": "m1",
                          "authorId": "u1",
                          "authorName": "Artin",
                          "timestampLabel": "2026-05-09 21:53:33.024426+00",
                          "body": "raw utc timestamp"
                        }
                      ]
                    }
                    """.trimIndent(),
                fallbackPageData = "{}",
                instanceId = "fallback"
            )

        assertEquals("2026-05-09 21:53:33.024426+00", payload.messages.first().timestampLabel)
        assertEquals("05:53", payload.formatTimestampLabel(payload.messages.first().timestampLabel))
    }

    @Test
    fun parsesReplyContextFields() {
        val payload =
            parseThreadScreenPayload(
                payload =
                    """
                    {
                      "instanceId": "thread-2",
                      "title": "Threads",
                      "subtitle": "Origin: #general · hello",
                      "navigationLabel": "Close",
                      "showModeTabs": false,
                      "showReplyActions": false,
                      "showOpenInChannelAction": true,
                      "originStatus": "message",
                      "originParentMessageId": "m-parent",
                      "originMessage": {
                        "id": "m-parent",
                        "authorId": "u1",
                        "authorName": "Artin",
                        "body": "hello from parent"
                      }
                    }
                    """.trimIndent(),
                fallbackPageData = "{}",
                instanceId = "fallback"
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
}
