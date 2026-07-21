package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusDismissProductionContractTest {
    @Test
    fun sharedDismissModifierUsesUnconsumedDownAndNeverConsumesTheGesture() {
        val source = source("build/raft/app/BasicWidget.kt")
        val helper = source.substringAfter("internal fun Modifier.dismissInputFocusOnUnconsumedPointerDown(")
            .substringBefore("/**\n * [touchListener] plus")

        assertContains(helper, "awaitFirstDown(requireUnconsumed = true)")
        assertContains(helper, "currentFocusManager.clearFocus(force = true)")
        assertFalse(".consume()" in helper)
    }

    @Test
    fun unifiedDialogsAndOrdinarySettingsFormsUseTheSharedDismissBoundary() {
        val dialogs = source("build/raft/app/SlockModalComponents.kt")
        val settings = source("build/raft/app/SettingsHomePage.kt")
        val members = source("build/raft/app/MembersPage.kt")

        assertContains(dialogs, "dismissInputFocusOnBackgroundDown: Boolean = false")
        assertContains(dialogs, "enabled = dismissInputFocusOnBackgroundDown")
        assertContains(settings, "Modifier.fillMaxSize().dismissInputFocusOnUnconsumedPointerDown()")
        assertContains(members, "MembersAgentIssueReportDialog(")
        val reportDialog = members.substringAfter("private fun MembersAgentIssueReportDialog(")
            .substringBefore("private fun MembersAgentIssueReportOption(")
        assertContains(reportDialog, "dismissInputFocusOnBackgroundDown = true")
    }

    @Test
    fun nonEditableDialogsRemainOptOutByDefault() {
        val dialogs = source("build/raft/app/SlockModalComponents.kt")
        val announcement = source("build/raft/app/AnnouncementModal.kt")

        assertContains(dialogs, "dismissInputFocusOnBackgroundDown: Boolean = false")
        assertFalse("dismissInputFocusOnBackgroundDown" in announcement)
    }

    @Test
    fun threadComposerKeepsItsDedicatedNonConsumingTimelineDismissContract() {
        val thread = source("build/raft/app/ThreadPage.kt")

        assertContains(thread, ".touchListenerWithConfiguration { type, position, touchSlop ->")
        assertContains(thread, "focusManager.clearFocus(force = true)")
    }

    private fun source(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("Could not locate shared commonMain Kotlin sources")
        val file = File(commonMain, path)
        assertTrue(file.isFile, "Missing production source $path")
        return file.readText().replace("\r\n", "\n")
    }
}
