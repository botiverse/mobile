package build.raft.app

import build.raft.app.data.MOBILE_ACTION_MULTI_SHELL_HOST
import build.raft.app.data.MOBILE_ACTION_HTTP3
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsHttp3Snapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SettingsMobilePayloadTest {
    @Test
    fun http3RowPreservesRequestedStateWhenRuntimeBecomesUnavailable() {
        val snapshot =
            SettingsMobileSnapshot(
                pushNotifications = toggle(enabled = false),
                muteServer = toggle(enabled = false),
                http3 =
                    SettingsHttp3Snapshot(
                        requestedEnabled = true,
                        effectiveEnabled = false,
                        curlRequestedForNewCalls = false,
                        canEnable = false,
                        statusLabel = "Unavailable",
                        detail = "native runtime missing"
                    )
            )

        val row =
            ScreenLoadState.Content(snapshot)
                .toMobilePayload(actionState = null, refreshing = false)
                .sections
                .flatMap { it.rows }
                .firstOrNull { it.key == MOBILE_ACTION_HTTP3 }

        assertNotNull(row)
        assertEquals(true, row.toggleValue)
        assertEquals("false", row.value)
        assertEquals("Unavailable", row.status)
        assertEquals(true, row.enabled, "persisted-on must remain actionable so the user can turn it off")
    }

    @Test
    fun multiShellHostRowIsDefaultOffAndCarriesBetaBadge() {
        val snapshot =
            SettingsMobileSnapshot(
                pushNotifications = toggle(enabled = false),
                muteServer = toggle(enabled = false),
                multiShellHost = toggle(enabled = false)
            )

        val payload = ScreenLoadState.Content(snapshot).toMobilePayload(actionState = null, refreshing = false)
        val row =
            payload.sections
                .flatMap { it.rows }
                .firstOrNull { it.key == MOBILE_ACTION_MULTI_SHELL_HOST }

        assertNotNull(row)
        assertEquals("Beta", row.badgeLabel)
        assertEquals(false, row.toggleValue)
        assertEquals("true", row.value)
        assertFalse(row.title.isBlank())
    }

    private fun toggle(enabled: Boolean): SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = enabled,
            available = true,
            statusLabel = if (enabled) "Enabled" else "Off",
            detail = "detail"
        )
}
