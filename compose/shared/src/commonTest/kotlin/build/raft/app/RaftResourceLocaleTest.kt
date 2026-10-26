package build.raft.app

import build.raft.app.resources.RaftResourceLocale
import build.raft.app.resources.StringDesc
import build.raft.app.resources.raftResourceLocale
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftResourceLocaleTest {
    private val english = RaftResourceLocale("en", "")
    private val japanese = RaftResourceLocale("ja", "")
    private val simplifiedChinese = RaftResourceLocale("zh", "CN")

    private fun system(vararg tags: String) = raftResourceLocale(StringDesc.LocaleType.System, tags.toList())

    @Test
    fun explicitAppLanguagesPickTheirCatalog() {
        listOf("en" to english, "ja" to japanese, "zh-CN" to simplifiedChinese).forEach { (tag, expected) ->
            assertEquals(expected, raftResourceLocale(StringDesc.LocaleType.Custom(tag), listOf("fr-FR")), tag)
        }
    }

    @Test
    fun systemLanguagePicksTheFirstPreferredLanguageWithACatalog() {
        mapOf(
            listOf("ja-JP") to japanese,
            listOf("zh-CN") to simplifiedChinese,
            listOf("zh-Hans-US") to simplifiedChinese,
            listOf("zh_SG") to simplifiedChinese,
            listOf("en-US", "ja-JP") to english,
            listOf("fr", "ja") to japanese,
            listOf("fr-FR", "de-DE") to english,
            emptyList<String>() to english,
        ).forEach { (tags, expected) -> assertEquals(expected, system(*tags.toTypedArray()), "$tags") }
    }

    @Test
    fun traditionalChineseIsSkippedLikeAnyUnsupportedLanguage() {
        mapOf(
            listOf("zh-Hant-TW") to english,
            listOf("zh-TW") to english,
            listOf("zh-HK", "ja-JP") to japanese,
            listOf("zh-Hant", "zh-Hans") to simplifiedChinese,
        ).forEach { (tags, expected) -> assertEquals(expected, system(*tags.toTypedArray()), "$tags") }
    }
}
