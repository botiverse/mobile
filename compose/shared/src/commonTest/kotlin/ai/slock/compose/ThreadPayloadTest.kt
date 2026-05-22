package ai.slock.compose

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.ThreadId
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadPayloadTest {
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
                        {"id": "notifications", "title": "Notifications", "subtitle": "Configure alerts", "icon": "bell-ring"},
                        {"id": "leave", "title": "Leave", "icon": "log-out", "destructive": true}
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
        assertEquals("notifications", payload.settingsActions.first().id)
        assertEquals(true, payload.settingsActions[1].destructive)
    }

    @Test
    fun invalidPayloadReturnsErrorState() {
        val payload = parseThreadScreenPayload("{not-json", "{}", "bad-1")

        assertEquals("bad-1", payload.instanceId)
        assertEquals("error", payload.status)
        assertEquals("payload_parse_failed", payload.errorCode)
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
                )
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
        assertEquals(false, payload.showReplyActions)
        assertEquals("Message #general", parseThreadComposerPayload(payload.composerPayload, "{}", payload.instanceId).placeholder)
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
            buildSharedThreadPayloadFromMessagesResponse(
                basePayload = base,
                session = session,
                bodyText =
                    """
                    {
                      "data": {
                        "messages": [
                          {
                            "id": "m1",
                            "body": "hello from shared route",
                            "createdAt": "2026-05-19T16:00:00Z",
                            "replyCount": 3,
                            "author": {
                              "id": "u1",
                              "displayName": "Artin",
                              "avatar": {"type": "pixel", "key": "robot"}
                            },
                            "attachments": [
                              {"id": "a1", "name": "log.txt", "mimeType": "text/plain"},
                              {"id": "a2", "name": "screen.png", "mimeType": "image/png", "imageUrl": "https://cdn.example/screen.png"}
                            ]
                          }
                        ]
                      }
                    }
                    """.trimIndent()
            )

        assertEquals("content", payload.status)
        assertEquals("m1", payload.messages.first().id)
        assertEquals("hello from shared route", payload.messages.first().body)
        assertEquals("Artin", payload.messages.first().authorName)
        assertEquals(true, payload.messages.first().isOwn)
        assertEquals("pixel:robot", payload.messages.first().avatarWireValue)
        assertEquals(3, payload.messages.first().replyCount)
        assertEquals("log.txt", payload.messages.first().attachments.first().name)
        assertEquals("https://cdn.example/screen.png", payload.messages.first().attachments[1].thumbnailUrl)
    }

    @Test
    fun hydratesSharedThreadAuthorsFromResponseDirectories() {
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
            buildSharedThreadPayloadFromMessagesResponse(
                basePayload = base,
                session = session,
                bodyText =
                    """
                    {
                      "data": {
                        "messages": [
                          {
                            "id": "m1",
                            "body": "directory author",
                            "authorId": "u1",
                            "created_at": "2026-05-19T16:00:00Z"
                          }
                        ],
                        "users": [
                          {
                            "id": "u1",
                            "displayName": "Artin",
                            "avatar": {"type": "pixel", "key": "robot"}
                          }
                        ]
                      }
                    }
                    """.trimIndent()
            )

        val message = payload.messages.single()
        assertEquals("Artin", message.authorName)
        assertEquals("pixel:robot", message.avatarWireValue)
        assertEquals(true, message.isOwn)
        assertEquals("2026-05-19T16:00:00Z", message.timestampLabel)
        assertEquals("00:00", payload.formatTimestampLabel(message.timestampLabel))
    }

    @Test
    fun prefersAgentAuthorFieldsOverCurrentUserFieldsForDirectMessageRows() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-dm-1",
                title = "stdrc",
                sourceThreadId = "dm-1",
                status = "loading",
                localOffsetMinutes = 480,
                todayEpochDay = 20593
            )
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u-artin", email = "artin@slock.ai"),
                accessToken = "access",
                refreshToken = "refresh"
            )

        val payload =
            buildSharedThreadPayloadFromMessagesResponse(
                basePayload = base,
                session = session,
                bodyText =
                    """
                    {
                      "data": {
                        "messages": [
                          {
                            "id": "m1",
                            "body": "agent dm reply",
                            "userId": "u-artin",
                            "userDisplayName": "artin",
                            "agentId": "agent-stdrc",
                            "createdAt": "2026-05-19T16:00:00Z"
                          }
                        ],
                        "agents": [
                          {
                            "id": "agent-stdrc",
                            "displayName": "stdrc",
                            "avatar": {"type": "pixel", "key": "stdrc"}
                          }
                        ]
                      }
                    }
                    """.trimIndent()
            )

        val message = payload.messages.single()
        assertEquals("agent-stdrc", message.authorId)
        assertEquals("stdrc", message.authorName)
        assertEquals("pixel:stdrc", message.avatarWireValue)
        assertEquals(false, message.isOwn)
    }

    @Test
    fun resolvesAgentPixelAvatarFallbackForThreadRows() {
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
            buildSharedThreadPayloadFromMessagesResponse(
                basePayload = base,
                session = session,
                bodyText =
                    """
                    {
                      "data": {
                        "messages": [
                          {
                            "id": "m1",
                            "body": "agent visual row",
                            "authorId": "agent-android",
                            "createdAt": "2026-05-19T16:00:00Z"
                          }
                        ],
                        "agents": [
                          {
                            "id": "agent-android",
                            "displayName": "Android Developer"
                          }
                        ]
                      }
                    }
                    """.trimIndent()
            )

        val message = payload.messages.single()
        assertEquals("agent", message.authorKind)
        assertEquals("Android Developer", message.authorName)
        assertEquals("random:agent-android", message.resolvedThreadAvatarWireValue())
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
            appendSharedThreadSentMessageFromResponse(
                basePayload = base,
                session = session,
                bodyText =
                    """
                    {
                      "data": {
                        "id": "m2",
                        "body": "sent from shared composer",
                        "author": {"id": "u1", "displayName": "Artin"}
                      }
                    }
                    """.trimIndent()
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
    }

    @Test
    fun parsesMessageFooterMetadataFromNestedTaskAndReplyAliases() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "thread-1",
                title = "对话流专修",
                sourceThreadId = "channel-1",
                status = "loading"
            )
        val payload =
            buildSharedThreadPayloadFromMessagesResponse(
                basePayload = base,
                session = null,
                bodyText =
                    """
                    {
                      "data": {
                        "messages": [
                          {
                            "id": "m1",
                            "authorId": "u1",
                            "authorName": "Artin",
                            "body": "please check this",
                            "replies": 4,
                            "task": {
                              "id": "task-193",
                              "number": 193,
                              "status": "Todo",
                              "assignee": {
                                "displayName": "Android-Developer-2"
                              }
                            }
                          },
                          {
                            "id": "m2",
                            "authorId": "u2",
                            "authorName": "Cindy",
                            "body": "nested replies",
                            "replyThread": {
                              "count": 3
                            }
                          }
                        ]
                      }
                    }
                    """.trimIndent()
            )

        val message = payload.messages.first()
        assertEquals("task-193", message.taskId)
        assertEquals(193, message.taskNumber)
        assertEquals("Todo", message.taskStatus)
        assertEquals("Android-Developer-2", message.taskAssigneeName)
        assertEquals(4, message.replyCount)
        assertEquals(3, payload.messages[1].replyCount)
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
        assertEquals(false, payload.canAddMembers)
        assertEquals(false, payload.showSettingsAction)
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
                          "data": {
                            "attachments": [
                              {
                                "id": "att-1",
                                "fileName": "report.pdf",
                                "mimeType": "application/pdf",
                                "sizeLabel": "12 KB"
                              }
                            ]
                          }
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
