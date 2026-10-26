package build.raft.app

import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.WireForwardMessageBundle
import build.raft.app.data.WireForwardMessageItem
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.parseSharedChannelMessages
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ForwardMessageParserTest {
    @Test
    fun messagesParserPreservesMergedForwardBundle() {
        val payload = parseSharedChannelMessages(
            bodyText = """
                {
                  "messages": [
                    {
                      "id": "forward-msg-1",
                      "channelId": "channel-1",
                      "senderType": "user",
                      "senderId": "user-1",
                      "senderName": "Ada",
                      "messageType": "chat",
                      "content": "",
                      "createdAt": "2026-07-06T13:00:00.000Z",
                      "forwardBundle": {
                        "title": "Group chat history",
                        "sourceTitle": "Design review",
                        "totalCount": 2,
                        "messages": [
                          {
                            "messageId": "m1",
                            "senderId": "user-2",
                            "senderName": "jor",
                            "senderType": "user",
                            "content": "我不是运维 无所谓了",
                            "createdAt": "2026-07-06T12:58:00.000Z"
                          },
                          {
                            "messageId": "m2",
                            "senderId": "user-2",
                            "senderName": "jor",
                            "senderType": "user",
                            "content": "反正老板目前是不限量使用",
                            "createdAt": "2026-07-06T12:59:00.000Z"
                          }
                        ]
                      }
                    }
                  ],
                  "historyLimited": false
                }
            """.trimIndent(),
            fallbackThreadId = ThreadId("channel-1")
        )

        val bundle = assertNotNull(payload.messages.single().forwardBundle)
        assertEquals("Group chat history", bundle.title)
        assertEquals("Design review", bundle.sourceTitle)
        assertEquals(2, bundle.totalCount)
        assertEquals("m1", bundle.messages.first().id)
        assertEquals("jor", bundle.messages.first().authorName)
        assertEquals("我不是运维 无所谓了", bundle.messages.first().body)
    }

    @Test
    fun messagesParserPreservesStagingForwardedBundleActionMetadata() {
        val payload = parseSharedChannelMessages(
            bodyText = """
                {
                  "messages": [
                    {
                      "id": "forward-msg-1",
                      "channelId": "channel-1",
                      "senderType": "user",
                      "senderId": "user-1",
                      "senderName": "Ada",
                      "messageType": "chat",
                      "content": "Forwarded 2 messages",
                      "createdAt": "2026-07-06T13:00:00.000Z",
                      "actionMetadata": {
                        "kind": "forwarded-bundle",
                        "version": 1,
                        "forwardedItems": [
                          {
                            "index": 0,
                            "sourceMessageId": "m1",
                            "sourceAuthorSnapshot": {
                              "type": "user",
                              "id": "user-2",
                              "name": "Jor",
                              "uniqueName": "jor"
                            },
                            "sourceCreatedAt": "2026-07-06T12:58:00.000Z",
                            "sourceTargetSnapshot": {
                              "id": "channel-1",
                              "type": "channel",
                              "label": "#general",
                              "labelVisibility": "public"
                            },
                            "contentSnapshot": "我不是运维 无所谓了",
                            "attachmentSnapshots": [
                              { "filename": "note.txt", "mimeType": "text/plain" }
                            ],
                            "provenanceState": "available"
                          },
                          {
                            "index": 1,
                            "sourceMessageId": "m2",
                            "sourceAuthorSnapshot": {
                              "type": "agent",
                              "id": "agent-1",
                              "name": "Build Bot"
                            },
                            "sourceCreatedAt": "2026-07-06T12:59:00.000Z",
                            "sourceTargetSnapshot": {
                              "id": "channel-1",
                              "type": "channel",
                              "label": "#general",
                              "labelVisibility": "public"
                            },
                            "contentSnapshot": "反正老板目前是不限量使用"
                          }
                        ]
                      }
                    }
                  ],
                  "historyLimited": false
                }
            """.trimIndent(),
            fallbackThreadId = ThreadId("channel-1")
        )

        val bundle = assertNotNull(payload.messages.single().forwardBundle)
        assertEquals("Forwarded", bundle.title)
        assertEquals("#general", bundle.sourceTitle)
        assertEquals(2, bundle.totalCount)
        assertEquals("m1", bundle.messages.first().id)
        assertEquals("@jor", bundle.messages.first().authorName)
        assertEquals("我不是运维 无所谓了", bundle.messages.first().body)
        assertEquals(1, bundle.messages.first().attachmentCount)
    }

    @Test
    fun forwardedBundleOrdersItemsAndOpensTheirSourceLikeTheWeb() {
        val payload = parseSharedChannelMessages(
            bodyText = """
                {
                  "messages": [
                    {
                      "id": "forward-msg-2",
                      "channelId": "channel-9",
                      "senderType": "user",
                      "senderId": "user-1",
                      "senderName": "Ada",
                      "messageType": "chat",
                      "content": "",
                      "createdAt": "2026-07-06T13:00:00.000Z",
                      "actionMetadata": {
                        "kind": "forwarded-bundle",
                        "version": 1,
                        "forwardedItems": [
                          {
                            "index": 0,
                            "sourceMessageId": "m-late",
                            "sourceTargetId": "channel-1",
                            "sourceAuthorSnapshot": { "type": "user", "id": "user-2", "name": "Jor" },
                            "sourceCreatedAt": "2026-07-06T12:59:00.000Z",
                            "sourceTargetSnapshot": { "id": "channel-1", "type": "channel", "label": "#general", "labelVisibility": "public" },
                            "contentSnapshot": "later",
                            "provenanceState": "available"
                          },
                          {
                            "index": 1,
                            "sourceMessageId": "m-early",
                            "sourceTargetId": "channel-1",
                            "sourceAuthorSnapshot": { "type": "user", "id": "user-3", "name": "Kim" },
                            "sourceCreatedAt": "2026-07-06T12:58:00.000Z",
                            "sourceTargetSnapshot": { "id": "channel-1", "type": "channel", "label": "#general", "labelVisibility": "public" },
                            "contentSnapshot": "earlier",
                            "attachmentPolicy": "projected",
                            "attachmentSnapshots": [
                              { "id": "att-1", "filename": "shot.png", "mimeType": "image/png", "width": 640, "height": 480 }
                            ],
                            "provenanceState": "available"
                          }
                        ]
                      }
                    }
                  ],
                  "historyLimited": false
                }
            """.trimIndent(),
            fallbackThreadId = ThreadId("channel-9")
        )

        val bundle = assertNotNull(payload.messages.single().forwardBundle)
        assertEquals(listOf("m-early", "m-late"), bundle.messages.map { it.id })
        assertEquals("Kim", bundle.messages.first().authorName)
        assertEquals(true, bundle.messages.first().attachmentsProjected)
        assertEquals("att-1", bundle.messages.first().attachments.single().id)
        val source = assertNotNull(bundle.visibleSource)
        assertEquals("#general", source.label)
        assertEquals("channel-1", source.channelId)
        assertEquals("m-early", source.messageId)
        assertEquals(
            "https://raft.test/s/acme/channel/channel-1?msg=m-early",
            source.forwardSourcePermalink(serverSlug = "acme", webBaseUrl = "https://raft.test/")
        )
        assertNull(source.forwardSourcePermalink(serverSlug = null, webBaseUrl = "https://raft.test"))
    }

    @Test
    fun threadProjectionKeepsForwardBundleOnRenderedMessage() {
        val page = ThreadMessagesPage(
            messages = listOf(
                WireMessage(
                    id = "forward-msg-1",
                    channelId = "channel-1",
                    senderType = "user",
                    senderId = "user-1",
                    senderName = "Ada",
                    messageType = "chat",
                    content = "",
                    createdAt = "2026-07-06T13:00:00.000Z",
                    forwardBundle =
                        WireForwardMessageBundle(
                            title = "Group chat history",
                            totalCount = 1,
                            messages =
                                listOf(
                                    WireForwardMessageItem(
                                        messageId = "m1",
                                        senderId = "user-2",
                                        senderName = "alice",
                                        content = "周末一起去爬山吗？"
                                    )
                                )
                        )
                )
            ),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false
        )

        val rendered = buildSharedThreadPayloadFromMessagesPage(
            basePayload =
                RaftThreadScreenPayload(
                    instanceId = "thread-channel-1",
                    sourceThreadId = "channel-1",
                    status = "loading"
                ),
            page = page,
            session = null
        )

        val bundle = assertNotNull(rendered.messages.single().forwardBundle)
        assertEquals("Group chat history", bundle.title)
        assertEquals(1, bundle.messages.size)
        assertEquals("周末一起去爬山吗？", bundle.messages.single().body)
    }
}
