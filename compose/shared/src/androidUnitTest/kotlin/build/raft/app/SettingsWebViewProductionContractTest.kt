package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsWebViewProductionContractTest {
    @Test
    fun settingsAndReleaseNotesKeepOneNativeWebViewRenderer() {
        val sources =
            listOf(
                "SettingsStandaloneWebViewHost.kt",
                "SettingsComputersPage.kt",
                "SettingsBillingPage.kt",
                "SettingsMarketplacePage.kt",
                "SettingsAdministrationPage.kt",
                "ReleaseNotesPage.kt",
            ).associateWith(::source)

        assertEquals(
            1,
            sources.values.sumOf { it.count("SlockWebView(") },
            "Settings and Release Notes must not introduce a second native WebView renderer",
        )
        assertEquals(1, sources.getValue("SettingsStandaloneWebViewHost.kt").count("SlockWebView("))
    }

    @Test
    fun authenticatedSettingsSurfacesKeepOneProductionPage() {
        val sources =
            commonMain()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .joinToString("\n") { it.readText() }
        val host = source("SettingsStandaloneWebViewHost.kt")
        val surfaceSources =
            listOf(
                host,
                source("SettingsComputersPage.kt"),
                source("SettingsBillingPage.kt"),
                source("SettingsMarketplacePage.kt"),
                source("SettingsAdministrationPage.kt"),
                source("ReleaseNotesPage.kt"),
            )

        assertContains(host, "@Page(SETTINGS_WEB_VIEW_PAGE, supportInLocal = true)")
        assertEquals(1, sources.count("@Page(SETTINGS_WEB_VIEW_PAGE"))
        assertEquals(
            1,
            surfaceSources.sumOf { it.count("@Page(") },
            "Only the shared Settings WebView @Page may own these surfaces",
        )
        listOf(
            "compose_settings_computers",
            "compose_settings_billing",
            "compose_settings_marketplace",
            "compose_settings_administration",
            "compose_release_notes",
        ).forEach { legacyPage ->
            assertFalse(legacyPage in sources, "Legacy Settings WebView page survived: $legacyPage")
        }
    }

    @Test
    fun sharedScaffoldOwnsBothHeaderAndSystemBackDispatch() {
        val host = source("SettingsStandaloneWebViewHost.kt")

        assertEquals(1, host.count("SlockBackHandler("))
        assertEquals(1, host.count("onClick = ::goBackOrClose"))
        assertEquals(2, host.count("performSettingsWebViewBack("))
        assertContains(host, "onBack = ::goBackOrClose")
    }

    private fun source(fileName: String): String {
        val file = File(commonMain(), "build/raft/app/$fileName")
        assertTrue(file.isFile, "Missing production source $fileName")
        return file.readText().replace("\r\n", "\n")
    }

    private fun commonMain(): File =
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate shared commonMain Kotlin sources")

    private fun String.count(needle: String): Int = split(needle).size - 1
}
