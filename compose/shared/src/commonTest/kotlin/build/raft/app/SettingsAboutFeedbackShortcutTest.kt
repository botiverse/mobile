package build.raft.app

import build.raft.app.data.DEVELOPER_ACTION_API_ENVIRONMENT
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsAboutFeedbackShortcutTest {
    @Test
    fun signedOutAboutShortcutsExposeFeedbackAfterDiagnostics() {
        val rows = shortcutRows(includeFeedback = true)

        assertEquals(
            listOf(DEVELOPER_ACTION_API_ENVIRONMENT, "diagnostics", SETTINGS_ABOUT_FEEDBACK_ACTION),
            rows.map { it.key }
        )
        val feedback = rows.last()
        assertEquals("Send feedback", feedback.title)
        assertEquals("Report a problem or suggestion", feedback.detail)
        assertTrue(feedback.enabled)
    }

    private fun shortcutRows(includeFeedback: Boolean): List<RaftSharedSettingsRow> =
        settingsAboutDeveloperShortcutRows(
            environmentTitle = "API environment",
            environmentDetail = "Current endpoint",
            environmentStatus = "Current",
            environmentEnabled = true,
            diagnosticsTitle = "Diagnostics",
            diagnosticsDetail = "Open diagnostic tools",
            feedbackTitle = "Send feedback",
            feedbackDetail = "Report a problem or suggestion",
            openStatus = "Open",
            includeFeedback = includeFeedback
        )
}
