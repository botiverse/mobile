package build.raft.app.shared

import build.raft.app.shell.RAFT_ROUTE_ACCOUNT_ID
import build.raft.app.shell.RAFT_ROUTE_ENVIRONMENT_ID
import build.raft.app.shell.RAFT_ROUTE_SERVER_ID
import build.raft.app.shell.RAFT_SERVER_SWITCH_KEEP_WINDOW
import build.raft.app.shell.RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS
import build.raft.app.shell.RAFT_SERVER_SWITCH_WINDOW_ID
import build.raft.app.shell.RAFT_SHELL_SERVER_ID
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftDesktopShortcutContract
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidShellWindowContractTest {
    @After
    fun tearDown() {
        AndroidShellWindowRegistry.clearForTest()
        AndroidShellWindowStore.clear()
        AndroidPendingLauncherTaskLaunchStore.clear()
    }

    @Test
    fun notificationTapOpensTheNotificationServersWindowNotTheSelectedServers() {
        // The account last used server B's window; a notification from server A (Hands 3a465552).
        val selected = AndroidShellRouteOwner("https://api.test", "user-1", "server-b")
        val owner = notificationAndroidShellRouteOwner(selected, " server-a ")

        assertEquals(AndroidShellRouteOwner("https://api.test", "user-1", "server-a"), owner)
        val target =
            resolveAndroidShellLaunchTarget(
                JSONObject()
                    .put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-a")
                    .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                    .withAndroidShellRouteOwner(owner!!),
                multiShellHostEnabled = true,
            )
        assertEquals(selected.copy(serverId = "server-a").shellCanonicalUri(), target.shellInstanceId)
        assertEquals("server-a", target.serverId)
        // Without a server on the notification, the selected owner as before; signed out, none.
        assertEquals(selected, notificationAndroidShellRouteOwner(selected, " "))
        assertNull(notificationAndroidShellRouteOwner(null, "server-a"))
    }

    /** The windows of the device report: the launcher (task 15) and server A's document window (task 18). */
    private val serverAOwner = AndroidShellRouteOwner("https://api.test", "user-1", "server-a")
    private val launcherWindow = AndroidShellLaunchTarget("android-default-app-shell", "server-x", documentShell = false)

    /** What the shell sends for "switch to server A" (appShellServerSwitchNewWindowPageDataOrNull). */
    private fun switchToServerA(windowId: String? = null, keepWindow: Boolean = false): JSONObject =
        JSONObject()
            .put(ANDROID_DOCUMENT_SHELL_KEY, true)
            .withAndroidShellRouteOwner(serverAOwner)
            .apply {
                windowId?.let { put(RAFT_SERVER_SWITCH_WINDOW_ID, it) }
                if (keepWindow) put(RAFT_SERVER_SWITCH_KEEP_WINDOW, true)
            }

    @Test
    fun serverSwitchToTheServerTheLauncherWindowShowsUsesThatWindowNotASecondOne() {
        // 1.27.0 on a device: the launcher window (task 15) shows A; picking A in B's window
        // opened A a document window (task 18) next to it.
        AndroidShellWindowRegistry.register(launcherWindow, taskId = 15)
        val switch = switchToServerA(windowId = launcherWindow.shellInstanceId, keepWindow = true)

        val existing = androidServerSwitchExistingWindowOrNull(switch, multiShellHostEnabled = true)!!

        assertEquals(15, existing.bringForwardTaskId)
        assertEquals("android-default-app-shell", existing.target.shellInstanceId)
        assertFalse(existing.target.documentShell)
        assertNull("no document task for the launch", existing.target.taskLaunchPlan(requestedTaskClear = false).documentShellId)
        assertNull("not the server the launcher was launched with", existing.target.serverId)
        // The route owner alone (what 1.27.0 resolved by) is a new document window for A.
        assertEquals(
            serverAOwner.shellCanonicalUri(),
            resolveAndroidShellLaunchTarget(switch, multiShellHostEnabled = true).shellInstanceId,
        )
    }

    @Test
    fun legacyRegisteredServerWindowCannotCreateAnotherShell() {
        AndroidShellWindowRegistry.register(
            AndroidShellLaunchTarget("shell-existing", "server-2", documentShell = true)
        )

        val target = resolveAndroidShellLaunchTarget(JSONObject().put(ANDROID_SHELL_SERVER_ID_KEY, "server-2"))

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertFalse(target.documentShell)
    }

    @Test
    fun disabledExplicitDocumentRouteFallsBackToDefaultShell() {
        val target =
            resolveAndroidShellLaunchTarget(
                JSONObject()
                    .put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")
                    .put(ANDROID_DOCUMENT_SHELL_KEY, true),
                multiShellHostEnabled = false
            )

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertFalse(target.documentShell)
        assertTrue(shouldUseGlobalHostDisplayMetrics(target))
    }

    @Test
    fun persistedLauncherWindowRevivesDeadTaskAfterProcessDeathInsteadOfOpeningDuplicateDocument() {
        val backingStore = InMemoryRaftKeyValueStore()
        val previousProvider = AndroidShellWindowStore.storeProvider
        AndroidShellWindowStore.storeProvider = { backingStore }
        try {
            val launcherTarget = AndroidShellLaunchTarget(DEFAULT_ANDROID_SHELL_INSTANCE_ID, null, documentShell = false)
            AndroidShellWindowRegistry.register(launcherTarget, taskId = 16)
            AndroidShellWindowStore.recordRegistered(launcherTarget, taskId = 16, fallbackOwner = serverAOwner)
            AndroidShellWindowStore.recordServerClaim(DEFAULT_ANDROID_SHELL_INSTANCE_ID, "server-a", serverAOwner)

            // Simulate process death (in-memory registry cleared, Recents still holds dead task 16).
            AndroidShellWindowRegistry.clearForTest()
            assertEquals("server-a", AndroidShellWindowStore.persistedLauncherServerId("https://api.test", "user-1"))

            val appTasks =
                listOf(
                    AndroidAppTaskSnapshot(
                        taskId = 16,
                        isAlive = false,
                        baseIntentData = null,
                        baseActivityClassName = "build.raft.app.MainActivity",
                    )
                )
            val reconciled = AndroidShellWindowStore.reconcile(appTasks, "https://api.test", "user-1")
            val match = androidServerSwitchReconciledWindowOrNull(switchToServerA(), true, reconciled)!!

            assertEquals(16, match.bringForwardTaskId)
            assertTrue(match.isDead)
            assertFalse(match.target.documentShell)
            assertEquals(DEFAULT_ANDROID_SHELL_INSTANCE_ID, match.target.shellInstanceId)

            // Even with a kept tab on a dead launcher task, bringForwardTaskId stays set (launcher uses stash + moveToFront).
            val withTab = switchToServerA().put(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB, "activity")
            val tabMatch = androidServerSwitchReconciledWindowOrNull(withTab, true, reconciled)!!
            assertEquals(16, tabMatch.bringForwardTaskId)
            assertTrue(tabMatch.isDead)
            assertFalse(tabMatch.target.documentShell)

            // Once task 16 disappears from Recents (swiped away), the persisted record is pruned.
            val afterSwipe = AndroidShellWindowStore.reconcile(emptyList(), "https://api.test", "user-1")
            assertTrue(afterSwipe.isEmpty())
            assertNull(androidServerSwitchReconciledWindowOrNull(switchToServerA(), true, afterSwipe))
            assertNull(AndroidShellWindowStore.persistedLauncherServerId("https://api.test", "user-1"))
        } finally {
            AndroidShellWindowStore.storeProvider = previousProvider
        }
    }

    @Test
    fun historyLaunchSanitizerStripsOneShotPayloadsAndPreservesDocumentWindowIdentity() {
        val owner = AndroidShellRouteOwner("https://api.test", "user-1", "server-b")
        val target = AndroidShellLaunchTarget(owner.shellCanonicalUri(), "server-b", documentShell = true, routeOwner = owner)
        val raw =
            JSONObject()
                .put("pageName", RaftCmpActivity.PAGE_APP_SHELL)
                .put("instanceId", "notification-app-shell-12345")
                .put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-b")
                .put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID, "channel-qa-b")
                .put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID, "msg-1")
                .put(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB, "activity")
                .put(RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS, 123456789L)
                .put(RAFT_SERVER_SWITCH_WINDOW_ID, owner.shellCanonicalUri())
                .put(RAFT_SERVER_SWITCH_KEEP_WINDOW, true)
                .put("slockSuppressRestoreLanding", true)
                .put(RaftDesktopShortcutContract.KeyTargetId, "ch-1")
                .put("intent", build.raft.app.AndroidExternalShareIntent)
                .put(build.raft.app.AndroidExternalShareTokenKey, "opaque-token_1234567890")
                .withAndroidShellLaunchTarget(target)

        val sanitized = sanitizeAppShellPageDataForHistoryLaunch(raw)

        // Window identity preserved (including slockShellServerId written by withAndroidShellLaunchTarget).
        assertEquals(owner.shellCanonicalUri(), sanitized.getString(ANDROID_SHELL_INSTANCE_ID_KEY))
        assertTrue(sanitized.getBoolean(ANDROID_DOCUMENT_SHELL_KEY))
        assertEquals("server-b", sanitized.getString(ANDROID_SHELL_SERVER_ID_KEY))
        assertEquals("server-b", sanitized.getString(RAFT_SHELL_SERVER_ID))
        assertEquals("https://api.test", sanitized.getString(RAFT_ROUTE_ENVIRONMENT_ID))
        assertEquals("user-1", sanitized.getString(RAFT_ROUTE_ACCOUNT_ID))
        assertEquals("server-b", sanitized.getString(RAFT_ROUTE_SERVER_ID))

        // One-shot payloads stripped so reopening from Recents lands cleanly on Home.
        assertFalse(sanitized.has("instanceId"))
        assertFalse(sanitized.has(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID))
        assertFalse(sanitized.has(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID))
        assertFalse(sanitized.has(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID))
        assertFalse(sanitized.has(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB))
        assertFalse(sanitized.has(RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS))
        assertFalse(sanitized.has(RAFT_SERVER_SWITCH_WINDOW_ID))
        assertFalse(sanitized.has(RAFT_SERVER_SWITCH_KEEP_WINDOW))
        assertFalse(sanitized.has("slockSuppressRestoreLanding"))
        assertFalse(sanitized.has(RaftDesktopShortcutContract.KeyTargetId))
        assertFalse(sanitized.has("intent"))
        assertFalse(sanitized.has(build.raft.app.AndroidExternalShareTokenKey))
    }

}
