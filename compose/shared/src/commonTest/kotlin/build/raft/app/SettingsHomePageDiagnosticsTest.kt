package build.raft.app

import build.raft.app.data.SettingsFeedbackType
import build.raft.app.data.PlatformFile
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDiagnosticsSnapshot
import build.raft.app.matches
import build.raft.app.settings.SettingsHandsFeedbackRetryRequest
import build.raft.app.settings.localFeatureOverridesAvailable
import build.raft.app.settings.matches
import build.raft.app.settings.registeredLocalFeatureOverrides
import build.raft.app.settings.replaceLocalizedFormatArgs
import build.raft.app.settings.toDiagnosticsPayload
import build.raft.app.thread.key
import build.raft.app.data.SharedSettingsDiagnosticFeatureFlagSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsHomePageDiagnosticsTest {
    @Test
    fun localFeatureOverrideSurfaceIsUnreachableWithoutEntitlement() {
        val releaseSnapshot =
            localFeatureOverrideSnapshot().copy(
                featureFlagOverrides =
                    localFeatureOverrideSnapshot().featureFlagOverrides.map { it.copy(entitled = false) }
            )

        assertFalse(releaseSnapshot.localFeatureOverridesAvailable())
        assertTrue(releaseSnapshot.registeredLocalFeatureOverrides().isEmpty())
        val overview =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state = ScreenLoadState.Content(releaseSnapshot),
                actionState = null,
            )
        assertFalse(overview.sections.any { it.title == "Developer tools" })
    }

    private fun localFeatureOverrideSnapshot(): SharedSettingsDiagnosticsSnapshot =
        SharedSettingsDiagnosticsSnapshot(
            featureFlagOverrides =
                listOf(
                    SharedSettingsDiagnosticFeatureFlagSnapshot(
                        key = "mobile_activity_stable_order_v1",
                        supported = true,
                        scopeKind = "ServerExact",
                        entitled = true,
                        serverEffective = false,
                        finalEffective = false,
                    ),
                    SharedSettingsDiagnosticFeatureFlagSnapshot(
                        key = "sync_core_read_state_v1",
                        supported = true,
                        scopeKind = "ChannelExact",
                        entitled = true,
                        clientOverride = "ForceOn",
                    ),
                    SharedSettingsDiagnosticFeatureFlagSnapshot(
                        key = "future_registered_flag_v1",
                        supported = false,
                        entitled = false,
                        clearReason = "unsupported_no_effect",
                    ),
                )
        )

    @Test
    fun feedbackRetryRequestMatchesTheRestoredDraftAndAttachments() {
        val attachment = PlatformFile(id = "picked-1", fileName = "screen.png")
        val request = SettingsHandsFeedbackRetryRequest("上传图片失败", listOf(attachment))

        assertTrue(request.matches("  上传图片失败  ", listOf(attachment)))
        assertFalse(request.matches("另一条反馈", listOf(attachment)))
        assertFalse(request.matches("上传图片失败", emptyList()))
        // Switching Idea / Bug after a failure is a new feedback, not a retry of the old one.
        assertFalse(request.matches("上传图片失败", listOf(attachment), SettingsFeedbackType.Problem))
    }

    @Test
    fun indexedLocalizedTemplatesKeepLocaleSpecificArgumentOrder() {
        assertEquals(
            "来自 Android 发布运行时 的 3 个库",
            "来自 %2${'$'}s 的 %1${'$'}d 个库".replaceLocalizedFormatArgs(3, "Android 发布运行时")
        )
    }
}
