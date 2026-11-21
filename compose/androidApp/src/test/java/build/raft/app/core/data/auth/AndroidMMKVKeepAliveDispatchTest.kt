package build.raft.app.core.data.auth

import android.app.Application
import build.raft.app.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import build.raft.app.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.shared.AndroidRealtimeKeepAliveController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidMMKVKeepAliveDispatchTest {
    private val queuedTasks = mutableListOf<() -> Unit>()

    @Before
    fun setUp() {
        queuedTasks.clear()
        AndroidAppScopeMMKV.keepAliveRunner = { task -> queuedTasks += task }
    }

    @After
    fun tearDown() {
        AndroidAppScopeMMKV.resetKeepAliveRunnerForTest()
    }

    @Test
    fun ignoresUnrelatedNamespacesAndKeys() {
        val app = RuntimeEnvironment.getApplication()
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = "otherNamespace",
            key = NOTIFICATION_ENABLED_PREFERENCE_KEY,
            value = "true",
        )
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = "unrelated_pref_key",
            value = "true",
        )
        assertEquals(0, queuedTasks.size)
    }

    @Test
    fun dispatchesNotificationAndKeepAlivePreferenceUpdatesOffCallerThread() {
        val app = RuntimeEnvironment.getApplication()
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = NOTIFICATION_ENABLED_PREFERENCE_KEY,
            value = "false",
        )
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = AndroidRealtimeKeepAliveController.localPreferenceKey(NOTIFICATION_ENABLED_PREFERENCE_KEY),
            value = "true",
        )
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY,
            value = "true",
        )
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = AndroidRealtimeKeepAliveController.localPreferenceKey(
                NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
            ),
            value = "false",
        )
        // All four matching preference writes enqueue asynchronously rather than running service IPC inline.
        assertEquals(4, queuedTasks.size)
    }
}
