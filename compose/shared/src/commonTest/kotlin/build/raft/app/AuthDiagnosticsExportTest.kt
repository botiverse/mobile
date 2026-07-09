package build.raft.app

import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsDiagnosticsEntry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AuthDiagnosticsExportTest {
    @Test
    fun exportIncludesEnvironmentAndRedactsSecrets() {
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
                entries =
                    listOf(
                        SharedSettingsDiagnosticsEntry(
                            timestampLabel = "1000",
                            tag = "SlockAuthNetwork",
                            message = "refresh_token=refresh-token-123 password=secret user=artin@slock.ai"
                        ),
                        SharedSettingsDiagnosticsEntry(
                            timestampLabel = "1001",
                            tag = "SlockAuth/auth_session_clear",
                            message = "auth_session_clear reason=unauthorized source=current_user fingerprint=len:32:tail4:abcd:hash:1234"
                        )
                    ),
                errorMessage = "diagnostics error: settings_diagnostics_exception"
            )

        assertContains(text, "Raft auth diagnostics")
        assertContains(text, "Platform: HarmonyOS")
        assertContains(text, "Source: abc1234")
        assertContains(text, "SlockAuthNetwork")
        assertContains(text, "auth_session_clear")
        assertContains(text, "fingerprint=len:32:tail4:abcd:hash:1234")
        assertContains(text, "[redacted]")
        assertFalse(text.contains("refresh-token-123"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("artin@slock.ai"))
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
