package build.raft.app

import build.raft.app.data.AppLocale
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.format.RaftFormatLocale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #proj-workspace:#388 (huxijin): the Artifact page's ⋯ menu and "Linked from" are drawn by the embedded
 * web page, which follows `?lang=` (web resolveLangParamLocale) and otherwise the WebView's languages.
 */
class RaftEmbeddedWebLangTest {
    @AfterTest
    fun resetLocale() {
        KmmResourceLocaleRuntime.replaceForTest(null)
    }

    @Test
    fun theAppsUiLanguageGoesAsTheWebsDisplayLocaleCode() {
        KmmResourceLocaleRuntime.replaceForTest(AppLocale.SimplifiedChinese)
        assertEquals("zh-cn", raftEmbeddedWebLangTag())
        KmmResourceLocaleRuntime.replaceForTest(AppLocale.BrazilianPortuguese)
        assertEquals("pt-br", raftEmbeddedWebLangTag())
        // Every App language is one of the web's display-locale codes (slock displayLocales.ts).
        assertEquals(
            listOf("en", "es", "pt-br", "ru", "ko", "ja", "zh-cn", "zh-tw"),
            RaftFormatLocale.entries.map { raftEmbeddedWebLangTag(it) },
        )
    }

    @Test
    fun langIsAddedOrReplacedAndEverythingElseStays() {
        assertEquals(
            "https://app.raft.build/settings/apps?lang=zh-cn",
            raftWebUrlWithLang("https://app.raft.build/settings/apps", "zh-cn"),
        )
        assertEquals(
            "https://app.raft.build/s/acme/settings?embed=raft-settings-v1&shell=host&lang=ja",
            raftWebUrlWithLang("https://app.raft.build/s/acme/settings?embed=raft-settings-v1&lang=en&shell=host", "ja"),
        )
        assertEquals(
            "https://app.raft.build/artifacts/a1?x=1&lang=ko#panel=comments",
            raftWebUrlWithLang("https://app.raft.build/artifacts/a1?x=1#panel=comments", "ko"),
        )
        // No usable tag: the address is left alone.
        val url = "https://app.raft.build/settings/apps?x=1"
        assertEquals(url, raftWebUrlWithLang(url, null))
        assertEquals(url, raftWebUrlWithLang(url, "  "))
        assertEquals(url, raftWebUrlWithLang(url, "zh cn"))
    }

    @Test
    fun anArtifactOpensInTheAppsLanguage() {
        val web = "https://app.raft.build"
        val shown = raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget("pt_a1", 3), langTag = "zh-cn")
        assertTrue(shown.contains("/artifacts/pt_a1/v/3?embed="), shown)
        assertTrue(shown.endsWith("&lang=zh-cn"), shown)
        // A language already in the address is replaced, never doubled.
        val relinked = raftArtifactViewerEmbeddedUrl("$web/artifacts/pt_a1?lang=en&x=1", langTag = "ko")
        assertEquals(1, Regex("[?&]lang=").findAll(relinked).count(), relinked)
        assertTrue(relinked.contains("?x=1&embed="), relinked)
        assertTrue(relinked.endsWith("&lang=ko"), relinked)
        // The warm document boots in it too, so an Artifact routed into it later keeps it.
        assertTrue(raftArtifactViewerWarmUrl(web, langTag = "ja").endsWith("&lang=ja"))
        // Without a language the contract is as before.
        assertFalse(raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget("pt_a1")).contains("lang="))
    }
}
