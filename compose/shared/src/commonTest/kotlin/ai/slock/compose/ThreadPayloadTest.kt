package ai.slock.compose

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.ThreadId
import ai.slock.compose.markdown.ThreadMarkdownTag
import ai.slock.compose.markdown.ThreadMarkdownTagKind
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.data.ThreadMessagesPage
import ai.slock.compose.data.WireAttachment
import ai.slock.compose.data.WireMessage
import ai.slock.compose.data.WireReaction
import ai.slock.compose.data.WireThreadSummary
import ai.slock.compose.data.appendSharedThreadSentMessageFromPage
import ai.slock.compose.data.buildSharedThreadPayloadFromMessagesPage
import ai.slock.compose.data.parseSharedThreadUploadedAttachments
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadPayloadTest {
    @Test
    fun threadMessagesLazyItemCountTracksDecoratedRows() {
        assertEquals(
            3,
            threadMessagesLazyItemCount(
                originStatus = "none",
                canLoadMoreBefore = false,
                messagesCount = 3,
                canLoadMoreAfter = false
            )
        )
        assertEquals(
            6,
            threadMessagesLazyItemCount(
                originStatus = "content",
                canLoadMoreBefore = true,
                messagesCount = 3,
                canLoadMoreAfter = true
            )
        )
        assertEquals(
            3,
            sharedListBeyondBoundsItemCount(
                threadMessagesLazyItemCount(
                    originStatus = "none",
                    canLoadMoreBefore = false,
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
        assertEquals(
            listOf("visibility", "archive", "delete"),
            payload.settingsActions.map { it.id }
        )
        assertEquals("Message #general", parseThreadComposerPayload(payload.composerPayload, "{}", payload.instanceId).placeholder)
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
        assertEquals("#42", payload.title)
        assertEquals("general", payload.subtitle)
        assertEquals("loading", payload.status)
        assertEquals(null, payload.sourceThreadId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals("Opening task", payload.emptyTitle)
        assertEquals(480, payload.localOffsetMinutes)
        assertEquals(20593, payload.todayEpochDay)
        assertEquals(true, payload.alignOwnMessagesRight)
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
        assertEquals("Message Threads", payload.title)
        assertEquals("general", payload.subtitle)
        assertEquals("loading", payload.status)
        assertEquals(null, payload.sourceThreadId)
        assertEquals("message-1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(false, payload.showModeTabs)
        assertEquals(true, payload.showOpenInChannelAction)
        assertEquals("Opening replies", payload.emptyTitle)
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
        assertEquals("#42 @Android-Developer-2", threadMessageTaskMetaLabel(payload.messages.first()))
        assertEquals("3 replies · 2 new", threadMessageRepliesMetaLabel(payload.messages.first()))
        assertEquals("👍", payload.messages.first().reactions.first().emoji)
        assertEquals(2, payload.messages.first().reactions.first().count)
        assertEquals(true, payload.messages.first().reactions.first().selected)
        assertEquals("log.txt", payload.messages.first().attachments.first().name)
        assertEquals("https://cdn.example/screen.png", payload.messages.first().attachments[1].thumbnailUrl)
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
        assertEquals("#163 @Android-Developer-2", threadMessageTaskMetaLabel(message))
        assertEquals("2 replies", threadMessageRepliesMetaLabel(message))
    }

    @Test
    fun resolvesThreadMentionAndAssigneeDisplayNamesFromMembers() {
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
            "#10 @Android Developer",
            threadMessageTaskMetaLabel(
                message,
                payload.resolveTaskAssigneeDisplayName(message.taskAssigneeName)
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
        assertEquals("10:00 · Cindy joined\nthe channel", message.collapsedSystemSummary("10:00"))
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
    }

    @Test
    fun sharedShellHidesSettingsForReplyThreadChrome() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "thread-reply-1",
                title = "Message Threads",
                showModeTabs = false,
                showSettingsAction = true,
                showOpenInChannelAction = true,
                originParentMessageId = "message-1"
            ).withSharedShellEventCapabilities()

        assertEquals(false, payload.showSettingsAction)
        assertEquals(true, payload.showOpenInChannelAction)
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
}
