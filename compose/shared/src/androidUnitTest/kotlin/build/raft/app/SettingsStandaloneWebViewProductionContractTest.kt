package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keeps the Web-owned Settings surfaces from accidentally regaining a native content load.
 *
 * This is a production-wiring check rather than a WebView rendering test: the embedded SPA is
 * the content authority, while native Account/Storage/Release Notes pages still use the shared
 * settings store in their own hosts.
 */
class SettingsStandaloneWebViewProductionContractTest {
    @Test
    fun webViewHostOnlyResolvesShellAuthAndEnvironment() {
        val source = repositoryFile(
            "compose/shared/src/commonMain/kotlin/build/raft/app/SettingsStandaloneWebViewHost.kt"
        ).readText()

        assertTrue(source.contains("rememberSettingsWebViewAuthSnapshot(authStore, sharedStores.authStore)"))
        assertTrue(source.contains("standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)"))
        assertFalse(source.contains("SharedSettingsViewModel"))
        assertFalse(source.contains("SharedSettingsIntent.LoadPageContent"))
        assertFalse(source.contains("rememberStandaloneSettingsEnvironment(pager, bridge)"))
    }

    @Test
    fun webSurfaceSpecsDoNotDeclareNativeContentDestinations() {
        val sourcePaths = listOf(
            "SettingsBillingPage.kt",
            "SettingsMarketplacePage.kt",
            "SettingsComputersPage.kt",
            "SettingsAdministrationPage.kt",
        )

        sourcePaths.forEach { fileName ->
            val source = repositoryFile(
                "compose/shared/src/commonMain/kotlin/build/raft/app/$fileName"
            ).readText()
            assertFalse(source.contains("destination = SharedSettingsDestination"), fileName)
        }
    }

    @Test
    fun nativeSettingsHostsRetainTheirStoreLoadAndEffectPath() {
        val account = repositoryFile(
            "compose/shared/src/commonMain/kotlin/build/raft/app/SettingsAccountPage.kt"
        ).readText()
        val storage = repositoryFile(
            "compose/shared/src/commonMain/kotlin/build/raft/app/SettingsStoragePage.kt"
        ).readText()
        val releaseNotes = repositoryFile(
            "compose/shared/src/commonMain/kotlin/build/raft/app/ReleaseNotesPage.kt"
        ).readText()

        listOf(account, storage, releaseNotes).forEach { source ->
            assertTrue(source.contains("SharedSettingsViewModel"))
            assertTrue(source.contains("SharedSettingsIntent.LoadPageContent"))
            assertTrue(source.contains("StandaloneSettingsEffectHandler"))
        }
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Unable to resolve $relativePath from ${start.absolutePath}")
    }
}
