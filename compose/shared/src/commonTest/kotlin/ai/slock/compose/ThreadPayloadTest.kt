package ai.slock.compose

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
        assertEquals("message", payload.originStatus)
        assertEquals("m-parent", payload.originParentMessageId)
        assertEquals("hello from parent", payload.originMessage?.body)
    }
}
