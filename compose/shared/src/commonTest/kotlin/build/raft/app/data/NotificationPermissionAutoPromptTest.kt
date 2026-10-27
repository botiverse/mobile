package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** artin 10-27: ask once for the notification permission after sign-in, never when granted, never again. */
class NotificationPermissionAutoPromptTest {
    private class Host(var statusCode: String? = "permission_not_determined") {
        var requests = 0
        val logs = mutableListOf<String>()
        var requestResult = SystemNotificationActionResult(SystemNotificationActionStatus.Requested, code = "permission_requested")

        fun prompt(store: SharedPreferenceStore, supported: Boolean = true) =
            NotificationPermissionAutoPrompt(
                preferences = KeyValueNotificationPermissionPromptPreferenceStore(store),
                supported = supported,
                loadStatusCode = { statusCode },
                requestPermission = { requests++; requestResult },
                logger = { logs += it },
            )
    }

    @Test
    fun asksOnceWhenNeverGrantedAndRemembersAcrossLaunches() = runTest {
        val store = InMemoryRaftKeyValueStore()
        val host = Host()
        val first = host.prompt(store)
        assertEquals(NotificationPermissionAutoPromptOutcome.Requested, first.maybeAsk())
        assertEquals(1, host.requests)
        assertTrue(KeyValueNotificationPermissionPromptPreferenceStore(store).readAutoPromptAskedSync())
        assertEquals(NotificationPermissionAutoPromptOutcome.Requested, first.maybeAsk(), "same shell shown again")
        assertEquals(1, host.requests)

        // Next launch / next sign-in (a new shell): the user declined, the permission is still not granted.
        assertEquals(NotificationPermissionAutoPromptOutcome.AlreadyAsked, host.prompt(store).maybeAsk())
        assertEquals(1, host.requests, "never asks again on its own")
        assertTrue(host.logs.contains("notification_permission_auto_prompt outcome=requested"), host.logs.toString())
    }

    @Test
    fun neverAsksWhenGrantedSwitchedOffUnsupportedOrUnknown() = runTest {
        for (code in listOf("permission_authorized", "permission_denied")) {
            val store = InMemoryRaftKeyValueStore()
            val host = Host(statusCode = code)
            assertEquals(NotificationPermissionAutoPromptOutcome.NotNeeded, host.prompt(store).maybeAsk(), code)
            assertEquals(0, host.requests, code)
            assertFalse(KeyValueNotificationPermissionPromptPreferenceStore(store).readAutoPromptAskedSync(), code)
        }
        val unsupportedHost = Host()
        assertEquals(
            NotificationPermissionAutoPromptOutcome.Unsupported,
            unsupportedHost.prompt(InMemoryRaftKeyValueStore(), supported = false).maybeAsk(),
        )
        assertEquals(0, unsupportedHost.requests)

        // No status from the host: no prompt and no flag, so a later launch can still ask.
        val store = InMemoryRaftKeyValueStore()
        val unknown = Host(statusCode = null)
        val prompt = unknown.prompt(store)
        assertEquals(NotificationPermissionAutoPromptOutcome.StatusUnknown, prompt.maybeAsk())
        assertFalse(KeyValueNotificationPermissionPromptPreferenceStore(store).readAutoPromptAskedSync())
        unknown.statusCode = "permission_not_determined"
        assertEquals(NotificationPermissionAutoPromptOutcome.Requested, prompt.maybeAsk(), "retries once the host answers")
        assertEquals(1, unknown.requests)
    }

    @Test
    fun logsTheAnswerOnTheResumeAfterThePrompt() = runTest {
        val host = Host()
        val prompt = host.prompt(InMemoryRaftKeyValueStore())
        prompt.onResumed()
        assertTrue(host.logs.none { "result=" in it }, "nothing asked yet")
        prompt.maybeAsk()
        host.statusCode = "permission_authorized"
        prompt.onResumed()
        prompt.onResumed()
        assertEquals(listOf("notification_permission_auto_prompt result=granted"), host.logs.filter { "result=" in it })
    }

    @Test
    fun aPromptThatNeverShowedDoesNotCountAsTheOneAsk() = runTest {
        // e.g. no activity to show it on yet: the host answers permission_denied without a dialog.
        val store = InMemoryRaftKeyValueStore()
        val host = Host()
        host.requestResult = SystemNotificationActionResult(SystemNotificationActionStatus.PermissionDenied, code = "permission_denied")
        val prompt = host.prompt(store)
        assertEquals(NotificationPermissionAutoPromptOutcome.RequestFailed, prompt.maybeAsk())
        assertFalse(KeyValueNotificationPermissionPromptPreferenceStore(store).readAutoPromptAskedSync())
        assertTrue(host.logs.contains("notification_permission_auto_prompt request_failed code=permission_denied"), host.logs.toString())

        host.requestResult = SystemNotificationActionResult(SystemNotificationActionStatus.Requested, code = "permission_requested")
        assertEquals(NotificationPermissionAutoPromptOutcome.Requested, prompt.maybeAsk(), "the shell showing again retries")
        assertEquals(2, host.requests)
        assertTrue(KeyValueNotificationPermissionPromptPreferenceStore(store).readAutoPromptAskedSync())
    }
}
