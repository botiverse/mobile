package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HomeServerDrawerOptInProductionCallsiteTest {
    @Test
    fun disabledPopoverRetainsPaddedHostWhileEnabledDrawerUsesFullScreenHost() {
        val source = homeComponentsSource()
        val overlayBlock =
            source.substring(
                startIndex = source.indexOf("if (serverSwitcherExpanded) {").also { assertFalse(it < 0) },
                endIndex = source.indexOf("if (notificationCenterExpanded", source.indexOf("if (serverSwitcherExpanded) {")).also {
                    assertFalse(it < 0)
                }
            )

        val disabledMarker = "// Preserve the legacy popover boundary when the opt-in is disabled:"
        val enabledBranch = overlayBlock.substringBefore(disabledMarker)
        val disabledBranch = overlayBlock.substringAfter(disabledMarker)

        assertContains(enabledBranch, "if (serverDrawerEnabled)")
        assertContains(enabledBranch, "HomeServerDrawerPanel(")
        assertFalse(enabledBranch.contains("Modifier.padding(bottom = bottomContentPadding)"))
        assertContains(disabledBranch, "modifier = Modifier.padding(bottom = bottomContentPadding)")
        assertContains(disabledBranch, "ServerSwitcherPanel(")
    }

    @Test
    fun appShellOwnsOneNativeDrawerCommandPathAndFiltersDrawerRuntimeFromMainSurface() {
        val source = settingsHomeSource()
        val host =
            source.substring(
                startIndex = source.indexOf("private fun AppShellServerDrawerHost(").also { assertFalse(it < 0) },
                endIndex = source.indexOf("internal fun appShellAuthGateViewModelKey", source.indexOf("private fun AppShellServerDrawerHost(")).also {
                    assertFalse(it < 0)
                }
            )

        assertContains(source, "shellState.withoutServerDrawerRuntimeForAppShellRender()")
        assertContains(source, "AppShellServerDrawerHost(")
        assertContains(host, "MoveableDrawer(")
        assertEquals(1, Regex("drawerState\\.open\\(\\)").findAll(host).count())
        assertEquals(1, Regex("drawerState\\.close\\(\\)").findAll(host).count())
        assertContains(host, "if (!desiredOpen && drawerState.progress <= 0.01f)")
        val openCall = host.indexOf("drawerState.open()")
        val interactivePublication = host.indexOf("interactiveGeneration = generation")
        assertFalse(openCall < 0)
        assertFalse(interactivePublication < 0)
        assertFalse(interactivePublication <= openCall)
        assertFalse(host.contains("delay("))
        assertFalse(host.contains("drawerOpenInFlight"))
        assertFalse(host.contains("fallback"))
    }

    @Test
    fun hostedPanelUsesStoreNormalizedCountProvidersAndRealConversationLayoutForInteractiveMarker() {
        val source = homeComponentsSource()
        val panel =
            source.substring(
                startIndex = source.indexOf("internal fun HomeServerDrawerHostedPanel(").also { assertFalse(it < 0) },
                endIndex = source.indexOf("private fun HomeServerDrawerHostedConversationRow(", source.indexOf("internal fun HomeServerDrawerHostedPanel(")).also {
                    assertFalse(it < 0)
                }
            )

        assertContains(panel, "beyondBoundsItemCount = 2")
        assertContains(panel, "normalizedContent.firstConversationIndex")
        assertContains(panel, "markerGate.claim(generation)")
        assertContains(panel, "normalizedContent.rows.size")
        assertContains(panel, "projector.project(row.conversation)")
        assertFalse(panel.contains("homeServerDrawerRows("))
        assertFalse(panel.contains("sortedForSidebar("))
        assertFalse(panel.contains("pinnedRowsForSidebar("))
        assertFalse(panel.contains("markerGate.reset()"))
        assertFalse(panel.contains("delay("))
        assertFalse(panel.contains("hydrated"))
    }

    private fun homeComponentsSource(): String {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val commonMain =
            generateSequence(File(workingDirectory)) { it.parentFile }
                .map { File(it, "src/commonMain/kotlin") }
                .firstOrNull(File::isDirectory)
                ?: error("Unable to locate shared commonMain sources")
        return File(commonMain, "build/raft/app/SlockHomeComponents.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun settingsHomeSource(): String {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val commonMain =
            generateSequence(File(workingDirectory)) { it.parentFile }
                .map { File(it, "src/commonMain/kotlin") }
                .firstOrNull(File::isDirectory)
                ?: error("Unable to locate shared commonMain sources")
        return File(commonMain, "build/raft/app/SettingsHomePage.kt")
            .readText()
            .replace("\r\n", "\n")
    }
}
