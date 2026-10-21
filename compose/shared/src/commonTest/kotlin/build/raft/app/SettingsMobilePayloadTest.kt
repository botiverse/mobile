package build.raft.app

import build.raft.app.data.MOBILE_ACTION_DESKTOP_MODE
import build.raft.app.data.MOBILE_ACTION_MULTI_SHELL_HOST
import build.raft.app.data.MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE
import build.raft.app.data.MOBILE_ACTION_NEW_MESSAGE_MENU
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun desktopModeRowDefaultsOnAndCarriesNoBadge() {
        val snapshot =
            SettingsMobileSnapshot(
                pushNotifications = toggle(enabled = false),
                muteServer = toggle(enabled = false)
            )

        val row =
            ScreenLoadState.Content(snapshot).toMobilePayload(
                actionState = null,
                refreshing = false,
                errorStrings = englishErrorPresentationStringsForTest()
            ).sections.flatMap { it.rows }
                .firstOrNull { it.key == MOBILE_ACTION_DESKTOP_MODE }

        assertNotNull(row)
        assertTrue(row.badgeLabel.isBlank())
        assertEquals(true, row.toggleValue)
        assertFalse(row.title.isBlank())
    }

    @Test
    fun newMessageMenuIsDefaultOffBetaAndPrecedesConversationForwardGesture() {
        val snapshot =
            SettingsMobileSnapshot(
                pushNotifications = toggle(enabled = false),
                muteServer = toggle(enabled = false),
                newMessageMenu = toggle(enabled = false),
                conversationForwardGesture = toggle(enabled = false)
            )

        val rows =
            ScreenLoadState.Content(snapshot).toMobilePayload(
                actionState = null,
                refreshing = false,
                errorStrings = englishErrorPresentationStringsForTest()
            ).sections.flatMap { it.rows }
        val menuIndex = rows.indexOfFirst { it.key == MOBILE_ACTION_NEW_MESSAGE_MENU }
        val forwardIndex = rows.indexOfFirst { it.key == MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE }
        val menuRow = rows.getOrNull(menuIndex)
        val forwardRow = rows.getOrNull(forwardIndex)

        assertEquals(menuIndex + 1, forwardIndex)
        assertNotNull(menuRow)
        assertEquals("Beta", menuRow.badgeLabel)
        assertEquals(false, menuRow.toggleValue)
        assertEquals("New message menu", menuRow.title)
        assertFalse(menuRow.detail.isBlank())
        assertNotNull(forwardRow)
        assertEquals("Beta", forwardRow.badgeLabel)
    }

    @Test
    fun unavailableConversationForwardGestureIsOmitted() {
        val snapshot =
            SettingsMobileSnapshot(
                pushNotifications = toggle(enabled = false),
                muteServer = toggle(enabled = false),
                newMessageMenu = toggle(enabled = false),
                conversationForwardGesture =
                    SettingsMobileToggleSnapshot(
                        enabled = false,
                        available = false,
                        statusLabel = "Unsupported",
                        detail = "Conversation Forward navigation is available on iOS."
                    )
            )

        val rows =
            ScreenLoadState.Content(snapshot).toMobilePayload(
                actionState = null,
                refreshing = false,
                errorStrings = englishErrorPresentationStringsForTest()
            ).sections.flatMap { it.rows }

        assertNull(rows.firstOrNull { it.key == MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE })
    }

    private fun toggle(enabled: Boolean): SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = enabled,
            available = true,
            statusLabel = if (enabled) "Enabled" else "Off",
            detail = "detail"
        )
}
