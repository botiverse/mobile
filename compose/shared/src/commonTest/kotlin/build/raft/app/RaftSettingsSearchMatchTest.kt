package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings search's matching and order, the web's (packages/web/src/features/settings/search/
 * settingsSearchMatch.ts and its settingsSearchMatch.test.ts): names, then other words, then
 * descriptions, then page and section; within each, the list's order.
 */
class RaftSettingsSearchMatchTest {
    private fun setting(
        id: String,
        name: String,
        description: String? = null,
        page: String = "Appearance",
        section: String? = null,
        aliases: List<String> = emptyList(),
    ) = RaftSearchableSetting(id, rowKey = "appearance", webPage = "appearance", name = name, description = description, pageLabel = page, sectionLabel = section, aliases = aliases)

    private val settings =
        listOf(
            setting("message-font-size", "Message font size", "Adjust rendered messages on this device", aliases = listOf("font", "text size")),
            setting("send-with", "Send messages with", page = "Appearance", section = "Message box", aliases = listOf("enter", "return", "send key")),
            setting("display-name", "Display name", page = "Account", section = "Account", aliases = listOf("name", "nickname")),
            setting("server-name", "Name", page = "Server", section = "Profile", aliases = listOf("rename")),
        )

    private fun search(query: String) = searchSettings(settings, query).map { it.setting.id to it.field }

    @Test
    fun namesComeFirstThenOtherWordsThenDescriptionsThenWhereItIs() {
        // "name": two names have it (list order), then an alias.
        assertEquals(
            listOf(
                "display-name" to RaftSettingsSearchField.Name,
                "server-name" to RaftSettingsSearchField.Name,
            ),
            search("name").take(2),
        )
        assertEquals(listOf("send-with" to RaftSettingsSearchField.Alias), search("return"))
        assertEquals(listOf("message-font-size" to RaftSettingsSearchField.Description), search("rendered"))
        assertEquals(listOf("send-with" to RaftSettingsSearchField.Where), search("message box"))
        // A name match outranks an earlier setting's alias or description.
        assertEquals(
            listOf("send-with" to RaftSettingsSearchField.Name, "message-font-size" to RaftSettingsSearchField.Description),
            search("messages"),
        )
    }

    @Test
    fun caseWhiteSpaceAndFullWidthLettersDoNotMatter() {
        assertEquals(search("enter"), search("  ENTER "))
        assertEquals(search("enter"), search("Ｅｎｔｅｒ"))
        assertEquals(search("message box"), search("message　  box"))
        assertEquals("send key", normalizeSettingsSearchText(" Send\tＫＥＹ "))
    }

    @Test
    fun anEmptyQueryFindsNothingAndAnUnknownWordNothingEither() {
        assertTrue(search("").isEmpty())
        assertTrue(search("   ").isEmpty())
        assertTrue(search("launch").isEmpty())
    }

    @Test
    fun theSecondLineIsPageSectionAndTheWordItWasFoundBy() {
        val byAlias = searchSettings(settings, "return").single()
        assertEquals("return", byAlias.alias)
        assertEquals("Appearance · Message box · return", byAlias.subtitle)
        assertEquals("Appearance", searchSettings(settings, "font size").single().subtitle)
    }

    @Test
    fun otherWordsAreTheDisplayLanguagesThenEnglishEachOnce() {
        val aliases = mapOf("en" to listOf("push", "notification"), "zh-cn" to listOf("推送", "通知", "push"))
        assertEquals(listOf("推送", "通知", "push", "notification"), settingsSearchAliases(aliases, "zh-cn"))
        assertEquals(listOf("push", "notification"), settingsSearchAliases(aliases, "en"))
        // A locale without its own list searches English.
        assertEquals(listOf("push", "notification"), settingsSearchAliases(aliases, "ja"))
    }

    @Test
    fun appLanguageTagsMapToTheIndexsLocales() {
        assertEquals("zh-cn", settingsSearchAliasLocale("zh-CN"))
        assertEquals("zh-cn", settingsSearchAliasLocale("zh-Hans-CN"))
        assertEquals("zh-tw", settingsSearchAliasLocale("zh-TW"))
        assertEquals("zh-tw", settingsSearchAliasLocale("zh_Hant"))
        assertEquals("pt-br", settingsSearchAliasLocale("pt-BR"))
        assertEquals("ja", settingsSearchAliasLocale("ja-JP"))
        assertEquals("en", settingsSearchAliasLocale("en-US"))
        assertEquals("en", settingsSearchAliasLocale(""))
    }
}
