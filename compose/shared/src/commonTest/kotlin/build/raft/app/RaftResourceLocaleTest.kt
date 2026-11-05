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
    private val traditionalChinese = RaftResourceLocale("zh", "TW")
    private val spanish = RaftResourceLocale("es", "")
    private val brazilianPortuguese = RaftResourceLocale("pt", "BR")
    private val korean = RaftResourceLocale("ko", "")
    private val russian = RaftResourceLocale("ru", "")

    private fun system(vararg tags: String) = raftResourceLocale(StringDesc.LocaleType.System, tags.toList())

    @Test
    fun explicitAppLanguagesPickTheirCatalog() {
        listOf(
            "en" to english,
            "ja" to japanese,
            "zh-CN" to simplifiedChinese,
            "zh-TW" to traditionalChinese,
            "es" to spanish,
            "pt-BR" to brazilianPortuguese,
            "ko" to korean,
            "ru" to russian,
        ).forEach { (tag, expected) ->
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

    /** The web's `matchDisplayLocale`: script decides Chinese, the region (or none) the rest. */
    @Test
    fun theFourLaterLanguagesMatchLikeTheWeb() {
        mapOf(
            listOf("zh-Hant-TW") to traditionalChinese,
            listOf("zh-TW") to traditionalChinese,
            listOf("zh-HK", "ja-JP") to traditionalChinese,
            listOf("zh-MO") to traditionalChinese,
            listOf("zh-Hant", "zh-Hans") to traditionalChinese,
            listOf("zh-Hans-TW") to simplifiedChinese,
            listOf("es-MX") to spanish,
            listOf("es_ES") to spanish,
            listOf("pt-BR") to brazilianPortuguese,
            listOf("pt-PT") to brazilianPortuguese,
            listOf("ko-KR") to korean,
            listOf("ru-RU") to russian,
            listOf("ru") to russian,
            listOf("fr-FR", "ru-RU", "ja-JP") to russian,
            listOf("fr-FR", "ko-KR", "ja-JP") to korean,
        ).forEach { (tags, expected) -> assertEquals(expected, system(*tags.toTypedArray()), "$tags") }
    }
}
