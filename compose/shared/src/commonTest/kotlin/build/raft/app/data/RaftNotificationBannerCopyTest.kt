package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.NotificationNoticeReceived
import build.raft.app.model.NotificationPushReceived
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Banner copy on the mobile socket (slock docs/contracts/notifications.md §1.5 / §4.1, mobileSocket.ts
 * and notificationCopy.ts @ fbc091209976f3d22d58f0cc65e330599c45bccf): `subtitle`, `localization`
 * and the `title · subtitle` the socket still sends.
 */
class RaftNotificationBannerCopyTest {
    @Test
    fun androidDeclaresKeySet3WhileIosStaysAt2() {
        // Android ships every banner key (AndroidNotificationCopyVectorTest); iOS shows an unknown
        // loc-key verbatim and has none of them yet.
        assertEquals(3, RaftPushProviderSpec.Fcm.previewKeySet)
        assertEquals(2, RaftPushProviderSpec.Apns.previewKeySet)
    }

    @Test
    fun theTitleLosesOnlyItsOwnSubtitleSuffix() {
        assertEquals("Thread in #general", raftNotificationTitleWithoutSubtitle("Thread in #general · Acme", "Acme"))
        assertEquals("#12 Fix login", raftNotificationTitleWithoutSubtitle("#12 Fix login · #general · Acme", "#general · Acme"))
        // FCM to key set 3 already sends the plain title.
        assertEquals("#general", raftNotificationTitleWithoutSubtitle("#general", "Acme"))
        // No subtitle, another suffix, or a title that is only the suffix: unchanged.
        assertEquals("#general · Acme", raftNotificationTitleWithoutSubtitle("#general · Acme", null))
        assertEquals("#general · Acme", raftNotificationTitleWithoutSubtitle("#general · Acme", " "))
        assertEquals("#general · Acme", raftNotificationTitleWithoutSubtitle("#general · Acme", "Other"))
        assertEquals(" · Acme", raftNotificationTitleWithoutSubtitle(" · Acme", "Acme"))
    }

    @Test
    fun aSocketPushCarriesItsSubtitleAndLocalization() {
        val push =
            assertIs<NotificationPushReceived>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.NotificationPush,
                    """
                    {
                      "title": "Thread in #general · Acme",
                      "subtitle": "Acme",
                      "body": "Alice: Sent 2 attachments",
                      "tag": "n-1",
                      "url": "https://app.raft.build/s/acme/c/thread-1",
                      "serverName": "Acme",
                      "channelName": "general",
                      "parentChannelKind": "channel",
                      "senderId": "user-alice",
                      "senderType": "user",
                      "senderName": "Alice",
                      "mentioned": false,
                      "serverId": "server-1",
                      "kind": "thread",
                      "channelId": "thread-1",
                      "threadId": "thread-1",
                      "parentChannelId": "general-id",
                      "parentMessageId": "parent-1",
                      "messageId": "m-1",
                      "id": "n-1",
                      "category": "thread",
                      "priority": "normal",
                      "seq": 7,
                      "groupKey": "server-1:thread-1",
                      "conversationId": "thread-1",
                      "route": null,
                      "routeUrl": "/s/acme/c/thread-1",
                      "silent": false,
                      "previewMode": "full",
                      "localization": {
                        "titleKey": "push.title.thread_in_channel",
                        "titleArgs": ["general"],
                        "bodyKey": "push.body.sender_attachments",
                        "bodyArgs": ["Alice", "2"]
                      }
                    }
                    """.trimIndent()
                )
            )
        assertEquals("Acme", push.subtitle)
        assertEquals(
            RaftNotificationLocalization(
                titleKey = "push.title.thread_in_channel",
                titleArgs = listOf("general"),
                bodyKey = "push.body.sender_attachments",
                bodyArgs = listOf("Alice", "2")
            ),
            push.localization
        )
        val request = buildSystemUnreadNotificationRequestFromPush(push, isVisibleThread = false, isAppInForeground = false)!!
        assertEquals("Acme", request.subtitle)
        assertEquals(push.localization, request.localization)
    }

    @Test
    fun aSocketNoticeCarriesItsSubtitleAndLocalization() {
        val notice =
            assertIs<NotificationNoticeReceived>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.NotificationPush,
                    """
                    {
                      "serverId": "server-1", "kind": null, "channelId": null, "messageId": null,
                      "category": "test", "groupKey": "server-1:test", "id": "t-1",
                      "title": "Raft test notification · Acme", "subtitle": "Acme",
                      "body": "If you can read this, notifications reach this device.",
                      "tag": "t-1", "silent": false,
                      "localization": { "titleKey": "push.title.test", "bodyKey": "push.body.test", "bodyArgs": [] }
                    }
                    """.trimIndent()
                )
            )
        assertEquals("Acme", notice.subtitle)
        assertEquals("push.title.test", notice.localization?.titleKey)
        assertEquals("push.body.test", notice.localization?.bodyKey)
    }

    @Test
    fun malformedLocalizationPartsAreDroppedLikeTheServerParser() {
        fun parse(json: String) = parseRaftNotificationLocalizationObject(Json.parseToJsonElement(json).jsonObject)
        assertNull(parse("""{}"""))
        assertNull(parse("""{"titleKey": "", "bodyKey": 3}"""))
        assertEquals(
            RaftNotificationLocalization(subtitleKey = "push.subtitle.dm_server", subtitleArgs = emptyList()),
            parse("""{"subtitleKey": "push.subtitle.dm_server", "subtitleArgs": ["Acme", 2]}""")
        )
        assertEquals(
            RaftNotificationLocalization(bodyKey = "push.body.no_text"),
            parse("""{"bodyKey": "push.body.no_text", "titleArgs": ["ignored without a key"]}""")
        )
    }
}
