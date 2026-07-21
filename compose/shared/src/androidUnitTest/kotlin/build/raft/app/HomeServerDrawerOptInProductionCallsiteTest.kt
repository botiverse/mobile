package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeServerDrawerOptInProductionCallsiteTest {
    @Test
    fun appShellHostWaitsForObservableSettleAndKeysEffectByOwnerGeneration() {
        val source = settingsHomeSource()
        val host =
            source.substringAfter("private fun AppShellServerDrawerHost(")
                .substringBefore("internal fun appShellAuthGateViewModelKey")

        assertContains(host, "LaunchedEffect(drawerState, projection.commandIntent.generation)")
        assertContains(host, "snapshotFlow { DrawerSettledObservation(drawerState.isOpen, drawerState.progress) }")
        assertContains(host, ".first { observation -> observation.isSettled(intent.desiredOpen) }")
        assertContains(host, "server_drawer_command_submitted")
        assertContains(host, "server_drawer_command_settled")
        assertContains(host, "commandState.acceptsConversationClick(generation, currentIntent)")
        assertContains(host, "commandState.acceptsRowObservation(generation, currentIntent)")
        assertContains(host, "val pointerPolicy = commandState.pointerPolicy(projection.commandIntent)")
        assertContains(host, "userScrollEnabled = pointerPolicy.nativePagerEnabled")
        assertContains(host, "if (pointerPolicy.appEdgeEnabled && drawerState.progress <= 0.01f)")
        assertContains(host, "commandState = commandState.beginProgrammaticOpen()")
        assertContains(host, "commandState = commandState.beginProgrammaticClose()")
        assertContains(host, "onProgrammaticCloseFenceChanged(programmaticCloseFence)")
        assertContains(host, ".establishClosedOwner(currentIntent)")
        assertContains(host, ".nativeSettledClosedWithoutCommand()")
        assertFalse(host.contains("server_drawer_command_end"))
        assertFalse(host.contains("delay("))
    }

    @Test
    fun appBackFencesDrawerPointersBeforeDispatchingAuthoritativeClose() {
        val source = settingsHomeSource()
        val popMainRoute = source.substringAfter("fun popMainRoute(): Boolean {").substringBefore("fun openHomeSecondaryDestination")
        val drawerBranch =
            popMainRoute.substringAfter("if (homeState.serverSwitcherExpanded) {").substringBefore("return true")

        val fenceIndex = drawerBranch.indexOf("serverDrawerProgrammaticCloseFence?.invoke(\"back\")")
        val dispatchIndex = drawerBranch.indexOf("SharedHomeIntent.DismissServerSwitcher")
        assertFalse(fenceIndex < 0)
        assertFalse(dispatchIndex < 0)
        assertTrue(fenceIndex < dispatchIndex)
    }

    @Test
    fun hostedConversationClickIsDisabledUntilInteractiveOwnershipMatchesAuthoritativeGeneration() {
        val source = homeComponentsSource()
        val hosted = source.substringAfter("internal fun HomeServerDrawerHostedPanel(")
            .substringBefore("private fun HomeServerDrawerServerHeader(")

        assertContains(hosted, "interactiveGeneration == rowObservationGeneration")
        assertContains(hosted, "interactiveGeneration == authoritativeOpenGeneration")
        assertContains(hosted, ".clickable(enabled = enabled, onClick = onClick)")
        assertContains(hosted, "onConversationClick(generation, serverId, item.id)")
    }

    @Test
    fun storeOwnsMonotonicGenerationAndSupersededTerminal() {
        val source = sharedHomeStoreSource()
        assertContains(source, "serverDrawerCommandIntent.transitionTo(expanding)")
        assertContains(source, "serverDrawerCommandIntent.transitionTo(false)")
        assertContains(source, "server_drawer_command_superseded generation=")
        assertContains(source, "generation = generation + 1L")
    }

    @Test
    fun appShellHasSingleNativeOpenAndCloseCallsite() {
        val source =
            settingsHomeSource().substringAfter("private fun AppShellServerDrawerHost(")
                .substringBefore("internal fun appShellAuthGateViewModelKey")
        assertEquals(1, Regex("drawerState\\.open\\(\\)").findAll(source).count())
        assertEquals(1, Regex("drawerState\\.close\\(\\)").findAll(source).count())
    }

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

    private fun homeComponentsSource(): String {
        val commonMain =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .map { File(it, "src/commonMain/kotlin") }
                .firstOrNull(File::isDirectory)
                ?: error("Unable to locate shared commonMain sources")
        return File(commonMain, "build/raft/app/SlockHomeComponents.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun settingsHomeSource(): String = commonMainSource("build/raft/app/SettingsHomePage.kt")

    private fun sharedHomeStoreSource(): String = commonMainSource("build/raft/app/data/SharedHomeStore.kt")

    private fun commonMainSource(relativePath: String): String {
        val commonMain =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .map { File(it, "src/commonMain/kotlin") }
                .firstOrNull(File::isDirectory)
                ?: error("Unable to locate shared commonMain sources")
        return File(commonMain, relativePath).readText().replace("\r\n", "\n")
    }
}
