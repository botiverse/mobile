package ai.slock.compose

import ai.slock.compose.data.SharedSettingsAboutSnapshot
import ai.slock.compose.data.SharedSettingsDiagnosticsEntry
import ai.slock.compose.data.SharedSettingsNetworkProbeSnapshot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AuthDiagnosticsExportTest {
    @Test
    fun exportIncludesEnvironmentProbeAndRedactsSecrets() {
        val text =
            buildAuthDiagnosticsExportText(
                about =
                    SharedSettingsAboutSnapshot(
                        appName = "Slock",
                        versionLabel = "1.2.3",
                        buildLabel = "42",
                        packageName = "ai.slock.compose",
                        signedInUserLabel = "artin@slock.ai",
                        selectedServerLabel = "Slock Team",
                        sessionStatusLabel = "Signed out",
                        deviceLabel = "OHOS Phone",
                        platformLabel = "HarmonyOS"
                    ),
                probe =
                    SharedSettingsNetworkProbeSnapshot(
                        baseUrl = "https://api.slock.ai/api/",
                        statusLabel = "Network error",
                        detail = "authorization: Bearer access-token-123",
                        reachable = false,
                        code = 0,
                        durationMillis = 250
                    ),
                entries =
                    listOf(
                        SharedSettingsDiagnosticsEntry(
                            timestampLabel = "1000",
                            tag = "SlockAuthNetwork",
                            message = "refresh_token=refresh-token-123 password=secret user=artin@slock.ai"
                        )
                    ),
                errorMessage = "probe error: settings_network_probe_exception"
            )

        assertContains(text, "Slock auth diagnostics")
        assertContains(text, "Platform: HarmonyOS")
        assertContains(text, "Base URL: https://api.slock.ai/api/")
        assertContains(text, "Status: Network error")
        assertContains(text, "SlockAuthNetwork")
        assertContains(text, "[redacted]")
        assertFalse(text.contains("access-token-123"))
        assertFalse(text.contains("refresh-token-123"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("artin@slock.ai"))
    }
}
