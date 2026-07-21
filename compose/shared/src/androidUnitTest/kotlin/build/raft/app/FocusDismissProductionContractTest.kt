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
        val account = source("build/raft/app/SettingsAccountPage.kt")
        val members = source("build/raft/app/MembersPage.kt")

        assertContains(dialogs, "dismissInputFocusOnBackgroundDown: Boolean = false")
        assertContains(dialogs, "enabled = dismissInputFocusOnBackgroundDown")
        assertContains(settings, "Modifier.fillMaxSize().dismissInputFocusOnUnconsumedPointerDown()")
        assertContains(account, ".fillMaxSize()\n                .dismissInputFocusOnUnconsumedPointerDown()")
        assertEditableRoot(settings, "private fun SharedSettingsServerContent(")
        assertEditableRoot(settings, "private fun SettingsConnectedAppsContent(")
        assertContains(members, "MembersAgentIssueReportDialog(")
        val reportDialog = members.substringAfter("private fun MembersAgentIssueReportDialog(")
            .substringBefore("private fun MembersAgentIssueReportOption(")
        assertContains(reportDialog, "dismissInputFocusOnBackgroundDown = true")
    }

    @Test
    fun authAndCustomEditableSurfacesOptInWithoutChangingReadOnlyAuthPages() {
        val authComponents = source("build/raft/app/AuthComponents.kt")
        val authPages = source("build/raft/app/AuthPages.kt")
        val settings = source("build/raft/app/SettingsHomePage.kt")
        val tasks = source("build/raft/app/SharedTasksPage.kt")

        assertContains(authComponents, "dismissInputFocusOnBackgroundDown: Boolean = false")
        assertContains(authComponents, "enabled = dismissInputFocusOnBackgroundDown")
        assertContains(functionBlock(authPages, "internal fun LoginContent(", "internal fun RegisterContent("), "dismissInputFocusOnBackgroundDown = true")
        assertContains(functionBlock(authPages, "internal fun RegisterContent(", "internal fun ForgotPasswordContent("), "dismissInputFocusOnBackgroundDown = true")
        assertContains(functionBlock(authPages, "internal fun ForgotPasswordContent(", "private fun AuthLegalAcceptanceContent("), "dismissInputFocusOnBackgroundDown = true")
        assertContains(functionBlock(settings, "private fun AppShellServerGateContent(", "internal fun authChooseServerShouldSkipInitialLoad("), "dismissInputFocusOnBackgroundDown = mode == \"create\"")
        assertContains(functionBlock(tasks, "private fun TasksCreateDialog(", "private fun parseTaskSortTimestamp("), ".dismissInputFocusOnUnconsumedPointerDown()")
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

    private fun assertEditableRoot(source: String, marker: String) {
        val block = source.substringAfter(marker).substringBefore("\n@Composable", missingDelimiterValue = source.substringAfter(marker))
        assertContains(
            block,
            ".dismissInputFocusOnUnconsumedPointerDown()",
            message = "Missing focus-dismiss boundary for $marker"
        )
    }

    private fun functionBlock(source: String, start: String, end: String): String =
        source.substringAfter(start).substringBefore(end)
}
