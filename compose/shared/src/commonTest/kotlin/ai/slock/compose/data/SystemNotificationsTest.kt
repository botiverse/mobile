package ai.slock.compose.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemNotificationsTest {
    @Test
    fun notificationPreferenceDefaultsToDisabledWhenNoPreferenceExists() = runBlocking {
        val repository = PersistentNotificationPreferenceRepository(
            KeyValueNotificationPreferenceStore(InMemorySlockKeyValueStore())
        )

        val snapshot = repository.getNotificationPreference()

        assertFalse(snapshot.enabled)
    }

    @Test
    fun notificationPreferencePersistsSelectedState() = runBlocking {
        val repository = PersistentNotificationPreferenceRepository(
            KeyValueNotificationPreferenceStore(InMemorySlockKeyValueStore())
        )

        val enabled = repository.setNotificationEnabled(true)
        val restoredEnabled = repository.getNotificationPreference()
        val disabled = repository.setNotificationEnabled(false)
        val restoredDisabled = repository.getNotificationPreference()

        assertTrue(enabled.enabled)
        assertTrue(restoredEnabled.enabled)
        assertFalse(disabled.enabled)
        assertFalse(restoredDisabled.enabled)
    }

    @Test
    fun inboxNotificationPolicySuppressesForegroundAndOwnVisibleMessages() {
        assertEquals(
            false,
            shouldShowSystemInboxNotification(
                messageAuthorId = "member-1",
                currentMemberId = "member-1",
                isVisibleThread = false
            )
        )
        assertEquals(
            false,
            shouldShowSystemInboxNotification(
                messageAuthorId = "member-2",
                currentMemberId = "member-1",
                isVisibleThread = true
            )
        )
        assertEquals(
            false,
            shouldShowSystemInboxNotification(
                messageAuthorId = "member-2",
                currentMemberId = "member-1",
                isVisibleThread = false,
                isAppInForeground = true
            )
        )
    }

    @Test
    fun inboxNotificationPolicyAllowsOtherUnreadBackgroundMessages() {
        assertTrue(
            shouldShowSystemInboxNotification(
                messageAuthorId = "member-2",
                currentMemberId = "member-1",
                isVisibleThread = false,
                isAppInForeground = false
            )
        )
    }
}
