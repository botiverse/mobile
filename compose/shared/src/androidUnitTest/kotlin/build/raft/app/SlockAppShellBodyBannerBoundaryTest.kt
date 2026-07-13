package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mechanical gate for app-shell alerts that must render below page chrome. */
class SlockAppShellBodyBannerBoundaryTest {

    @Test
    fun shellProvidesBannerToPageBodyInsteadOfRenderingAboveContent() {
        val source = source("build/raft/app/SlockAppShell.kt")

        assertTrue(
            "CompositionLocalProvider(LocalSlockAppShellBodyTopBanner provides bodyTopBanner)" in source,
            "The app shell must provide global alerts to page-owned body slots.",
        )
        assertFalse(
            Regex("if \\(networkStatus == SlockAppShellNetworkStatus\\.Offline\\) \\{\\s*SlockAppShellNetworkBanner\\(\\)")
                .containsMatchIn(source),
            "The network banner must not be mounted in the shell root before content/header chrome.",
        )
    }

    @Test
    fun everyAppShellHeaderPatternConsumesTheBodyBannerSlot() {
        val expectedCallCounts =
            mapOf(
                "build/raft/app/SlockSectionHeader.kt" to 1,
                "build/raft/app/SlockHomeComponents.kt" to 1,
                "build/raft/app/SharedInboxPanel.kt" to 1,
                "build/raft/app/SettingsHomePage.kt" to 3,
            )

        expectedCallCounts.forEach { (path, expectedCount) ->
            assertEquals(
                expectedCount,
                source(path).split("SlockAppShellBodyTopBanner()").size - 1,
                "$path must consume the app-shell body alert immediately after each standard/custom header pattern.",
            )
        }

        val discoveredConsumers =
            commonMain
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { "SlockAppShellBodyTopBanner()" in it.readText() }
                .map { it.relativeTo(commonMain).invariantSeparatorsPath }
                .filterNot { it == "build/raft/app/SlockAppShell.kt" }
                .toSet()

        assertEquals(
            expectedCallCounts.keys,
            discoveredConsumers,
            "New app-shell header patterns must explicitly join the below-header body-banner contract.",
        )
    }

    private val commonMain: File
        get() = requireNotNull(
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory),
        ) { "Could not locate shared commonMain Kotlin sources" }

    private fun source(path: String): String {
        val file = File(commonMain, path)
        assertTrue(file.isFile, "Missing production source $path")
        return file.readText().replace("\r\n", "\n")
    }
}
