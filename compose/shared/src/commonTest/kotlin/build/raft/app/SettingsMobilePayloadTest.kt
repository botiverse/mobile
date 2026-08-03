package build.raft.app

import build.raft.app.data.MOBILE_ACTION_MULTI_SHELL_HOST
import build.raft.app.data.MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE
import build.raft.app.data.MOBILE_ACTION_INLINE_SELECTION_TOOLBAR
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SettingsMobilePayloadTest {
    @Test
    fun multiShellHostRowIsDefaultOffAndCarriesBetaBadge() {
        val snapshot =
            SettingsMobileSnapshot(
                pushNotifications = toggle(enabled = false),
                muteServer = toggle(enabled = false),
                multiShellHost = toggle(enabled = false)
            )

        val payload =
            ScreenLoadState.Content(snapshot).toMobilePayload(
                actionState = null,
                refreshing = false,
                errorStrings = englishErrorPresentationStringsForTest()
            )
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

    @Test
    fun conversationForwardGestureIsDefaultOffBetaAndFollowsInlineSelection() {
        val snapshot =
            SettingsMobileSnapshot(
                pushNotifications = toggle(enabled = false),
                muteServer = toggle(enabled = false),
                inlineSelectionToolbar = toggle(enabled = false),
                conversationForwardGesture = toggle(enabled = false)
            )

        val rows =
            ScreenLoadState.Content(snapshot).toMobilePayload(
                actionState = null,
                refreshing = false,
                errorStrings = englishErrorPresentationStringsForTest()
            ).sections.flatMap { it.rows }
        val inlineIndex = rows.indexOfFirst { it.key == MOBILE_ACTION_INLINE_SELECTION_TOOLBAR }
        val forwardIndex = rows.indexOfFirst { it.key == MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE }
        val row = rows.getOrNull(forwardIndex)

        assertEquals(inlineIndex + 1, forwardIndex)
        assertNotNull(row)
        assertEquals("Beta", row.badgeLabel)
        assertEquals(false, row.toggleValue)
        assertFalse(row.title.isBlank())
        assertFalse(row.detail.isBlank())
    }

    private fun toggle(enabled: Boolean): SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = enabled,
            available = true,
            statusLabel = if (enabled) "Enabled" else "Off",
            detail = "detail"
        )
}
