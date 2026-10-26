package build.raft.app

import build.raft.app.resources.RaftResourceLocale
import build.raft.app.resources.StringDesc
import build.raft.app.resources.raftResourceLocale
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftResourceLocaleTest {
    private fun system(language: String, script: String = "", region: String = "") =
        raftResourceLocale(StringDesc.LocaleType.System, language, script, region)

    private fun custom(tag: String) = raftResourceLocale(StringDesc.LocaleType.Custom(tag), "fr", "", "FR")

    @Test
    fun explicitAppLanguagesPickTheirCatalog() {
        assertEquals(RaftResourceLocale("en", ""), custom("en"))
        assertEquals(RaftResourceLocale("ja", ""), custom("ja"))
        assertEquals(RaftResourceLocale("zh", "CN"), custom("zh-CN"))
    }

    @Test
    fun systemLanguageMatchesTheShippedCatalogs() {
        assertEquals(RaftResourceLocale("ja", ""), system("ja", region = "JP"))
        assertEquals(RaftResourceLocale("zh", "CN"), system("zh", region = "CN"))
        assertEquals(RaftResourceLocale("zh", "CN"), system("zh", script = "Hans", region = "US"))
        assertEquals(RaftResourceLocale("zh", "CN"), system("zh", region = "SG"))
        assertEquals(RaftResourceLocale("en", ""), system("en", region = "US"))
        assertEquals(RaftResourceLocale("en", ""), system("fr", region = "FR"))
    }

    @Test
    fun traditionalChineseFallsBackToTheBaseCatalog() {
        assertEquals(RaftResourceLocale("en", ""), system("zh", script = "Hant", region = "TW"))
        assertEquals(RaftResourceLocale("en", ""), system("zh", region = "TW"))
        assertEquals(RaftResourceLocale("en", ""), system("zh", region = "HK"))
    }
}
