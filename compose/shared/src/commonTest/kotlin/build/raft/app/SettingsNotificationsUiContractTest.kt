package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import build.raft.app.data.PushPreviewMode

class SettingsNotificationsUiContractTest {
    @Test
    fun permissionGrantedRecognizesPositivePlatformStatuses() {
        assertTrue(settingsNotificationPermissionGranted("Allowed"))
        assertTrue(settingsNotificationPermissionGranted(" granted "))
        assertTrue(settingsNotificationPermissionGranted("Enabled"))
        assertTrue(settingsNotificationPermissionGranted("ON"))
    }

    @Test
    fun permissionGrantedRejectsActionAndUnavailableStatuses() {
        assertFalse(settingsNotificationPermissionGranted("Requested"))
        assertFalse(settingsNotificationPermissionGranted("Disabled"))
        assertFalse(settingsNotificationPermissionGranted("Unsupported"))
        assertFalse(settingsNotificationPermissionGranted(""))
    }

    @Test
    fun permissionCopyUsesSemanticStateInsteadOfRawPlatformDetail() {
        assertEquals(
            SettingsNotificationPermissionCopy.Allowed,
            settingsNotificationPermissionCopy(permissionGranted = true, permissionAvailable = true)
        )
        assertEquals(
            SettingsNotificationPermissionCopy.Needed,
            settingsNotificationPermissionCopy(permissionGranted = false, permissionAvailable = true)
        )
        assertEquals(
            SettingsNotificationPermissionCopy.Unavailable,
            settingsNotificationPermissionCopy(permissionGranted = false, permissionAvailable = false)
        )
    }

    @Test
    fun testNotificationCopyNormalizesEveryUserVisibleTerminal() {
        assertEquals(SettingsTestNotificationCopy.Ready, settingsTestNotificationCopy("Ready", available = true))
        assertEquals(SettingsTestNotificationCopy.Sent, settingsTestNotificationCopy("Sent", available = true))
        assertEquals(SettingsTestNotificationCopy.Requested, settingsTestNotificationCopy("Requested", available = true))
        assertEquals(SettingsTestNotificationCopy.Unavailable, settingsTestNotificationCopy("Unsupported", available = false))
        assertEquals(SettingsTestNotificationCopy.Unavailable, settingsTestNotificationCopy("", available = false))
        assertEquals(SettingsTestNotificationCopy.Error, settingsTestNotificationCopy("Error", available = true))
    }

    @Test
    fun previewModeExposesExactlyTheThreeCanonicalWireValues() {
        // The settings control renders one option per enum entry, so the enum *is* the user-facing
        // choice list: it must stay at three and must never resurface the retired "private" value.
        assertEquals(
            listOf("full", "sender_only", "hidden"),
            PushPreviewMode.entries.map { it.wireName }
        )
    }

    @Test
    fun stricterOfIsOrderIndependent() {
        for (a in PushPreviewMode.entries) {
            for (b in PushPreviewMode.entries) {
                val expected = if (a.strictness >= b.strictness) a else b
                assertEquals(expected, PushPreviewMode.stricterOf(a, b), "stricterOf($a, $b)")
                assertEquals(PushPreviewMode.stricterOf(a, b), PushPreviewMode.stricterOf(b, a), "stricterOf($b, $a)")
            }
        }
        assertEquals(PushPreviewMode.Hidden, PushPreviewMode.stricterOf(PushPreviewMode.Full, PushPreviewMode.Hidden))
        assertEquals(PushPreviewMode.SenderOnly, PushPreviewMode.stricterOf(PushPreviewMode.SenderOnly, PushPreviewMode.Full))
    }

    @Test
    fun previewModeEachEntryHasDistinctIconAndLabel() {
        assertEquals(
            PushPreviewMode.entries.size,
            PushPreviewMode.entries.map { it.settingsPreviewModeIcon() }.toSet().size
        )
        assertEquals(
            PushPreviewMode.entries.size,
            PushPreviewMode.entries.map { it.settingsPreviewModeLabel() }.toSet().size
        )
    }
}
