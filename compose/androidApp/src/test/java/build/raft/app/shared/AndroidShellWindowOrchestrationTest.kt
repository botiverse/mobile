package build.raft.app.shared

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import build.raft.app.BuildConfig
import build.raft.app.MainActivity
import build.raft.app.MainActivityOnCreateShareAction
import build.raft.app.RAFT_SERVER_SWITCH_KEEP_WINDOW
import build.raft.app.RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS
import build.raft.app.RAFT_SERVER_SWITCH_WINDOW_ID
import build.raft.app.RAFT_SHELL_SERVER_ID
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftDesktopShortcutContract
import build.raft.app.dispatchMainActivityLaunchIntent
import build.raft.app.emailVerificationToken
import build.raft.app.isAndroidExternalShareIntent
import build.raft.app.isAppLoginCallbackIntent
import build.raft.app.isRaftMobileOAuthCallback
import build.raft.app.RaftMessageLinkIngress
import build.raft.app.postLoginAppShellPageData
import build.raft.app.resolveMainActivityOnCreateShareAction
import build.raft.app.resolveMainActivityShellPageData
import build.raft.app.settingsAccountAppShellPageData
import build.raft.app.shouldClearTaskForSharedShell
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidShellWindowOrchestrationTest {
    private val ownerA = AndroidShellRouteOwner("https://api.test", "user-1", "server-a")
    private val ownerB = AndroidShellRouteOwner("https://api.test", "user-1", "server-b")
    private lateinit var previousStoreProvider: () -> build.raft.app.data.SharedPreferenceStore?
    private lateinit var backingStore: InMemoryRaftKeyValueStore

    @Before
    fun setUp() {
        AndroidShellWindowRegistry.clearForTest()
        AndroidPendingLauncherTaskLaunchStore.clear()
        backingStore = InMemoryRaftKeyValueStore()
        previousStoreProvider = AndroidShellWindowStore.storeProvider
        AndroidShellWindowStore.storeProvider = { backingStore }
    }

    @After
    fun tearDown() {
        AndroidShellWindowRegistry.clearForTest()
        AndroidPendingLauncherTaskLaunchStore.clear()
        AndroidShellWindowStore.storeProvider = previousStoreProvider
    }

    private fun switchPageData(
        owner: AndroidShellRouteOwner,
        windowId: String? = null,
        keepWindow: Boolean = false,
        initialTab: String? = null,
        tapEpochMillis: Long = 0L,
    ): JSONObject =
        JSONObject()
            .put(ANDROID_DOCUMENT_SHELL_KEY, true)
            .withAndroidShellRouteOwner(owner)
            .apply {
                windowId?.let { put(RAFT_SERVER_SWITCH_WINDOW_ID, it) }
                if (keepWindow) put(RAFT_SERVER_SWITCH_KEEP_WINDOW, true)
                initialTab?.let { put(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB, it) }
                if (tapEpochMillis > 0L) put(RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS, tapEpochMillis)
            }

    @Test
    fun prepareAppShellCreateIntentConsumesPendingLauncherStashAndClearsHistoryFlag() {
        AndroidPendingLauncherTaskLaunchStore.stash(
            taskId = 16,
            serverId = "server-a",
            initialTab = "activity",
            tapEpochMillis = 9876L,
        )
        val launchIntent =
            Intent().apply {
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(RaftCmpActivity.KEY_PAGE_NAME, RaftCmpActivity.PAGE_APP_SHELL)
                putExtra(RaftCmpActivity.KEY_PAGE_DATA, JSONObject().toString())
            }

        val hasExplicitDestination =
            prepareAndroidAppShellCreateIntent(
                intent = launchIntent,
                taskId = 16,
                multiShellHostEnabled = true,
                currentOwner = { ownerB },
            )

        assertTrue(hasExplicitDestination)
        assertFalse(isAndroidIntentLaunchedFromHistory(launchIntent.flags))
        val merged = JSONObject(RaftCmpActivity.resolvedPageData(launchIntent).text)
        assertEquals("server-a", merged.getString(ANDROID_SHELL_SERVER_ID_KEY))
        assertEquals("server-a", merged.getString(RAFT_SHELL_SERVER_ID))
        assertEquals("activity", merged.getString(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB))
        assertEquals(9876L, merged.getLong(RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS))
        // One-shot stash is consumed.
        assertNull(AndroidPendingLauncherTaskLaunchStore.consume(16))
    }

    @Test
    fun prepareAppShellCreateIntentRestoresPersistedLauncherServerUnlessExplicitOrShortcutPresent() {
        val launcherTarget = AndroidShellLaunchTarget(DEFAULT_ANDROID_SHELL_INSTANCE_ID, null, documentShell = false)
        AndroidShellWindowStore.recordRegistered(launcherTarget, taskId = 16, fallbackOwner = ownerA)

        // Blank page data with multi-window ON restores persisted launcher server-a and strips history flag.
        val plainHistoryIntent =
            Intent().apply {
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                putExtra(RaftCmpActivity.KEY_PAGE_NAME, RaftCmpActivity.PAGE_APP_SHELL)
                putExtra(RaftCmpActivity.KEY_PAGE_DATA, JSONObject().toString())
            }
        assertFalse(
            prepareAndroidAppShellCreateIntent(
                intent = plainHistoryIntent,
                taskId = 16,
                multiShellHostEnabled = true,
                currentOwner = { ownerB },
            )
        )
        assertFalse(isAndroidIntentLaunchedFromHistory(plainHistoryIntent.flags))
        val restored = JSONObject(RaftCmpActivity.resolvedPageData(plainHistoryIntent).text)
        assertEquals("server-a", restored.getString(RAFT_SHELL_SERVER_ID))
        assertEquals("server-a", restored.getString(ANDROID_SHELL_SERVER_ID_KEY))

        // Fresh desktop shortcut launch names an explicit target server: must NOT overwrite with persisted launcher server-a.
        val shortcutIntent =
            Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(RaftCmpActivity.KEY_PAGE_NAME, RaftCmpActivity.PAGE_APP_SHELL)
                putExtra(
                    RaftCmpActivity.KEY_PAGE_DATA,
                    JSONObject().put(RaftDesktopShortcutContract.KeyServerId, "server-s").toString(),
                )
            }
        prepareAndroidAppShellCreateIntent(
            intent = shortcutIntent,
            taskId = 16,
            multiShellHostEnabled = true,
            currentOwner = { ownerB },
        )
        val shortcutMerged = JSONObject(RaftCmpActivity.resolvedPageData(shortcutIntent).text)
        assertEquals("server-s", shortcutMerged.getString(RaftDesktopShortcutContract.KeyServerId))
        assertEquals("", shortcutMerged.optNonNullString(RAFT_SHELL_SERVER_ID))
        assertEquals("", shortcutMerged.optNonNullString(ANDROID_SHELL_SERVER_ID_KEY))

        // History-replayed shortcut launch strips one-shot shortcut keys and restores persisted launcher server-a.
        val historyShortcutIntent =
            Intent().apply {
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                putExtra(RaftCmpActivity.KEY_PAGE_NAME, RaftCmpActivity.PAGE_APP_SHELL)
                putExtra(
                    RaftCmpActivity.KEY_PAGE_DATA,
                    JSONObject().put(RaftDesktopShortcutContract.KeyServerId, "server-s").toString(),
                )
            }
        prepareAndroidAppShellCreateIntent(
            intent = historyShortcutIntent,
            taskId = 16,
            multiShellHostEnabled = true,
            currentOwner = { ownerB },
        )
        assertFalse(isAndroidIntentLaunchedFromHistory(historyShortcutIntent.flags))
        val historyShortcutMerged = JSONObject(RaftCmpActivity.resolvedPageData(historyShortcutIntent).text)
        assertEquals("", historyShortcutMerged.optNonNullString(RaftDesktopShortcutContract.KeyServerId))
        assertEquals("server-a", historyShortcutMerged.getString(RAFT_SHELL_SERVER_ID))
        assertEquals("server-a", historyShortcutMerged.getString(ANDROID_SHELL_SERVER_ID_KEY))

        // Multi-window OFF must not restore persisted launcher server.
        val singleWindowIntent =
            Intent().apply {
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                putExtra(RaftCmpActivity.KEY_PAGE_NAME, RaftCmpActivity.PAGE_APP_SHELL)
                putExtra(RaftCmpActivity.KEY_PAGE_DATA, JSONObject().toString())
            }
        prepareAndroidAppShellCreateIntent(
            intent = singleWindowIntent,
            taskId = 16,
            multiShellHostEnabled = false,
            currentOwner = { ownerB },
        )
        val singleMerged = JSONObject(RaftCmpActivity.resolvedPageData(singleWindowIntent).text)
        assertEquals("", singleMerged.optNonNullString(RAFT_SHELL_SERVER_ID))
    }

    @Test
    fun mainActivityShellPageDataSkipsPersistedLauncherRestoreForShortcutServer() {
        val launcherTarget = AndroidShellLaunchTarget(DEFAULT_ANDROID_SHELL_INSTANCE_ID, null, documentShell = false)
        AndroidShellWindowStore.recordRegistered(launcherTarget, taskId = 16, fallbackOwner = ownerA)

        val shortcutIntent =
            Intent().apply {
                putExtra(RaftCmpActivity.KEY_PAGE_NAME, RaftCmpActivity.PAGE_APP_SHELL)
                putExtra(
                    RaftCmpActivity.KEY_PAGE_DATA,
                    JSONObject().put(RaftDesktopShortcutContract.KeyServerId, "server-s").toString(),
                )
            }
        val shortcutData =
            resolveMainActivityShellPageData(
                launchIntent = shortcutIntent,
                taskId = 16,
                multiShellHostEnabled = true,
                currentOwner = { ownerB },
            )
        assertEquals("server-s", shortcutData.getString(RaftDesktopShortcutContract.KeyServerId))
        assertEquals("", shortcutData.optNonNullString(RAFT_SHELL_SERVER_ID))
        assertEquals("", shortcutData.optNonNullString(ANDROID_SHELL_SERVER_ID_KEY))

        // Without explicit server, MainActivity restores persisted launcher server-a.
        val restoredData =
            resolveMainActivityShellPageData(
                launchIntent = Intent(Intent.ACTION_MAIN),
                taskId = 16,
                multiShellHostEnabled = true,
                currentOwner = { ownerB },
            )
        assertEquals("server-a", restoredData.getString(RAFT_SHELL_SERVER_ID))
        assertEquals("server-a", restoredData.getString(ANDROID_SHELL_SERVER_ID_KEY))

        // History-replayed app-shell intent strips one-shot shortcut/tab keys and restores persisted launcher server-a,
        // while a clean MainActivity policy intent still clears the dead trampoline task.
        val historyAppShellIntent =
            Intent().apply {
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                putExtra(RaftCmpActivity.KEY_PAGE_NAME, RaftCmpActivity.PAGE_APP_SHELL)
                putExtra(
                    RaftCmpActivity.KEY_PAGE_DATA,
                    JSONObject()
                        .put(RaftDesktopShortcutContract.KeyServerId, "server-s")
                        .put(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB, "activity")
                        .toString(),
                )
            }
        val historyData =
            resolveMainActivityShellPageData(
                launchIntent = historyAppShellIntent,
                taskId = 16,
                multiShellHostEnabled = true,
                currentOwner = { ownerB },
            )
        assertEquals("", historyData.optNonNullString(RaftDesktopShortcutContract.KeyServerId))
        assertEquals("", historyData.optNonNullString(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB))
        assertEquals("server-a", historyData.getString(RAFT_SHELL_SERVER_ID))
        assertEquals("server-a", historyData.getString(ANDROID_SHELL_SERVER_ID_KEY))
        assertFalse(historyAppShellIntent.shouldClearTaskForSharedShell())
        assertTrue(Intent().shouldClearTaskForSharedShell())

        // Non-document launcher payloads carrying only route-owner metadata (settingsAccount / postLogin)
        // must still restore the persisted launcher server-a rather than letting server-b overwrite it.
        listOf(
            settingsAccountAppShellPageData(ownerB),
            postLoginAppShellPageData(ownerB),
        ).forEach { nonDocPayload ->
            val launcherIntent =
                Intent().apply {
                    putExtra(RaftCmpActivity.KEY_PAGE_NAME, RaftCmpActivity.PAGE_APP_SHELL)
                    putExtra(RaftCmpActivity.KEY_PAGE_DATA, nonDocPayload.toString())
                }
            prepareAndroidAppShellCreateIntent(
                intent = launcherIntent,
                taskId = 16,
                multiShellHostEnabled = true,
                currentOwner = { ownerB },
            )
            val resolved = JSONObject(RaftCmpActivity.resolvedPageData(launcherIntent).text)
            assertEquals("server-a", resolved.getString(RAFT_SHELL_SERVER_ID))
            assertEquals("server-a", resolved.getString(ANDROID_SHELL_SERVER_ID_KEY))
        }
    }

    @Test
    fun mainActivityHistoryRevivalSkipsAllOneShotIntakeHandlersWhileFreshIntentsDispatchThem() {
        val scheme = BuildConfig.OAUTH_CALLBACK_SCHEME
        val cases =
            listOf(
                "external_share" to
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "shared snippet")
                    },
                "app_login_callback" to
                    Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://login/callback?code=abc123")),
                "mobile_oauth_callback" to
                    Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://oauth/callback?code=oauth123")),
                "email_verification" to
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://app.raft.build/?verify=verify-token")),
                "raft_message_link" to
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://app.raft.build/s/hq/channel/11111111-1111-4111-8111-111111111111" +
                                "?msg=22222222-2222-4222-8222-222222222222"
                        )
                    ),
            )

        // Verify onCreate share intake decision directly with and without FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY.
        val shareFresh = cases.first().second
        val shareHistory = Intent(shareFresh).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertEquals(MainActivityOnCreateShareAction.Submit, resolveMainActivityOnCreateShareAction(shareFresh))
        assertEquals(MainActivityOnCreateShareAction.ClearHistoryShare, resolveMainActivityOnCreateShareAction(shareHistory))
        assertEquals(MainActivityOnCreateShareAction.None, resolveMainActivityOnCreateShareAction(Intent(Intent.ACTION_MAIN)))

        fun runDispatch(intent: Intent): List<String> {
            val invoked = mutableListOf<String>()
            dispatchMainActivityLaunchIntent(
                intent = intent,
                onHistoryRevival = { invoked += "history_revival" },
                onExternalShare = { candidate ->
                    if (candidate?.isAndroidExternalShareIntent() == true) {
                        invoked += "external_share"
                        true
                    } else {
                        false
                    }
                },
                onAppLoginCallback = { candidate ->
                    if (candidate.isAppLoginCallbackIntent()) {
                        invoked += "app_login_callback"
                        true
                    } else {
                        false
                    }
                },
                onMobileOAuthCallback = { candidate ->
                    if (candidate?.data?.isRaftMobileOAuthCallback() == true) {
                        invoked += "mobile_oauth_callback"
                        true
                    } else {
                        false
                    }
                },
                onEmailVerificationLink = { candidate ->
                    if (candidate.emailVerificationToken() != null) {
                        invoked += "email_verification"
                        true
                    } else {
                        false
                    }
                },
                onRaftMessageLink = { candidate ->
                    val uri = candidate?.takeIf { it.action == Intent.ACTION_VIEW }?.data
                    if (uri != null && RaftMessageLinkIngress.isMessageLink(uri.toString(), "https://app.raft.build")) {
                        invoked += "raft_message_link"
                        true
                    } else {
                        false
                    }
                },
                onUnclaimedAppSchemeLink = { false },
                onUnsupportedRaftWebLink = { false },
                onOpenSharedShell = { invoked += "open_shared_shell" },
            )
            return invoked
        }

        cases.forEach { (expectedHandler, freshIntent) ->
            // Fresh intent enters its one-shot intake handler.
            assertEquals(listOf(expectedHandler), runDispatch(freshIntent))

            // Same intent replayed from Recents/history skips ALL one-shot handlers and revives the shell cleanly.
            val historyIntent = Intent(freshIntent).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
            assertEquals(listOf("history_revival"), runDispatch(historyIntent))
        }
    }

    @Test
    fun dispatchAppShellLaunchStashesAndMovesDeadLauncherTaskToFront() {
        val launcherTarget = AndroidShellLaunchTarget(DEFAULT_ANDROID_SHELL_INSTANCE_ID, null, documentShell = false)
        AndroidShellWindowStore.recordRegistered(launcherTarget, taskId = 16, fallbackOwner = ownerA)
        val deadLauncherTasks =
            listOf(
                AndroidAppTaskSnapshot(
                    taskId = 16,
                    isAlive = false,
                    baseIntentData = null,
                    baseActivityClassName = MainActivity::class.java.name,
                )
            )
        val broughtForward = mutableListOf<Pair<Int, Boolean>>()
        var launched = false

        RaftCmpActivity.dispatchAppShellLaunch(
            pageData = switchPageData(ownerA, initialTab = "activity", tapEpochMillis = 5555L),
            clearTask = false,
            multiShellHostEnabled = true,
            appTasksProvider = { deadLauncherTasks },
            bringToFront = { taskId, removeOnFailure ->
                broughtForward += taskId to removeOnFailure
                true
            },
            launchStarter = { _, _, _, _ -> launched = true },
        )

        assertEquals(listOf(16 to true), broughtForward)
        assertFalse(launched)
        val stashed = AndroidPendingLauncherTaskLaunchStore.consume(16)
        assertNotNull(stashed)
        assertEquals("server-a", stashed?.serverId)
        assertEquals("activity", stashed?.initialTab)
        assertEquals(5555L, stashed?.tapEpochMillis)
    }

    @Test
    fun dispatchAppShellLaunchClearsStashAndFallsBackWhenDeadLauncherMoveToFrontFails() {
        val launcherTarget = AndroidShellLaunchTarget(DEFAULT_ANDROID_SHELL_INSTANCE_ID, null, documentShell = false)
        AndroidShellWindowStore.recordRegistered(launcherTarget, taskId = 16, fallbackOwner = ownerA)
        val deadLauncherTasks =
            listOf(
                AndroidAppTaskSnapshot(
                    taskId = 16,
                    isAlive = false,
                    baseIntentData = null,
                    baseActivityClassName = MainActivity::class.java.name,
                )
            )
        var launchedDocId: String? = null
        var launchedHost: Class<out Activity>? = null
        var launchedPageData: JSONObject? = null

        RaftCmpActivity.dispatchAppShellLaunch(
            pageData = switchPageData(ownerA, initialTab = "activity", tapEpochMillis = 5555L),
            clearTask = false,
            multiShellHostEnabled = true,
            appTasksProvider = { deadLauncherTasks },
            bringToFront = { _, _ -> false },
            launchStarter = { resolvedPageData, _, documentShellId, hostActivityClass ->
                launchedPageData = resolvedPageData
                launchedDocId = documentShellId
                launchedHost = hostActivityClass
            },
        )

        // Failed moveToFront must clear the launcher stash and fall back to opening a document shell for server-a.
        assertNull(AndroidPendingLauncherTaskLaunchStore.consume(16))
        assertEquals(ownerA.shellCanonicalUri(), launchedDocId)
        assertEquals(RaftDocumentShellActivity::class.java, launchedHost)
        assertEquals("server-a", launchedPageData?.optString(RAFT_SHELL_SERVER_ID))
    }

    @Test
    fun dispatchAppShellLaunchRevivesDeadDocumentTaskOrDeliversKeptTabViaStarter() {
        val deadDocumentTasks =
            listOf(
                AndroidAppTaskSnapshot(
                    taskId = 22,
                    isAlive = false,
                    baseIntentData = ownerB.shellCanonicalUri(),
                    baseActivityClassName = RaftDocumentShellActivity::class.java.name,
                )
            )

        // Without destination: revives dead document task via moveToFront without touching launcher stash.
        val broughtForward = mutableListOf<Pair<Int, Boolean>>()
        var launchedWithoutTab = false
        RaftCmpActivity.dispatchAppShellLaunch(
            pageData = switchPageData(ownerB, keepWindow = true),
            clearTask = false,
            multiShellHostEnabled = true,
            appTasksProvider = { deadDocumentTasks },
            bringToFront = { taskId, removeOnFailure ->
                broughtForward += taskId to removeOnFailure
                true
            },
            launchStarter = { _, _, _, _ -> launchedWithoutTab = true },
        )
        assertEquals(listOf(22 to true), broughtForward)
        assertFalse(launchedWithoutTab)
        assertNull(AndroidPendingLauncherTaskLaunchStore.consume(22))

        // With a kept tab: skips moveToFront and uses startActivity so intoExisting delivers the tab.
        var bringCalls = 0
        var launchedDocId: String? = null
        var launchedHost: Class<out Activity>? = null
        RaftCmpActivity.dispatchAppShellLaunch(
            pageData = switchPageData(ownerB, initialTab = "tasks"),
            clearTask = false,
            multiShellHostEnabled = true,
            appTasksProvider = { deadDocumentTasks },
            bringToFront = { _, _ ->
                bringCalls += 1
                true
            },
            launchStarter = { _, _, documentShellId, hostActivityClass ->
                launchedDocId = documentShellId
                launchedHost = hostActivityClass
            },
        )
        assertEquals(0, bringCalls)
        assertEquals(ownerB.shellCanonicalUri(), launchedDocId)
        assertEquals(RaftDocumentShellActivity::class.java, launchedHost)
    }
}
