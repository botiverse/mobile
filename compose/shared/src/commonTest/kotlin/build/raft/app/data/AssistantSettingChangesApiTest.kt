package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET servers/:id/assistant/setting-changes` (web `components/settings/assistantSettingChangesData.ts`
 * `AssistantSettingChange`, `readChanges`): `{ changes: [...] }`, newest first, values
 * `string | boolean | null`.
 */
class AssistantSettingChangesApiTest {
    @Test
    fun readsEveryField() {
        val changes =
            parseAssistantSettingChanges(
                """
                {"changes":[{
                  "id":"c1","assistantName":"Ada","setting":"theme",
                  "serverId":"s2","serverName":"Design","targetRef":null,"confirmed":true,
                  "previousValue":"raft","nextValue":"space","createdAt":"2026-11-17T09:14:00.000Z"
                }]}
                """.trimIndent()
            )
        assertEquals(
            listOf(
                AssistantSettingChange(
                    id = "c1",
                    assistantName = "Ada",
                    setting = AssistantChangedSetting.Theme,
                    serverReported = true,
                    serverId = "s2",
                    serverName = "Design",
                    targetRef = null,
                    confirmed = true,
                    previousValue = AssistantSettingValue.Text("raft"),
                    nextValue = AssistantSettingValue.Text("space"),
                    createdAt = "2026-11-17T09:14:00.000Z",
                )
            ),
            changes,
        )
    }

    @Test
    fun booleanAndNullValues() {
        val change =
            parseAssistantSettingChanges(
                """
                {"changes":[{"id":"c1","assistantName":null,"setting":"themeDecor",
                  "previousValue":null,"nextValue":true,"createdAt":"2026-11-17T09:14:00Z"}]}
                """.trimIndent()
            ).single()
        assertNull(change.previousValue)
        assertEquals(AssistantSettingValue.Flag(true), change.nextValue)
        assertNull(change.assistantName)

        val unmuted =
            parseAssistantSettingChanges(
                """{"changes":[{"id":"c2","setting":"conversationMute","targetRef":"#design","previousValue":true,"nextValue":false,"createdAt":"2026-11-17T09:14:00Z"}]}"""
            ).single()
        assertEquals(AssistantSettingValue.Flag(true), unmuted.previousValue)
        assertEquals(AssistantSettingValue.Flag(false), unmuted.nextValue)
        assertEquals("#design", unmuted.targetRef)
    }

    @Test
    fun anUnknownSettingIsLeftOutAndTheRestStays() {
        val changes =
            parseAssistantSettingChanges(
                """
                {"changes":[
                  {"id":"c1","setting":"somethingNew","previousValue":1,"nextValue":{"a":1},"createdAt":"2026-11-17T09:14:00Z"},
                  {"id":"c2","setting":"sidebarArrange","previousValue":null,"nextValue":null,"createdAt":"2026-11-17T09:10:00Z"},
                  {"setting":"theme","previousValue":null,"nextValue":"space","createdAt":"2026-11-17T09:00:00Z"},
                  {"id":"c4","setting":"theme","previousValue":null,"nextValue":"space"},
                  "not an object"
                ]}
                """.trimIndent()
            )
        assertEquals(listOf("c2"), changes.map { it.id })
        assertEquals(AssistantChangedSetting.SidebarArrange, changes.single().setting)
    }

    @Test
    fun serverIdAbsentNullOrPresent() {
        val changes =
            parseAssistantSettingChanges(
                """
                {"changes":[
                  {"id":"absent","setting":"displayLanguage","previousValue":"en","nextValue":"ja","createdAt":"2026-11-17T09:14:00Z"},
                  {"id":"gone","setting":"displayLanguage","serverId":null,"serverName":null,"previousValue":"en","nextValue":"ja","createdAt":"2026-11-17T09:14:00Z"},
                  {"id":"here","setting":"displayLanguage","serverId":"s1","previousValue":"en","nextValue":"ja","createdAt":"2026-11-17T09:14:00Z"},
                  {"id":"other","setting":"displayLanguage","serverId":"s2","serverName":"Design","previousValue":"en","nextValue":"ja","createdAt":"2026-11-17T09:14:00Z"}
                ]}
                """.trimIndent()
            ).associateBy { it.id }
        assertFalse(changes.getValue("absent").inOtherServer("s1"))
        assertTrue(changes.getValue("gone").inOtherServer("s1"))
        assertFalse(changes.getValue("here").inOtherServer("s1"))
        assertTrue(changes.getValue("other").inOtherServer("s1"))
    }

    @Test
    fun anUnreadableBodyIsNoChanges() {
        assertEquals(emptyList(), parseAssistantSettingChanges(""))
        assertEquals(emptyList(), parseAssistantSettingChanges("[]"))
        assertEquals(emptyList(), parseAssistantSettingChanges("""{"changes":null}"""))
        assertEquals(emptyList(), parseAssistantSettingChanges("""{"error":"assistant_not_enabled"}"""))
        assertNull(assistantSettingChangesResultOf(403, """{"error":"assistant_not_enabled"}"""))
        assertNull(assistantSettingChangesResultOf(200, null))
    }

    @Test
    fun eachPageShowsItsOwnSettings() {
        val all =
            AssistantChangedSetting.entries.mapIndexed { index, setting ->
                AssistantSettingChange(id = "c$index", assistantName = null, setting = setting, createdAt = "2026-11-17T09:14:00Z")
            }
        assertEquals(
            listOf(AssistantChangedSetting.DisplayLanguage, AssistantChangedSetting.PreferredTimezone),
            all.ofSettings(ASSISTANT_LANGUAGE_REGION_SETTINGS).map { it.setting },
        )
        assertEquals(
            listOf(
                AssistantChangedSetting.Theme,
                AssistantChangedSetting.ThemeDecor,
                AssistantChangedSetting.SidebarMove,
                AssistantChangedSetting.SidebarPin,
                AssistantChangedSetting.SidebarArrange,
            ),
            all.ofSettings(ASSISTANT_THEME_SETTINGS).map { it.setting },
        )
        assertEquals(
            listOf(AssistantChangedSetting.ConversationMute, AssistantChangedSetting.NotificationLevel),
            all.ofSettings(ASSISTANT_NOTIFICATION_SETTINGS).map { it.setting },
        )
        assertEquals(
            listOf(AssistantChangedSetting.AgentMove, AssistantChangedSetting.MessageSave),
            all.ofSettings(ASSISTANT_ACTION_SETTINGS).map { it.setting },
        )
    }
}
