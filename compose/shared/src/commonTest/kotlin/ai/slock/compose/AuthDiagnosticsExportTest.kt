package ai.slock.compose

import ai.slock.compose.data.SharedSettingsAboutSnapshot
import ai.slock.compose.data.SharedSettingsDiagnosticsEntry
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
                        sourceLabel = "auth-bootstrap-diagnostics/e36bc138+",
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
                        )
                    ),
                errorMessage = "diagnostics error: settings_diagnostics_exception"
            )

        assertContains(text, "Raft auth diagnostics")
        assertContains(text, "Platform: HarmonyOS")
        assertContains(text, "Source: auth-bootstrap-diagnostics/e36bc138+")
        assertContains(text, "SlockAuthNetwork")
        assertContains(text, "[redacted]")
        assertFalse(text.contains("refresh-token-123"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("artin@slock.ai"))
    }
}
