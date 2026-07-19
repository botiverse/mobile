package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
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
}
