package build.raft.app.shared

import build.raft.app.RAFT_ROUTE_ACCOUNT_ID
import build.raft.app.RAFT_ROUTE_ENVIRONMENT_ID
import build.raft.app.RAFT_ROUTE_SERVER_ID
import build.raft.app.RAFT_SERVER_SWITCH_KEEP_WINDOW
import build.raft.app.RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS
import build.raft.app.RAFT_SERVER_SWITCH_WINDOW_ID
import build.raft.app.RAFT_SHELL_SERVER_ID
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
    private val serverADocumentWindow =
        AndroidShellLaunchTarget(serverAOwner.shellCanonicalUri(), "server-a", documentShell = true, routeOwner = serverAOwner)

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
    fun serverSwitchBringsTheServersWindowForwardAsItIsOnlyWithoutADestination() {
        AndroidShellWindowRegistry.register(launcherWindow, taskId = 15)
        AndroidShellWindowRegistry.register(serverADocumentWindow, taskId = 18)
        val windowId = serverADocumentWindow.shellInstanceId

        // No destination: the task comes forward, no Intent rebuilds the window on Home.
        val asItIs = androidServerSwitchExistingWindowOrNull(switchToServerA(windowId, keepWindow = true), true)!!
        assertEquals(18, asItIs.bringForwardTaskId)
        assertEquals(serverADocumentWindow, asItIs.target)
        // A kept tab: the same window, launched on the switch's page data as before.
        val onATab = androidServerSwitchExistingWindowOrNull(switchToServerA(windowId), true)!!
        assertNull(onATab.bringForwardTaskId)
        assertEquals(serverADocumentWindow, onATab.target)
        // A launch that names no window (a notification tap, a first switch to a server) resolves
        // as before and is delivered with its destination.
        assertNull(androidServerSwitchExistingWindowOrNull(switchToServerA(), true))
        val notification =
            JSONObject()
                .put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-a")
                .put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID, "thread-1")
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                .withAndroidShellRouteOwner(serverAOwner)
        assertNull(androidServerSwitchExistingWindowOrNull(notification, true))
        assertEquals(serverADocumentWindow, resolveAndroidShellLaunchTarget(notification, multiShellHostEnabled = true))
        // The named window closed meanwhile, or the setting is off: as before.
        assertNull(androidServerSwitchExistingWindowOrNull(switchToServerA("slock://shell/gone", keepWindow = true), true))
        assertNull(androidServerSwitchExistingWindowOrNull(switchToServerA(windowId, keepWindow = true), false))
    }

    @Test
    fun notificationServerUsesDefaultSingleShellIdentity() {
        val pageData = JSONObject().put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-2")

        val target = resolveAndroidShellLaunchTarget(pageData)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertEquals("server-2", target.serverId)
        assertFalse(target.documentShell)
        val enriched = pageData.withAndroidShellLaunchTarget(target)
        assertEquals(target.shellInstanceId, enriched.getString(ANDROID_SHELL_INSTANCE_ID_KEY))
        assertEquals("server-2", enriched.getString(ANDROID_SHELL_SERVER_ID_KEY))
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
    fun notificationServerReusesDefaultShellAlreadyShowingThatServer() {
        AndroidShellWindowRegistry.register(
            AndroidShellLaunchTarget("android-default-app-shell", "server-2", documentShell = false)
        )

        val target = resolveAndroidShellLaunchTarget(
            JSONObject().put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-2")
        )

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertEquals("server-2", target.serverId)
        assertFalse(target.documentShell)
    }

    @Test
    fun ordinaryLauncherUsesDefaultNonDocumentShell() {
        val target = resolveAndroidShellLaunchTarget(JSONObject())
        AndroidShellWindowRegistry.register(target, taskId = 41)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertNull(target.serverId)
        assertFalse(target.documentShell)
        assertTrue(AndroidShellWindowRegistry.hasShellInTask(41))
        assertFalse(AndroidShellWindowRegistry.hasShellInTask(42))
    }

    @Test
    fun legacyDocumentRequestCannotSuppressRequestedTaskClear() {
        val target = resolveAndroidShellLaunchTarget(
            JSONObject().put(ANDROID_SHELL_SERVER_ID_KEY, "server-2")
        )

        val taskLaunch = target.taskLaunchPlan(requestedTaskClear = true)

        assertTrue(taskLaunch.clearTask)
    }

    @Test
    fun pageDataRoundTripPreservesEnabledDocumentIdentity() {
        val owner =
            AndroidShellRouteOwner(
                environmentId = "https://api.test/",
                accountId = "user-1",
                serverId = "server-a",
            ).normalizedOrNull()!!
        val canonical = owner.shellCanonicalUri()
        val source = JSONObject().put("slockNotificationMessageId", "message-1")
        val target =
            AndroidShellLaunchTarget(canonical, "server-a", documentShell = true, routeOwner = owner)

        val enriched = source.withAndroidShellLaunchTarget(target)
        val restored = androidShellLaunchTargetFromPageData(enriched)

        assertEquals(canonical, restored?.shellInstanceId)
        assertEquals("server-a", restored?.serverId)
        assertTrue(restored?.documentShell ?: false)
        assertEquals(owner, restored?.routeOwner)
        assertEquals("https://api.test", enriched.getString(RAFT_ROUTE_ENVIRONMENT_ID))
        assertEquals("user-1", enriched.getString(RAFT_ROUTE_ACCOUNT_ID))
        assertEquals("server-a", enriched.getString(RAFT_ROUTE_SERVER_ID))
        assertTrue(enriched.getBoolean(ANDROID_DOCUMENT_SHELL_KEY))
        assertEquals("message-1", enriched.getString("slockNotificationMessageId"))
    }

    @Test
    fun rawLegacyDocumentPageDataNormalizesBeforeStoreScopeWhilePreservingRoute() {
        val source =
            JSONObject()
                .put(ANDROID_SHELL_INSTANCE_ID_KEY, "server-shell:server-b")
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                .put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-b")
                .put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID, "thread-b")
                .put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID, "message-b")

        val normalized = normalizeAndroidAppShellPageData(source)

        assertEquals("android-default-app-shell", normalized.getString(ANDROID_SHELL_INSTANCE_ID_KEY))
        assertEquals("server-b", normalized.getString(ANDROID_SHELL_SERVER_ID_KEY))
        assertFalse(normalized.getBoolean(ANDROID_DOCUMENT_SHELL_KEY))
        assertEquals("thread-b", normalized.getString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID))
        assertEquals("message-b", normalized.getString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID))
    }

    @Test
    fun firstLoginServerSelectionStaysInDefaultNonDocumentTask() {
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_INSTANCE_ID_KEY, "legacy-app-shell")
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-a")
                .put(ANDROID_DOCUMENT_SHELL_KEY, false)

        val target = androidShellLaunchTargetFromPageData(pageData)
        val taskLaunch = target?.taskLaunchPlan(requestedTaskClear = false)

        assertEquals("android-default-app-shell", target?.shellInstanceId)
        assertEquals("server-a", target?.serverId)
        assertFalse(target?.documentShell ?: true)
        assertFalse(taskLaunch?.clearTask ?: true)
    }

    @Test
    fun explicitNonDocumentServerRouteStaysDefaultEvenWithoutShellId() {
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-a")
                .put(ANDROID_DOCUMENT_SHELL_KEY, false)

        val target = resolveAndroidShellLaunchTarget(pageData)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertEquals("server-a", target.serverId)
        assertFalse(target.documentShell)
    }

    @Test
    fun defaultShellIdentityCannotBePromotedToDocumentMode() {
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_INSTANCE_ID_KEY, "android-default-app-shell")
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-a")
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)

        val target = androidShellLaunchTargetFromPageData(pageData)

        assertEquals("android-default-app-shell", target?.shellInstanceId)
        assertEquals("server-a", target?.serverId)
        assertFalse(target?.documentShell ?: true)
    }

    @Test
    fun shellIdentityWithoutDocumentMarkerCannotCreateDocumentTask() {
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_INSTANCE_ID_KEY, "server-shell:server-a")
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-a")

        val target = androidShellLaunchTargetFromPageData(pageData)

        assertEquals("android-default-app-shell", target?.shellInstanceId)
        assertEquals("server-a", target?.serverId)
        assertFalse(target?.documentShell ?: true)
    }

    @Test
    fun chooserCrossServerRouteWithoutOldShellIdUsesDefaultShell() {
        val replacementPageData = JSONObject().put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")

        val target = resolveAndroidShellLaunchTarget(replacementPageData)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertEquals("server-b", target.serverId)
        assertFalse(target.documentShell)
    }

    @Test
    fun enabledExplicitDocumentRouteUsesCanonicalOwnerShell() {
        val owner =
            AndroidShellRouteOwner(
                environmentId = "https://api.test/",
                accountId = "user-1",
                serverId = "server-b",
            ).normalizedOrNull()!!
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                .withAndroidShellRouteOwner(owner)

        val target = resolveAndroidShellLaunchTarget(pageData, multiShellHostEnabled = true)
        val taskLaunch = target.taskLaunchPlan(requestedTaskClear = true)

        val canonical = owner.shellCanonicalUri()
        assertEquals("slock://shell/https%3A%2F%2Fapi.test/user-1/server-b", canonical)
        assertEquals(canonical, target.shellInstanceId)
        assertEquals("server-b", target.serverId)
        assertEquals(owner, target.routeOwner)
        assertTrue(target.documentShell)
        assertFalse(taskLaunch.clearTask)
        assertEquals(canonical, taskLaunch.documentShellId)
        assertFalse(shouldUseGlobalHostDisplayMetrics(target))
    }

    @Test
    fun explicitDocumentRouteWithoutRouteOwnerFallsBackToDefaultShell() {
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)

        val target = resolveAndroidShellLaunchTarget(pageData, multiShellHostEnabled = true)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertEquals("server-b", target.serverId)
        assertFalse(target.documentShell)
    }

    @Test
    fun enabledDocumentRouteReusesRegisteredOwnerShell() {
        val owner =
            AndroidShellRouteOwner(
                environmentId = "https://api.test",
                accountId = "user-1",
                serverId = "server-b",
            ).normalizedOrNull()!!
        AndroidShellWindowRegistry.register(
            AndroidShellLaunchTarget(
                shellInstanceId = owner.shellCanonicalUri(),
                serverId = "server-b",
                documentShell = true,
                routeOwner = owner,
            )
        )
        val target =
            resolveAndroidShellLaunchTarget(
                JSONObject()
                    .put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")
                    .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                    .withAndroidShellRouteOwner(owner),
                multiShellHostEnabled = true
            )

        assertEquals(owner.shellCanonicalUri(), target.shellInstanceId)
        assertEquals(owner, target.routeOwner)
        assertTrue(target.documentShell)
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
    fun configRecreateKeepsShellWhileRealFinishReleasesIt() {
        assertFalse(shouldReleaseAndroidShellWindow(isFinishing = true, isChangingConfigurations = true))
        assertFalse(shouldReleaseAndroidShellWindow(isFinishing = false, isChangingConfigurations = false))
        assertTrue(shouldReleaseAndroidShellWindow(isFinishing = true, isChangingConfigurations = false))
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
    fun deadDocumentTaskInRecentsIsMatchedFromCanonicalUriWithoutPersistedRecord() {
        val backingStore = InMemoryRaftKeyValueStore()
        val previousProvider = AndroidShellWindowStore.storeProvider
        AndroidShellWindowStore.storeProvider = { backingStore }
        try {
            val serverCOwner = AndroidShellRouteOwner("https://api.test", "user-1", "server-c")
            val appTasks =
                listOf(
                    AndroidAppTaskSnapshot(
                        taskId = 18,
                        isAlive = false,
                        baseIntentData = serverCOwner.shellCanonicalUri(),
                        baseActivityClassName = "build.raft.app.shared.RaftDocumentShellActivity",
                    )
                )
            val reconciled = AndroidShellWindowStore.reconcile(appTasks, "https://api.test", "user-1")
            val switchToC =
                JSONObject()
                    .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                    .withAndroidShellRouteOwner(serverCOwner)

            // No destination -> moveToFront revives dead task 18.
            val keepMatch = androidServerSwitchReconciledWindowOrNull(switchToC, true, reconciled)!!
            assertEquals(18, keepMatch.bringForwardTaskId)
            assertTrue(keepMatch.isDead)
            assertTrue(keepMatch.target.documentShell)
            assertEquals(serverCOwner.shellCanonicalUri(), keepMatch.target.shellInstanceId)

            // With a destination tab -> startActivity reuses dead task 18 via documentLaunchMode=intoExisting.
            val tabSwitchToC = JSONObject(switchToC.toString()).put(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB, "activity")
            val tabMatch = androidServerSwitchReconciledWindowOrNull(tabSwitchToC, true, reconciled)!!
            assertNull(tabMatch.bringForwardTaskId)
            assertTrue(tabMatch.isDead)
            assertTrue(tabMatch.target.documentShell)

            // Different account ignores and does not match server C's task.
            assertTrue(AndroidShellWindowStore.reconcile(appTasks, "https://api.test", "user-2").isEmpty())
        } finally {
            AndroidShellWindowStore.storeProvider = previousProvider
        }
    }

    @Test
    fun pendingLauncherTaskLaunchConsumesOnceAndExpiresAfterTtl() {
        var now = 1_000L
        val previousClock = AndroidPendingLauncherTaskLaunchStore.elapsedMsProvider
        AndroidPendingLauncherTaskLaunchStore.elapsedMsProvider = { now }
        try {
            AndroidPendingLauncherTaskLaunchStore.stash(taskId = 16, serverId = "server-a", initialTab = "activity", tapEpochMillis = 99L)
            assertNull(AndroidPendingLauncherTaskLaunchStore.consume(taskId = 17))
            val consumed = AndroidPendingLauncherTaskLaunchStore.consume(taskId = 16)!!
            assertEquals("server-a", consumed.serverId)
            assertEquals("activity", consumed.initialTab)
            assertEquals(99L, consumed.tapEpochMillis)
            assertTrue(consumed.hasExplicitDestination)
            // One-shot: second consume returns null.
            assertNull(AndroidPendingLauncherTaskLaunchStore.consume(taskId = 16))

            // Expired stash returns null.
            AndroidPendingLauncherTaskLaunchStore.stash(taskId = 16, serverId = "server-a", initialTab = null)
            now += 15_000L
            assertNull(AndroidPendingLauncherTaskLaunchStore.consume(taskId = 16))
        } finally {
            AndroidPendingLauncherTaskLaunchStore.elapsedMsProvider = previousClock
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
    }

    @Test
    fun launcherRecordRegisteredPreservesPersistedServerAndNullAppTasksDoesNotWipeStore() {
        val backingStore = InMemoryRaftKeyValueStore()
        val previousProvider = AndroidShellWindowStore.storeProvider
        AndroidShellWindowStore.storeProvider = { backingStore }
        try {
            val ownerA = AndroidShellRouteOwner("https://api.test", "user-1", "server-a")
            val ownerB = AndroidShellRouteOwner("https://api.test", "user-1", "server-b")
            val launcherTarget = AndroidShellLaunchTarget(DEFAULT_ANDROID_SHELL_INSTANCE_ID, null, documentShell = false)
            AndroidShellWindowStore.recordRegistered(launcherTarget, taskId = 16, fallbackOwner = ownerA)
            assertEquals("server-a", AndroidShellWindowStore.persistedLauncherServerId("https://api.test", "user-1"))

            // LMK revival with global fallback owner pointing to server-b must not overwrite launcher's server-a.
            AndroidShellWindowStore.recordRegistered(launcherTarget, taskId = 16, fallbackOwner = ownerB)
            assertEquals("server-a", AndroidShellWindowStore.persistedLauncherServerId("https://api.test", "user-1"))

            // Failed ActivityManager snapshot (null) must not prune the persisted store.
            assertTrue(AndroidShellWindowStore.reconcile(null, "https://api.test", "user-1").isEmpty())
            assertEquals("server-a", AndroidShellWindowStore.persistedLauncherServerId("https://api.test", "user-1"))

            // Reassigned taskId now carrying a document canonical URI must not match as launcher.
            val reassignedTasks =
                listOf(
                    AndroidAppTaskSnapshot(
                        taskId = 16,
                        isAlive = false,
                        baseIntentData = ownerB.shellCanonicalUri(),
                        baseActivityClassName = "build.raft.app.shared.RaftDocumentShellActivity",
                    )
                )
            val reconciled = AndroidShellWindowStore.reconcile(reassignedTasks, "https://api.test", "user-1")
            assertEquals(1, reconciled.size)
            assertTrue(reconciled.single().documentShell)
            assertEquals("server-b", reconciled.single().serverId)
            assertNull(AndroidShellWindowStore.persistedLauncherServerId("https://api.test", "user-1"))
        } finally {
            AndroidShellWindowStore.storeProvider = previousProvider
        }
    }

    @Test
    fun reconciledRichDestinationDoesNotDropPayloadViaMoveToFront() {
        val ownerA = AndroidShellRouteOwner("https://api.test", "user-1", "server-a")
        val deadLauncher =
            listOf(
                ReconciledAndroidShellWindow(
                    shellInstanceId = DEFAULT_ANDROID_SHELL_INSTANCE_ID,
                    taskId = 16,
                    serverId = "server-a",
                    documentShell = false,
                    routeOwner = ownerA,
                    isAlive = false,
                )
            )
        val notificationToThread =
            JSONObject()
                .withAndroidShellRouteOwner(ownerA)
                .put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID, "ch-1")
        val match = androidServerSwitchReconciledWindowOrNull(notificationToThread, true, deadLauncher)!!
        assertNull(match.bringForwardTaskId)

        val shortcutToTarget =
            JSONObject()
                .withAndroidShellRouteOwner(ownerA)
                .put(RaftDesktopShortcutContract.KeyTargetId, "ch-2")
        assertNull(androidServerSwitchReconciledWindowOrNull(shortcutToTarget, true, deadLauncher)!!.bringForwardTaskId)
    }

    @Test
    fun chooseServerFromShellOrDocumentShellTargetIsNotPostAuthReplacement() {
        assertTrue(
            AndroidLoginTransitionRouting.isPostAuthReplacement(
                sourcePage = RaftPages.AUTH_CHOOSE_SERVER,
                destination = RaftPages.APP_SHELL,
                replaceCurrent = true,
                sourcePageData = JSONObject(),
                destinationPageData = JSONObject(),
            )
        )
        assertFalse(
            AndroidLoginTransitionRouting.isPostAuthReplacement(
                sourcePage = RaftPages.AUTH_CHOOSE_SERVER,
                destination = RaftPages.APP_SHELL,
                replaceCurrent = true,
                sourcePageData = JSONObject().put("slockChooseServerFromShell", true),
                destinationPageData = JSONObject(),
            )
        )
        assertFalse(
            AndroidLoginTransitionRouting.isPostAuthReplacement(
                sourcePage = RaftPages.AUTH_CHOOSE_SERVER,
                destination = RaftPages.APP_SHELL,
                replaceCurrent = true,
                sourcePageData = JSONObject(),
                destinationPageData = JSONObject().put(ANDROID_DOCUMENT_SHELL_KEY, true),
            )
        )
    }

    @Test
    fun optNonNullStringTreatsJsonNullAsEmpty() {
        val json = JSONObject().put(RAFT_SHELL_SERVER_ID, JSONObject.NULL).put("real", "  srv-1  ")
        assertEquals("", json.optNonNullString(RAFT_SHELL_SERVER_ID))
        assertEquals("srv-1", json.optNonNullString("real"))
    }

    @Test
    fun recycledLauncherTaskIdDoesNotMatchPersistedDocumentWindowInReconcile() {
        val backingStore = InMemoryRaftKeyValueStore()
        val previousProvider = AndroidShellWindowStore.storeProvider
        AndroidShellWindowStore.storeProvider = { backingStore }
        try {
            val ownerA = AndroidShellRouteOwner("https://api.test", "user-1", "server-a")
            val ownerB = AndroidShellRouteOwner("https://api.test", "user-1", "server-b")
            val docB =
                AndroidShellLaunchTarget(
                    shellInstanceId = ownerB.shellCanonicalUri(),
                    serverId = "server-b",
                    documentShell = true,
                    routeOwner = ownerB,
                )
            AndroidShellWindowStore.recordRegistered(docB, taskId = 25, fallbackOwner = null)

            // After reboot, Android reused taskId 25 for a brand-new MainActivity launcher task (no slock://shell URI).
            val recycledLauncherTask =
                listOf(
                    AndroidAppTaskSnapshot(
                        taskId = 25,
                        isAlive = true,
                        baseIntentData = null,
                        baseActivityClassName = "build.raft.app.MainActivity",
                    )
                )
            val reconciled = AndroidShellWindowStore.reconcile(recycledLauncherTask, "https://api.test", "user-1")
            assertTrue("recycled launcher task must not masquerade as server B's document window", reconciled.isEmpty())

            // Reusing taskId 25 for a different document URI (server A) must also not match persisted document B.
            AndroidShellWindowStore.recordRegistered(docB, taskId = 25, fallbackOwner = null)
            val otherDocumentTask =
                listOf(
                    AndroidAppTaskSnapshot(
                        taskId = 25,
                        isAlive = true,
                        baseIntentData = ownerA.shellCanonicalUri(),
                        baseActivityClassName = "build.raft.app.shared.RaftDocumentShellActivity",
                    )
                )
            val reconciledOther = AndroidShellWindowStore.reconcile(otherDocumentTask, "https://api.test", "user-1")
            assertEquals(1, reconciledOther.size)
            assertEquals("server-a", reconciledOther.single().serverId)
            assertEquals(ownerA.shellCanonicalUri(), reconciledOther.single().shellInstanceId)
        } finally {
            AndroidShellWindowStore.storeProvider = previousProvider
        }
    }

    @Test
    fun hasExplicitAndroidShellServerRecognizesShortcutAndShellServerKeys() {
        assertFalse(hasExplicitAndroidShellServer(JSONObject()))
        assertFalse(hasExplicitAndroidShellServer(JSONObject().put(RAFT_SHELL_SERVER_ID, JSONObject.NULL)))
        assertTrue(hasExplicitAndroidShellServer(JSONObject().put(RAFT_SHELL_SERVER_ID, "server-a")))
        assertTrue(hasExplicitAndroidShellServer(JSONObject().put(ANDROID_SHELL_SERVER_ID_KEY, "server-a")))
        assertTrue(
            hasExplicitAndroidShellServer(
                JSONObject().put(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-a")
            )
        )
        assertTrue(
            hasExplicitAndroidShellServer(
                JSONObject().put(RaftDesktopShortcutContract.KeyServerId, "server-s")
            )
        )
        // Non-document launcher payloads carrying only route-owner metadata must NOT suppress
        // persisted launcher server restoration; document windows DO honor slockRouteServerId.
        assertFalse(
            hasExplicitAndroidShellServer(
                JSONObject().put(ANDROID_ROUTE_SERVER_ID_KEY, "server-b")
            )
        )
        assertTrue(
            hasExplicitAndroidShellServer(
                JSONObject()
                    .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                    .put(ANDROID_ROUTE_SERVER_ID_KEY, "server-b")
            )
        )
    }

}
