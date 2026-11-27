package build.raft.app.shared

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import build.raft.app.BuildConfig
import build.raft.app.MainActivity
import build.raft.app.MainActivityOnCreateShareAction
import build.raft.app.shell.RAFT_SERVER_SWITCH_KEEP_WINDOW
import build.raft.app.shell.RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS
import build.raft.app.shell.RAFT_SERVER_SWITCH_WINDOW_ID
import build.raft.app.shell.RAFT_SHELL_SERVER_ID
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftDesktopShortcutContract
import build.raft.app.dispatchMainActivityLaunchIntent
import build.raft.app.emailVerificationToken
import build.raft.app.isAndroidExternalShareIntent
import build.raft.app.isAppLoginCallbackIntent
import build.raft.app.isRaftMobileOAuthCallback
import build.raft.app.thread.RaftMessageLinkIngress
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidShellWindowOrchestrationTest {
    private val ownerA = AndroidShellRouteOwner("https://api.test", "user-1", "server-a")
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
    fun buildStartIntentMarksPreserveLiveShellOnlyForNonClearAppShellReveals() {
        val context = RuntimeEnvironment.getApplication()
        val revealIntent =
            RaftCmpActivity.buildStartIntent(
                context = context,
                pageName = RaftCmpActivity.PAGE_APP_SHELL,
                pageData = JSONObject(),
                clearTask = false,
                hostActivityClass = RaftAppShellActivity::class.java,
                preserveLiveShell = true,
            )
        assertTrue(revealIntent.getBooleanExtra(RaftCmpActivity.KEY_PRESERVE_LIVE_SHELL_ON_NEW_INTENT, false))
        assertEquals(RaftCmpActivity.PAGE_APP_SHELL, revealIntent.getStringExtra(RaftCmpActivity.KEY_PAGE_NAME))
        assertEquals(0, revealIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertTrue((revealIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((revealIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)

        val defaultIntent =
            RaftCmpActivity.buildStartIntent(
                context = context,
                pageName = RaftCmpActivity.PAGE_APP_SHELL,
                pageData = JSONObject(),
                clearTask = false,
                hostActivityClass = RaftAppShellActivity::class.java,
                preserveLiveShell = false,
            )
        assertFalse(defaultIntent.getBooleanExtra(RaftCmpActivity.KEY_PRESERVE_LIVE_SHELL_ON_NEW_INTENT, false))

        val clearTaskIntent =
            RaftCmpActivity.buildStartIntent(
                context = context,
                pageName = RaftCmpActivity.PAGE_APP_SHELL,
                pageData = JSONObject(),
                clearTask = true,
                hostActivityClass = RaftAppShellActivity::class.java,
                preserveLiveShell = true,
            )
        assertFalse(clearTaskIntent.getBooleanExtra(RaftCmpActivity.KEY_PRESERVE_LIVE_SHELL_ON_NEW_INTENT, false))

        val loginIntent =
            RaftCmpActivity.buildStartIntent(
                context = context,
                pageName = RaftCmpActivity.PAGE_LOGIN,
                pageData = JSONObject(),
                clearTask = false,
                preserveLiveShell = true,
            )
        assertFalse(loginIntent.getBooleanExtra(RaftCmpActivity.KEY_PRESERVE_LIVE_SHELL_ON_NEW_INTENT, false))
    }

    @Test
    fun externalShareStartIntentPreservesLiveAppShellWithoutClearTaskAndCarriesOpaqueToken() {
        val context = RuntimeEnvironment.getApplication()
        val pageData =
            build.raft.app.androidExternalSharePageData(
                build.raft.app.AndroidExternalShareLaunch("opaque-token_1234567890")
            )
        val shareStartIntent =
            RaftCmpActivity.buildStartIntent(
                context = context,
                pageName = RaftCmpActivity.PAGE_APP_SHELL,
                pageData = pageData,
                clearTask = false,
                hostActivityClass = RaftAppShellActivity::class.java,
                preserveLiveShell = true,
            )

        assertEquals(0, shareStartIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertTrue((shareStartIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((shareStartIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
        assertTrue(shareStartIntent.getBooleanExtra(RaftCmpActivity.KEY_PRESERVE_LIVE_SHELL_ON_NEW_INTENT, false))
        assertTrue(
            shouldPreserveLiveAppShellOnNewIntent(
                currentPageName = RaftCmpActivity.PAGE_APP_SHELL,
                incomingPageName = shareStartIntent.getStringExtra(RaftCmpActivity.KEY_PAGE_NAME).orEmpty(),
                preserveRequested = shareStartIntent.getBooleanExtra(RaftCmpActivity.KEY_PRESERVE_LIVE_SHELL_ON_NEW_INTENT, false),
            )
        )
        assertEquals(
            "opaque-token_1234567890",
            externalShareTokenFromPageDataOrNull(RaftCmpActivity.resolvedPageData(shareStartIntent).text)
        )
    }
}
