package build.raft.app

import build.raft.app.data.SharedSettingsAboutSnapshot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AuthDiagnosticsExportTest {
    @Test
    fun exportIncludesEnvironmentAndRedactsSecretsWithoutRunLogs() {
        val text =
            buildAuthDiagnosticsExportText(
                about =
                    SharedSettingsAboutSnapshot(
                        appName = "Raft",
                        versionLabel = "1.2.3",
                        buildLabel = "42",
                        sourceLabel = "abc1234",
                        packageName = "build.raft.app",
                        signedInUserLabel = "artin@slock.ai",
                        selectedServerLabel = "Slock Team",
                        sessionStatusLabel = "Signed out",
                        deviceLabel = "OHOS Phone",
                        platformLabel = "HarmonyOS"
                    ),
                errorMessage = "diagnostics error: settings_diagnostics_exception refresh_token=refresh-token-123"
            )

        assertContains(text, "Raft auth diagnostics")
        assertContains(text, "Platform: HarmonyOS")
        assertContains(text, "Source: abc1234")
        assertContains(text, "settings_diagnostics_exception")
        assertContains(text, "[redacted]")
        assertFalse(text.contains("refresh-token-123"))
        assertFalse(text.contains("artin@slock.ai"))
        // Copied text is the environment summary only: app run logs go out only with Feedback.
        assertFalse(text.contains("Recent logs"))
    }

    @Test
    fun signedOutFeedbackMessageIdentifiesTheSourceWithoutCredentials() {
        val text =
            buildSignedOutAuthDiagnosticsFeedbackMessage(
                SharedSettingsAboutSnapshot(
                    versionLabel = "1.2.0",
                    buildLabel = "1020000",
                    sourceLabel = "source-123",
                    signedInUserLabel = "artin@raft.build"
                )
            )

        assertContains(text, "Signed-out auth diagnostics report")
        assertContains(text, "Version: 1.2.0")
        assertContains(text, "Build: 1020000")
        assertContains(text, "Source: source-123")
        assertFalse(text.contains("artin@raft.build"))
    }
}
