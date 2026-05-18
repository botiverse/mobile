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
                      "members": [
                        {"id": "u1", "displayName": "Artin", "handle": "artin", "roleLabel": "Owner"},
                        {"id": "a1", "displayName": "Android Bot", "kind": "agent", "avatarLabel": "AB"}
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
        assertEquals("Artin", payload.members.first().displayName)
        assertEquals("agent", payload.members[1].kind)
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
}
